"""极简 MySQL 连接池 + 常用读写。

所有函数是同步的；异步端点里请用 starlette.concurrency.run_in_threadpool 调用，
避免阻塞事件循环。
"""

import hashlib
import queue
import threading
import time
from pathlib import Path

import pymysql
from pymysql.cursors import DictCursor

from . import settings as S

_pool: "queue.LifoQueue" = queue.LifoQueue()
_MAX_POOL = 8
_create_lock = threading.Lock()


def _new_conn():
    return pymysql.connect(
        host=S.DB_HOST, port=S.DB_PORT, user=S.DB_USER, password=S.DB_PASSWORD,
        database=S.DB_NAME, charset="utf8mb4", autocommit=True,
        cursorclass=DictCursor, connect_timeout=10, read_timeout=60, write_timeout=60,
    )


def _acquire():
    try:
        conn = _pool.get_nowait()
        conn.ping(reconnect=True)
        return conn
    except queue.Empty:
        return _new_conn()
    except Exception:
        return _new_conn()


def _release(conn):
    try:
        if _pool.qsize() < _MAX_POOL:
            _pool.put(conn)
        else:
            conn.close()
    except Exception:
        try:
            conn.close()
        except Exception:
            pass


def query(sql, args=None):
    conn = _acquire()
    try:
        with conn.cursor() as cur:
            cur.execute(sql, args or ())
            return cur.fetchall()
    finally:
        _release(conn)


def query_one(sql, args=None):
    rows = query(sql, args)
    return rows[0] if rows else None


def execute(sql, args=None):
    conn = _acquire()
    try:
        with conn.cursor() as cur:
            cur.execute(sql, args or ())
            return cur.lastrowid, cur.rowcount
    finally:
        _release(conn)


def _split_statements(text: str):
    out = []
    for raw in text.split(";"):
        lines = [ln for ln in raw.splitlines() if not ln.strip().startswith("--")]
        stmt = "\n".join(lines).strip()
        if stmt:
            out.append(stmt)
    return out


