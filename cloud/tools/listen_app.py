#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""假装自己是 App：连上云的 19002，把事件按行打出来。

    python tools/listen_app.py --host <你的服务器> --port 19002
"""
import argparse, socket, sys

p = argparse.ArgumentParser()
p.add_argument("--host", default="127.0.0.1")
p.add_argument("--port", type=int, default=19002)
a = p.parse_args()

with socket.create_connection((a.host, a.port), timeout=10) as s:
    print(f"连上 {a.host}:{a.port}，等事件…", flush=True)
    buf = b""
    while True:
        chunk = s.recv(4096)
        if not chunk:
            print("服务器断开", flush=True)
            sys.exit(0)
        buf += chunk
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            print(line.decode("utf-8", "replace"), flush=True)
