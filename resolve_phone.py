# -*- coding: utf-8 -*-
"""解析 MIT-Reader 手机的地址与 TXT，并探测构建版本指纹。"""
import json
import socket
import time
import urllib.request

from zeroconf import Zeroconf

NAME = "MIT-Reader-2510DRK44C._mitreader._tcp.local."
TYPE = "_mitreader._tcp.local."

zc = Zeroconf()
info = zc.get_service_info(TYPE, NAME, timeout=5000)
zc.close()
if info is None:
    print("RESOLVE-FAIL")
    raise SystemExit(1)

addrs = [socket.inet_ntoa(a) for a in info.addresses]
txt = {k.decode() if isinstance(k, bytes) else k: (v.decode() if isinstance(v, bytes) else v)
       for k, v in (info.properties or {}).items()}
print("ADDRESSES:", addrs)
print("PORT:", info.port)
print("TXT:", txt)

host = addrs[0]
port = info.port
base = "http://%s:%s" % (host, port)


def post(path, body, headers=None, timeout=5):
    req = urllib.request.Request(
        base + path, data=body.encode(), method="POST",
        headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode()[:200]
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:200]
    except Exception as e:
        return None, repr(e)


# 指纹 1：未带 token 的 /session —— 新构建返回 401，旧构建不校验
print("probe /session no-token:", post("/session", "{}"))
# 指纹 2：/remote/heartbeat 假 token —— 两代构建都返回 ok:false JSON
print("probe /remote/heartbeat:", post("/remote/heartbeat", json.dumps({"token": "probe"})))
