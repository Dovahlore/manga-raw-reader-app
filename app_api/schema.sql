-- ============================================================
--  mit-app-api 数据库结构（MySQL 8+ / InnoDB / utf8mb4）
--
--  原则：DB 只存"长期要查的索引 + 结构化结果"；图片/缓存走磁盘文件（L1）。
--  4 张表，外键 + 级联删除：
--    books(id) ← pages.book_id          ON DELETE CASCADE
--    books(id) ← page_context.book_id   ON DELETE CASCADE
--    pages(id) ← page_blocks.page_id    ON DELETE CASCADE
--
--  已砍掉（冗余 / 只写不读）：
--    jobs         —— 与 pages.status/attempts/error 完全重复，且响应里不暴露 job_id，客户端查不到
--    cache_index  —— 只 INSERT/UPDATE 从不 SELECT，纯摆设（真正的缓存就是 cache/result/*.png 文件）
--
--  首次启动由 app_api.main → db.init_schema 执行；
--  老库升级由 db.init_schema 里的迁移步骤处理（幂等）。
-- ============================================================

-- 账号表：API Key ↔ 用户 id，并挂用量/活跃时间等元数据。
-- 各表 owner 列存的是 users.id（字符串）；未配 Key（鉴权关闭）时 owner='default' 不入表。
CREATE TABLE IF NOT EXISTS users (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  api_key_hash   CHAR(64) NOT NULL UNIQUE,    -- SHA-256(API Key)，鉴权时用它找用户
  name           VARCHAR(128) NULL,           -- 可选昵称
  token_used     BIGINT NOT NULL DEFAULT 0,   -- 累计 token 消耗（引擎暂未上报，预留）
  page_count     INT NOT NULL DEFAULT 0,      -- 累计翻页次数（含重译）
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  last_active_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS books (
  id          VARCHAR(191) PRIMARY KEY,
  owner       VARCHAR(191) NOT NULL DEFAULT 'default',  -- = users.id（未配 Key 时 'default'）
  title       VARCHAR(512) NULL,
  format      VARCHAR(32)  NULL,          -- epub / mobi / cbz / folder / unknown
  mode        VARCHAR(16)  NULL,          -- manga / normal（云同步书的阅读模式）
  page_count  INT          NULL,
  order_dir   VARCHAR(8)   NULL,          -- ltr / rtl（日漫右开本）
  -- 云同步列（zip_path 为 NULL = 未同步；本地书/云端书共用一张表，省掉 cloud_books 与 id 同步）
  hash        VARCHAR(64)  NULL,          -- 源文件 sha256（同账号去重）
  fingerprint VARCHAR(64)  NULL,
  zip_path    VARCHAR(512) NULL,          -- 云同步 zip 路径
  size        BIGINT       NULL,
  folder_id   BIGINT       NULL,          -- 云端收藏夹 id（软引用 cloud_folders.id）
  synced_at   DATETIME     NULL,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_owner (owner),
  UNIQUE KEY uniq_owner_hash (owner, hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS pages (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  book_id     VARCHAR(191) NOT NULL,
  page_index  INT          NOT NULL,
  order_dir   VARCHAR(8)   NULL,
  orig_sha1   CHAR(40)     NOT NULL,
  config_hash CHAR(40)     NOT NULL,      -- 只含影响管线的配置，不含上下文
  orig_path   VARCHAR(512) NULL,
  out_path    VARCHAR(512) NULL,
  json_path   VARCHAR(512) NULL,          -- 完整结果文件（含每块 text/background），给校对/下载用
  status      VARCHAR(16)  NOT NULL DEFAULT 'pending', -- pending/running/done/failed
  attempts    INT          NOT NULL DEFAULT 0,
  error       TEXT         NULL,
  elapsed_ms  INT          NULL,
  tokens      INT          NULL,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uniq_book_page (book_id, page_index),
  KEY idx_sha1 (orig_sha1),
  CONSTRAINT fk_pages_book FOREIGN KEY (book_id) REFERENCES books (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 每页的块级结构化结果（src/dst/bbox/颜色/角度）——长期留存：
--   * /v1/pages/{id}/json 直接从这里读，不碰磁盘文件
--   * 未来"换翻译器重译"可复用这些框/原文，省掉重跑检测+OCR
-- (page_id, idx) 天然唯一，直接做复合主键（省掉自增 id 和二级索引）
CREATE TABLE IF NOT EXISTS page_blocks (
  page_id  BIGINT NOT NULL,
  idx      INT    NOT NULL,
  minx     INT NULL, miny INT NULL, maxx INT NULL, maxy INT NULL,
  angle    DOUBLE NULL,
  prob     DOUBLE NULL,
  fg       VARCHAR(32) NULL, bg VARCHAR(32) NULL,
  src_text TEXT NULL,
  dst_text TEXT NULL,
  PRIMARY KEY (page_id, idx),
  CONSTRAINT fk_pageblocks_page FOREIGN KEY (page_id) REFERENCES pages (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 跨页上下文：按 book_id 分桶，杜绝"两本书串页"；书删了跟着删
CREATE TABLE IF NOT EXISTS page_context (
  book_id    VARCHAR(191) NOT NULL,
  page_index INT          NOT NULL,
  src_text   TEXT NULL,
  dst_text   TEXT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (book_id, page_index),
  CONSTRAINT fk_pagecontext_book FOREIGN KEY (book_id) REFERENCES books (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 任务表：给 App 看进度（POST /v1/pages/translate?async=1 → 轮询 /v1/jobs/{id}）
CREATE TABLE IF NOT EXISTS jobs (
  id          VARCHAR(36) PRIMARY KEY,
  owner       VARCHAR(191) NOT NULL DEFAULT 'default',  -- 任务归属账号（与 books.owner 同源）
  book_id     VARCHAR(191) NULL,           -- whole-book 任务归属的书（删书/取消同步时按书取消后台任务）
  page_id     BIGINT      NULL,           -- 完成后回填；页删了跟着删
  action      VARCHAR(32) NOT NULL,       -- translate / retranslate
  status      VARCHAR(16) NOT NULL,       -- queued / running / done / failed
  attempts    INT NOT NULL DEFAULT 0,
  config_json JSON NULL,
  error       TEXT NULL,
  queued_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
  started_at  DATETIME NULL,
  finished_at DATETIME NULL,
  KEY idx_status (status),
  KEY idx_owner (owner),
  KEY idx_jobs_book (book_id),
  CONSTRAINT fk_jobs_page FOREIGN KEY (page_id) REFERENCES pages (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- 云同步收藏夹（多账号：owner = users.id；未配 Key 时 'default'）
-- books.folder_id 软引用这里的 id（删夹不删书，App 端按 name 匹配）
-- ============================================================

CREATE TABLE IF NOT EXISTS cloud_folders (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  owner      VARCHAR(191) NOT NULL DEFAULT 'default',
  name       VARCHAR(191) NOT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uniq_owner_folder (owner, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- App 自动更新发布表：App 启动/设置页查 /v1/app/update 拉最新版本。
-- 发布 = 把 APK 放进 /data/releases（挂载卷）+ 插一行；接口实时查库，无需重启容器。
-- ============================================================
CREATE TABLE IF NOT EXISTS app_releases (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  version_code     INT NOT NULL,
  version_name     VARCHAR(64) NOT NULL,
  channel          VARCHAR(16) NOT NULL DEFAULT 'stable',   -- stable / beta
  platform         VARCHAR(16) NOT NULL DEFAULT 'android',  -- android（Quest 同 android）
  abi              VARCHAR(32) NOT NULL DEFAULT 'universal',-- arm64-v8a / universal
  min_version_code INT NOT NULL DEFAULT 0,                  -- 低于此版本强制更新
  `force`          TINYINT(1) NOT NULL DEFAULT 0,           -- force 是 MySQL 保留字，需反引号
  changelog        TEXT NULL,
  file_path        VARCHAR(512) NOT NULL,                   -- 相对 /data/releases 的文件名
  size             BIGINT NOT NULL,
  sha256           CHAR(64) NOT NULL,
  enabled          TINYINT(1) NOT NULL DEFAULT 1,           -- 发布/下架开关（回滚用）
  created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uniq_release (channel, platform, abi, version_code),
  KEY idx_latest (channel, platform, abi, enabled, version_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- 阅读进度（按 owner+hash 存：hash 是内容哈希，本地书/云端书通用，跨设备同步）
-- last_read_at 用客户端毫秒时间戳做 LWW（最后写入者胜）。
-- ============================================================
CREATE TABLE IF NOT EXISTS reading_progress (
  owner        VARCHAR(191) NOT NULL DEFAULT 'default',
  hash         VARCHAR(64) NOT NULL,           -- 书的内容 hash（跨设备同一本书）
  page         INT NOT NULL,
  last_read_at BIGINT NOT NULL,                -- 客户端毫秒时间戳
  updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (owner, hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
