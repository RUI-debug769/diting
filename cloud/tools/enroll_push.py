#!/usr/bin/env python3
"""把一段 wav 推给云端的注册端口，模拟 App 里「注册我的声音」。

    python tools/enroll_push.py --wav models/zh.wav --port 19003
"""
import argparse
import json
import socket
import struct
import sys
import wave


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--wav", required=True)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=19003)
    ap.add_argument("--chunk", type=int, default=1024)
    a = ap.parse_args()

    with wave.open(a.wav, "rb") as w:
        if w.getnchannels() != 1 or w.getsampwidth() != 2 or w.getframerate() != 16000:
            print(f"{a.wav} 不是 16k/16bit/单声道", file=sys.stderr)
            return 1
        frames = w.readframes(w.getnframes())

    with socket.create_connection((a.host, a.port), timeout=10) as s:
        for i in range(0, len(frames), a.chunk):
            part = frames[i : i + a.chunk]
            s.sendall(struct.pack("<I", len(part)) + part)
        s.shutdown(socket.SHUT_WR)
        line = s.makefile("rb").readline()

    print(line.decode("utf-8", "replace").strip())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