def init_schema(retries: int = 30, delay: float = 2.0):
    """建表 + 迁移（幂等）。容器编排时 MySQL 可能还没就绪，这里做重试。"""
    stmts = _split_statements(Path(__file__).with_name("schema.sql").read_text(encoding="utf-8"))
    last_err = None
    for attempt in range(1, retries + 1):
        try:
            conn = _new_conn()
            with conn.cursor() as cur:
                # ---- 迁移老库（幂等）----
                # 1) cache_index 只写不读 → 直接删
                cur.execute("DROP TABLE IF EXISTS cache_index")
                # 2) page_blocks 老结构是自增 id 主键 → 改成 (page_id, idx) 复合主键
                cur.execute(
                    "SELECT COUNT(*) AS c FROM information_schema.COLUMNS "
                    "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='page_blocks' AND COLUMN_NAME='id'"
                )
                if cur.fetchone()["c"] > 0:
                    cur.execute("DROP TABLE IF EXISTS page_blocks")
                # 3) jobs 老结构没 page_id 外键 → 重建
                cur.execute(
                    "SELECT COUNT(*) AS c FROM information_schema.KEY_COLUMN_USAGE "
                    "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='jobs' AND CONSTRAINT_NAME='fk_jobs_page'"
                )
                if cur.fetchone()["c"] == 0:
                    cur.execute("DROP TABLE IF EXISTS jobs")
                # 4) 老库的 pages 没有外键 → 整体重建 4 张表（升级只在第一次发生）
                cur.execute(
                    "SELECT COUNT(*) AS c FROM information_schema.KEY_COLUMN_USAGE "
                    "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pages' AND CONSTRAINT_NAME='fk_pages_book'"
                )
                if cur.fetchone()["c"] == 0:
                    cur.execute("SET FOREIGN_KEY_CHECKS=0")
                    for t in ("page_blocks", "page_context", "pages", "books"):
                        cur.execute(f"DROP TABLE IF EXISTS {t}")
                    cur.execute("SET FOREIGN_KEY_CHECKS=1")
                # 5) 多账号改造：books/jobs 补 owner 列（幂等；全新库时表还没建，跳过）
                for tbl in ("books", "jobs"):
                    cur.execute(
                        "SELECT COUNT(*) AS c FROM information_schema.TABLES "
                        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=%s", (tbl,))
                    if cur.fetchone()["c"] == 0:
                        continue
                    cur.execute(
                        "SELECT COUNT(*) AS c FROM information_schema.COLUMNS "
                        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=%s AND COLUMN_NAME='owner'",
                        (tbl,))
                    if cur.fetchone()["c"] == 0:
                        cur.execute(
                            f"ALTER TABLE {tbl} ADD COLUMN owner VARCHAR(191) NOT NULL DEFAULT 'default'")
                # 6) 旧的 owner='default'（X-User-Id 时代）迁到「单一 API Key 的 SHA-256」；
                #    多 Key（逗号分隔）时无法判定归属，保持不动；表可能已被后续合并删掉，容错跳过
                if S.API_TOKEN and "," not in S.API_TOKEN:
                    new_owner = hashlib.sha256(S.API_TOKEN.encode()).hexdigest()
                    for tbl in ("cloud_folders", "cloud_books", "books"):
                        try:
                            cur.execute(f"UPDATE {tbl} SET owner=%s WHERE owner='default'", (new_owner,))
                        except Exception:      # noqa: BLE001
                            pass
                # ---- 建表（IF NOT EXISTS）----
                for s in stmts:
                    cur.execute(s)
                # 7) users 表：把老 owner（sha256 形式）迁成 users.id（幂等）
                #    迁移后 owner 变成纯数字 users.id，不会再命中下面的 sha256 判断
                def _looks_sha256(v):
                    return bool(v) and len(v) == 64 and all(c in "0123456789abcdefABCDEF" for c in v)
                for tbl in ("cloud_folders", "cloud_books", "books", "jobs"):
                    try:
                        cur.execute(f"SELECT DISTINCT owner FROM {tbl}")
                        owners = [r["owner"] for r in cur.fetchall() if _looks_sha256(r["owner"])]
                    except Exception:      # noqa: BLE001
                        owners = []
                    for o in owners:
                        cur.execute(
                            "INSERT INTO users (api_key_hash) VALUES (%s) "
                            "ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)", (o,))
                        cur.execute("SELECT id FROM users WHERE api_key_hash=%s", (o,))
                        uid = cur.fetchone()["id"]
                        cur.execute(f"UPDATE {tbl} SET owner=%s WHERE owner=%s", (str(uid), o))
                # 8) cloud_books 并入 books（单表改造，幂等）
                #    a. 给 books 补云列
                cloud_cols = {
                    "mode": "VARCHAR(16) NULL",
                    "hash": "VARCHAR(64) NULL",
                    "fingerprint": "VARCHAR(64) NULL",
                    "zip_path": "VARCHAR(512) NULL",
                    "size": "BIGINT NULL",
                    "folder_id": "BIGINT NULL",
                    "synced_at": "DATETIME NULL",
                }
                for col, ddl in cloud_cols.items():
                    cur.execute(
                        "SELECT COUNT(*) AS c FROM information_schema.COLUMNS "
                        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='books' AND COLUMN_NAME=%s", (col,))
                    if cur.fetchone()["c"] == 0:
                        cur.execute(f"ALTER TABLE books ADD COLUMN {col} {ddl}")
                #    b. 补 uniq_owner_hash 索引
                cur.execute(
                    "SELECT COUNT(*) AS c FROM information_schema.STATISTICS "
                    "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='books' AND INDEX_NAME='uniq_owner_hash'")
                if cur.fetchone()["c"] == 0:
                    cur.execute("ALTER TABLE books ADD UNIQUE KEY uniq_owner_hash (owner, hash)")
                #    c. 若 cloud_books 仍存在：合并数据后删表
                cur.execute(
                    "SELECT COUNT(*) AS c FROM information_schema.TABLES "
                    "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='cloud_books'")
                if cur.fetchone()["c"] > 0:
                    cur.execute(
                        "INSERT INTO books (id, owner, title, mode, page_count, hash, fingerprint, "
                        "zip_path, size, folder_id, synced_at) "
                        "SELECT id, owner, title, mode, page_count, hash, fingerprint, "
                        "zip_path, size, folder_id, synced_at FROM cloud_books "
                        "ON DUPLICATE KEY UPDATE mode=VALUES(mode), hash=VALUES(hash), fingerprint=VALUES(fingerprint), "
                        "zip_path=VALUES(zip_path), size=VALUES(size), folder_id=VALUES(folder_id), synced_at=VALUES(synced_at)")
                    cur.execute("DROP TABLE IF EXISTS cloud_books")
                # 9) 把配置的 API Key 种进 users 表（默认用户；幂等）
                #    支持逗号分隔多 Key：每个 Key 一个用户。启动即存在，不用等首次请求。
                if S.API_TOKEN:
                    for token in [t.strip() for t in S.API_TOKEN.split(",") if t.strip()]:
                        h = hashlib.sha256(token.encode()).hexdigest()
                        cur.execute(
                            "INSERT INTO users (api_key_hash) VALUES (%s) "
                            "ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)", (h,))
                # 10) jobs 补 book_id 列：whole-book 任务之前没记录书，删书/取消同步时
                #     无法按书取消后台任务（孤儿 job 继续翻）。加这列后可按书取消。
                cur.execute(
                    "SELECT COUNT(*) AS c FROM information_schema.COLUMNS "
                    "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='jobs' AND COLUMN_NAME='book_id'")
                if cur.fetchone()["c"] == 0:
                    cur.execute("ALTER TABLE jobs ADD COLUMN book_id VARCHAR(191) NULL")
                cur.execute(
                    "SELECT COUNT(*) AS c FROM information_schema.STATISTICS "
                    "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='jobs' AND INDEX_NAME='idx_jobs_book'")
                if cur.fetchone()["c"] == 0:
                    cur.execute("CREATE INDEX idx_jobs_book ON jobs (book_id)")
                # 11) 外键补全：jobs.book_id、reading_progress → books（先清孤儿，再建 FK；幂等）
                #     a. 清孤儿 reading_progress（找不到同 owner+hash 的书）
                cur.execute(
                    "DELETE rp FROM reading_progress rp "
                    "LEFT JOIN books b ON b.owner=rp.owner AND b.hash=rp.hash "
                    "WHERE b.id IS NULL")
                #     b. 清孤儿 jobs（book_id 指向已删书）
                cur.execute(
                    "DELETE j FROM jobs j "
                    "LEFT JOIN books b ON b.id=j.book_id "
                    "WHERE j.book_id IS NOT NULL AND b.id IS NULL")
                #     c. 建 FK（幂等）
                cur.execute(
                    "SELECT COUNT(*) AS c FROM information_schema.KEY_COLUMN_USAGE "
                    "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='jobs' AND CONSTRAINT_NAME='fk_jobs_book'")
                if cur.fetchone()["c"] == 0:
                    cur.execute(
                        "ALTER TABLE jobs ADD CONSTRAINT fk_jobs_book "
                        "FOREIGN KEY (book_id) REFERENCES books (id) ON DELETE CASCADE")
                cur.execute(
                    "SELECT COUNT(*) AS c FROM information_schema.KEY_COLUMN_USAGE "
                    "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='reading_progress' AND CONSTRAINT_NAME='fk_readingprogress_book'")
                if cur.fetchone()["c"] == 0:
                    cur.execute(
                        "ALTER TABLE reading_progress ADD CONSTRAINT fk_readingprogress_book "
                        "FOREIGN KEY (owner, hash) REFERENCES books (owner, hash) ON DELETE CASCADE")
            conn.close()
            return len(stmts)
        except Exception as e:      # noqa: BLE001
            last_err = e
            print(f"[db] 等待 MySQL 就绪… ({attempt}/{retries}) {type(e).__name__}: {e}", flush=True)
            time.sleep(delay)
    raise RuntimeError(f"MySQL 连接/建表失败: {last_err}")


def ping() -> bool:
    try:
        query_one("SELECT 1 AS ok")
        return True
    except Exception:
        return False
