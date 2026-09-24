#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
本地自托管「看日志 → 自动修」agent（V1）。

职责循环（每 AGENT_INTERVAL 秒一轮）：
  1. 检查 docker 容器健康（docker ps）；
  2. 拉取各容器最近日志，按错误特征匹配（Traceback / ERROR / FATAL / OOM / CUDA out of memory / 5xx / crash）；
  3. 命中错误后，收集错误上下文 + 报错源码文件，喂给 DeepSeek；
  4. DeepSeek 返回「根因说明 + 文件级补丁」；
  5. 若 AUTO_APPLY=1：把补丁落到 git 分支 agent-fix-<时间戳>，重建并重启受影响容器；
     若 DRY_RUN=1：只写报告，不改文件、不重启；
  6. 全程写 agent/agent.log，诊断报告写 agent/reports/。

环境变量：
  DRY_RUN=1           只诊断出报告，不落盘不改不重启（默认 0）
  AUTO_APPLY=1        自动改文件 + 重建 + 重启（默认 1，DRY_RUN=1 时强制关闭）
  AGENT_INTERVAL=300  轮询间隔秒数（默认 300 = 5 分钟）
  DEEPSEEK_API_KEY=…  覆盖 secret.env 里的 key

用法：
  python agent/autofix.py
  DRY_RUN=1 python agent/autofix.py
