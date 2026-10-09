"""mit-app-api 配置（全部可用环境变量覆盖）"""

import os
from pathlib import Path

# 引擎（manga-image-translator 的 server/main.py）地址
# 容器里通过 host.docker.internal 指向宿主机上已发布的 8010
ENGINE_URL = os.getenv("MIT_ENGINE_URL", "http://host.docker.internal:8010").rstrip("/")

# MySQL / Redis（compose 里按服务名互访）
DB_HOST = os.getenv("MIT_DB_HOST", "app-db")
DB_PORT = int(os.getenv("MIT_DB_PORT", "3306"))
DB_USER = os.getenv("MIT_DB_USER", "mit")
DB_PASSWORD = os.getenv("MIT_DB_PASSWORD", "mit")
DB_NAME = os.getenv("MIT_DB_NAME", "mit")

REDIS_URL = os.getenv("MIT_REDIS_URL", "redis://app-redis:6379/0")
# 单独传密码，不拼进 URL —— 密码里的 @ : / 等字符在 URL 里需要转义，容易踩坑
REDIS_PASSWORD = os.getenv("MIT_REDIS_PASSWORD", "").strip()

# 数据目录（容器内挂载 /data）
DATA_DIR = Path(os.getenv("MIT_DATA_DIR", "/data"))
CACHE_DIR = DATA_DIR / "cache" / "result"
BOOKS_DIR = DATA_DIR / "books"
CLOUD_DIR = DATA_DIR / "cloud"          # 云端同步的书 zip
RELEASES_DIR = DATA_DIR / "releases"    # App 安装包（挂载卷，放包即发布，无需重启容器）

# 访问令牌：留空=不校验（仅内网自用时可以留空）
API_TOKEN = os.getenv("MIT_API_TOKEN", "").strip()

# 跨页上下文默认取前几页（0=关闭）；App 显式传 context 时以 App 为准
CONTEXT_PAGES = int(os.getenv("MIT_CONTEXT_PAGES", "1"))

# 单页翻译超时（秒）
ENGINE_TIMEOUT = float(os.getenv("MIT_ENGINE_TIMEOUT", "900"))

# 图片无损压缩：译文图 PNG → WebP 无损（省流量且不降质，保存时转，下载零额外延迟）。
# 0 = 关闭（直接存/发 PNG）
IMAGE_COMPRESS = int(os.getenv("MIT_IMAGE_COMPRESS", "1"))

# L1 结果缓存的 TTL（秒）：图片按内容 hash 落磁盘，结构化结果 JSON 放 Redis。
# 0 = 永不过期（依赖 Redis AOF 持久化）；默认 14 天，过期就重跑管线。
CACHE_TTL = int(os.getenv("MIT_CACHE_TTL", "1209600"))

# 翻译结果保留天数：超期自动删 DB 里的页/块/上下文 + 磁盘图片与结果文件。
# 0 = 永不清理；默认 14 天（App 本地有缓存，服务端只留 14 天兜底）。
RETENTION_DAYS = int(os.getenv("MIT_RETENTION_DAYS", "14"))

# 默认管线配置，App 传的 config 会与之深合并
DEFAULT_CONFIG = {
    "translator": {"translator": os.getenv("MIT_TRANSLATOR", "deepseek"),
                   "target_lang": os.getenv("MIT_TARGET_LANG", "CHS")},
    "detector": {"detector": "ctd", "detection_size": 2048},
    "ocr": {"ocr": "mocr", "use_mocr_merge": True},
    "inpainter": {"inpainter": "lama_large", "inpainting_size": 1024,
                  "inpainting_precision": "bf16"},
    "render": {"renderer": "default", "font_color": "000000"},
}

# 计算 config_hash 时忽略的字段（上下文属于"软输入"，不参与缓存键，
# 否则同一页换个邻居就要重翻，缓存会变得没用）
CACHE_IGNORED_KEYS = ("context_text",)


def engine_url_for_host() -> str:
    """给日志用"""
    return ENGINE_URL
