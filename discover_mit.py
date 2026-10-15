# -*- coding: utf-8 -*-
"""用 app 同款 NSD(mDNS) 服务类型在局域网发现设备，探测 20 秒。"""
import socket
import time
from zeroconf import Zeroconf, ServiceBrowser

FOUND = {}

class Listener:
    def add_service(self, *args):
        zc, type_, name = args[-3], args[-2], args[-1]
        try:
            info = zc.get_service_info(type_, name, timeout=3000)
        except Exception as e:
            print("resolve-fail", name, e)
            return
        if info is None:
            print("resolve-none", name)
            return
        addrs = [socket.inet_ntoa(a) for a in info.addresses]
        txt = {}
        try:
            for k, v in (info.properties or {}).items():
                txt[k.decode() if isinstance(k, bytes) else k] = (
                    v.decode() if isinstance(v, bytes) else v)
        except Exception:
            pass
        FOUND[name] = (addrs, info.port, txt)
        print("FOUND name=%r addrs=%s port=%s txt=%s" % (name, addrs, info.port, txt))

    def update_service(self, *args):
        pass

    def remove_service(self, *args):
        print("REMOVED", args[-1])

zc = Zeroconf()
ServiceBrowser(zc, "_mitreader._tcp.local.", Listener)
print("browsing for 20s...")
time.sleep(20)
zc.close()
print("TOTAL:", len(FOUND))