"""

import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
AGENT_DIR = ROOT / "agent"
LOG_FILE = AGENT_DIR / "agent.log"
REPORT_DIR = AGENT_DIR / "reports"

# 要盯的容器（跟 docker-compose.full.yml 对齐）
CONTAINERS = ["mit-app-api", "mit-engine", "mit-app-db", "mit-app-redis", "mit-frpc"]

INTERVAL_SECONDS = int(os.environ.get("AGENT_INTERVAL", "300"))
DRY_RUN = os.environ.get("DRY_RUN", "0") == "1"
AUTO_APPLY = (os.environ.get("AUTO_APPLY", "1") == "1") and not DRY_RUN

# ---- DeepSeek（OpenAI 兼容）----
DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"
DEEPSEEK_MODEL = os.environ.get("DEEPSEEK_MODEL", "deepseek-chat")
DEEPSEEK_KEY = ""


def _read_secret_key() -> str:
    p = ROOT / "secret.env"
    if p.exists():
        for line in p.read_text(encoding="utf-8", errors="ignore").splitlines():
            if line.strip().startswith("DEEPSEEK_API_KEY="):
                return line.strip().split("=", 1)[1].strip()
    return ""


DEEPSEEK_KEY = os.environ.get("DEEPSEEK_API_KEY") or _read_secret_key()

# 错误特征（按行匹配）
ERROR_PATTERNS = [
    r"Traceback \(most recent call last\)",
    r"(?i)\bERROR\b",
    r"(?i)\bFATAL\b",
    r"(?i)CUDA out of memory",
    r"(?i)OutOfMemory",
    r"(?i)\bOOM\b",
    r"(?i)Killed\b",
    r"(?i)exit code [1-9]",
    r"(?i)500 Internal Server Error",
    r"(?i)RuntimeError",
    r"(?i)ConnectionError",
    r"(?i)Unhandled exception",
    r"(?i)panic",
]


def log(msg: str):
    line = f"[{datetime.now():%Y-%m-%d %H:%M:%S}] {msg}"
    print(line, flush=True)
    try:
        LOG_FILE.parent.mkdir(parents=True, exist_ok=True)
        with LOG_FILE.open("a", encoding="utf-8") as f:
            f.write(line + "\n")
    except Exception:
        pass


def sh(cmd: list, timeout=60) -> str:
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                           errors="ignore", cwd=str(ROOT))
        return (r.stdout or "") + (r.stderr or "")
    except Exception as e:  # noqa: BLE001
        return f"[agent] 命令失败 {' '.join(cmd)}: {e}"


def check_health() -> dict:
    """返回 {container: status}，status 含 'Up'/'healthy'/'Exited' 等。"""
    out = sh(["docker", "ps", "-a", "--format", "{{.Names}}\t{{.Status}}"])
    status = {}
    for line in out.splitlines():
        if "\t" in line:
            name, st = line.split("\t", 1)
            status[name.strip()] = st.strip()
    return status


def recent_logs(container: str, minutes: int = 5) -> str:
    # 只取最近 N 分钟，避免重复扫历史老错
    return sh(["docker", "logs", "--since", f"{minutes}m", container])


def detect_errors(text: str):
    hits = []
    for line in text.splitlines():
        for pat in ERROR_PATTERNS:
            if re.search(pat, line):
                hits.append(line.strip())
                break
    return hits


def extract_relevant_files(err_text: str):
    """从报错里抓本仓库的源码文件路径（app_api/…、android/…）。"""
    files = set()
    for m in re.finditer(r'([\w./-]+\.(?:py|kt|kts|java))', err_text):
        p = m.group(1)
        if "app_api" in p or "android" in p or "manga_translator" in p:
            files.add(p)
    return sorted(files)


def read_files(paths, max_bytes=8000):
    """读源码文件片段（截断，控制 token）。"""
    buf = []
    for p in paths:
        fp = ROOT / p
        if not fp.exists():
            # 报错里的路径可能是相对 app_api 的，试试去掉前缀
            fp = ROOT / "app_api" / p
        if fp.exists():
            try:
                content = fp.read_text(encoding="utf-8", errors="ignore")[:max_bytes]
                buf.append(f"### 文件: {p}\n```\n{content}\n```")
            except Exception:
                pass
    return "\n\n".join(buf)


def ask_deepseek(error_text: str, file_context: str) -> dict:
    """让 DeepSeek 诊断并给出文件级补丁。返回 {summary, files:[{path,content}]} 或抛异常。"""
    if not DEEPSEEK_KEY:
        raise RuntimeError("没有 DEEPSEEK_API_KEY（检查 secret.env 或环境变量）")

    prompt = (
        "你是资深后端/Android 工程师。下面是我在跑的服务里抓到的运行日志错误。\n"
        "请：1) 用中文一句话说清根因；2) 给出最小修复补丁。\n\n"
        "【错误日志】\n" + error_text[:6000] + "\n\n"
        "【相关源码】\n" + file_context[:12000] + "\n\n"
        "只输出一个 JSON 对象，不要解释，不要 markdown 代码块：\n"
        '{"summary": "根因一句话", "files": [{"path": "相对仓库根路径", "content": "完整新文件内容"}]}\n'
        "如果不需要改代码（纯运行时问题），files 给空数组。"
    )
    body = json.dumps({
        "model": DEEPSEEK_MODEL,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.1,
        "response_format": {"type": "json_object"},
    }).encode("utf-8")
    req = urllib.request.Request(
        DEEPSEEK_URL, data=body,
        headers={"Authorization": f"Bearer {DEEPSEEK_KEY}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=180) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    content = data["choices"][0]["message"]["content"]
    # 容错：剥掉可能包裹的 ```json ... ```
    content = re.sub(r"^```(?:json)?\s*|\s*```$", "", content.strip(), flags=re.S)
    return json.loads(content)


def apply_patch(report: dict, container: str):
    """把补丁落到新分支并提交；返回分支名或 None。"""
    files = report.get("files") or []
    if not files:
        return None
    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    branch = f"agent-fix-{ts}"
    # 基于当前分支切一个新分支
    sh(["git", "checkout", "-b", branch])
    for f in files:
        path = (ROOT / f["path"]).resolve()
        # 安全边界：只允许改仓库内的文件
        if not str(path).startswith(str(ROOT.resolve())):
            log(f"  拒绝越界路径: {f['path']}")
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f["content"], encoding="utf-8")
        log(f"  已写 {f['path']}")
    sh(["git", "add", "-A"])
    sh(["git", "commit", "-m", f"agent: 自动修复 {container}\n\n{report.get('summary', '')}"])
    return branch


def rebuild(container: str):
    """重建并重启受影响容器。"""
    # app-api 和 engine 有 build；db/redis/frpc 无 build 直接 up -d 即可
    buildable = {"mit-app-api", "mit-engine"}
    if container in buildable:
        log(f"  重建镜像 {container} ...")
        sh(["docker", "compose", "--env-file", "app.env", "-f", "docker-compose.full.yml",
            "build", container])
    log(f"  重启容器 {container} ...")
    sh(["docker", "compose", "--env-file", "app.env", "-f", "docker-compose.full.yml",
        "up", "-d", container])


def save_report(container: str, error_text: str, report: dict, branch: str or None):
    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    p = REPORT_DIR / f"{ts}-{container}.md"
    p.write_text(
        f"# 自动诊断报告\n\n- 时间: {datetime.now()}\n- 容器: {container}\n"
        f"- 分支: {branch or '(未改代码)'}\n- 根因: {report.get('summary', '')}\n\n"
        f"## 错误日志\n```\n{error_text[:4000]}\n```\n",
        encoding="utf-8",
    )
    log(f"  报告: {p.name}")


def main():
    if not DEEPSEEK_KEY:
        log("⚠️  未找到 DEEPSEEK_API_KEY，agent 无法调用模型（只做健康检查）")
    log(f"启动 agent：interval={INTERVAL_SECONDS}s dry_run={DRY_RUN} auto_apply={AUTO_APPLY}")
    seen = {}  # 错误签名去重，避免同一错误反复处理

    while True:
        try:
            status = check_health()
            for c in CONTAINERS:
                st = status.get(c, "")
                if "Up" not in st:
                    log(f"⚠️  容器 {c} 不健康: {st}")
                    if AUTO_APPLY:
                        log(f"  自动重启 {c} ...")
                        sh(["docker", "compose", "--env-file", "app.env",
                            "-f", "docker-compose.full.yml", "up", "-d", c])

            for c in CONTAINERS:
                logs = recent_logs(c)
                hits = detect_errors(logs)
                if not hits:
                    continue
                # 去重：用容器+第一行错误做签名，5 轮内不重复处理
                sig = f"{c}::{hits[0][:120]}"
                if sig in seen and (time.time() - seen[sig]) < INTERVAL_SECONDS * 3:
                    continue
                seen[sig] = time.time()

                err_text = "\n".join(hits[:60])
                log(f"🔎 检测到错误 [{c}]: {hits[0][:160]}")
                files = extract_relevant_files(logs + err_text)
                ctx = read_files(files)
                try:
                    report = ask_deepseek(err_text, ctx)
                except Exception as e:  # noqa: BLE001
                    log(f"  调用 DeepSeek 失败: {e}")
                    continue
                branch = None
                if AUTO_APPLY and (report.get("files")):
                    branch = apply_patch(report, c)
                    rebuild(c)
                else:
                    log(f"  根因: {report.get('summary', '')}")
                save_report(c, err_text, report, branch)
        except KeyboardInterrupt:
            log("agent 停止")
            return
        except Exception as e:  # noqa: BLE001
            log(f"循环异常（继续下一轮）: {e}")

        time.sleep(INTERVAL_SECONDS)


if __name__ == "__main__":
    main()
