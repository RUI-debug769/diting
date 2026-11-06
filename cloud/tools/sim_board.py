#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""假装自己是板子：把一个 wav 文件按板子的帧格式推给云端。

板子和手机之间那套帧（4 字节小端长度 + PCM）在这里原样复现，
所以云端在处理真实板子之前，可以先用录音把它跑通。

    python tools/sim_board.py --wav test.wav --host 127.0.0.1 --port 19001 --realtime
"""

from __future__ import annotations

import argparse
import socket
import struct
import sys
import time
import wave
from pathlib import Path

SAMPLE_RATE = 16000
FRAME_BYTES = 1024  # 与板子实现一致：每帧 512 个采样点


def read_wav(path: Path) -> bytes:
    with wave.open(str(path), "rb") as w:
        if w.getframerate() != SAMPLE_RATE:
            sys.exit(f"采样率要是 {SAMPLE_RATE}，这个文件是 {w.getframerate()}")
        if w.getnchannels() != 1:
            sys.exit("要单声道")
        if w.getsampwidth() != 2:
            sys.exit("要 16bit")
        return w.readframes(w.getnframes())


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--wav", required=True)
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=19001)
    p.add_argument("--realtime", action="store_true", help="按真实时间推，否则尽快推完")
    p.add_argument("--repeat", type=int, default=1)
    args = p.parse_args()

    pcm = read_wav(Path(args.wav))
    total = len(pcm) // (2 * SAMPLE_RATE)
    print(f"{args.wav}: {total:.1f}s，{len(pcm)} 字节")

    with socket.create_connection((args.host, args.port), timeout=10) as s:
        for _ in range(args.repeat):
            for i in range(0, len(pcm), FRAME_BYTES):
                frame = pcm[i : i + FRAME_BYTES]
                s.sendall(struct.pack("<I", len(frame)) + frame)
                if args.realtime:
                    time.sleep(len(frame) / (2 * SAMPLE_RATE))
            if args.realtime:
                time.sleep(1.0)  # 留一段静音，让 VAD 收尾
        s.shutdown(socket.SHUT_WR)
    print("推完")


if __name__ == "__main__":
    main()
