#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""声纹注册：把「我」的声音登记进去。

    python enroll.py me my-1.wav my-2.wav my-3.wav

三段录音会各自算一条 192 维向量，再取平均（按段数归一）写进
`data/voiceprints.json`。脚本会顺带打印三段之间的两两相似度：
如果它们彼此都到不了 0.6，说明录音环境差异太大，**不要注册**——
拿这样的平均值去卡 0.62，主人自己也会被拒。

只看 `sherpa-onnx` 的 CAM++ 中文模型，和线上服务用的是同一个。
"""

from __future__ import annotations

import argparse
import json
import sys
from itertools import combinations
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from diting_cloud import SAMPLE_RATE, Voiceprints, cosine  # noqa: E402

try:
    import sherpa_onnx
except ImportError:  # pragma: no cover
    raise SystemExit("缺少 sherpa-onnx：pip install -r requirements.txt")


def load_wav(path: Path) -> np.ndarray:
    import wave

    with wave.open(str(path), "rb") as w:
        assert w.getframerate() == SAMPLE_RATE, f"{path} 采样率应为 {SAMPLE_RATE}"
        assert w.getnchannels() == 1, f"{path} 应为单声道"
        assert w.getsampwidth() == 2, f"{path} 应为 16bit"
        raw = w.readframes(w.getnframes())
    return np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("wavs", nargs="+")
    p.add_argument("--models", default="models")
    p.add_argument("--voiceprints", default="data/voiceprints.json")
    p.add_argument("--threshold", type=float, default=0.62)
    p.add_argument("--name", default="me")
    args = p.parse_args()

    cfg = sherpa_onnx.SpeakerEmbeddingExtractorConfig()
    cfg.model = str(Path(args.models) / "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx")
    cfg.num_threads = 1
    cfg.provider = "cpu"
    if not cfg.validate():
        raise SystemExit("声纹模型缺失：" + cfg.model)
    extractor = sherpa_onnx.SpeakerEmbeddingExtractor(cfg)

    vectors: list[list[float]] = []
    for name in args.wavs:
        samples = load_wav(Path(name))
        stream = extractor.create_stream()
        stream.accept_waveform(SAMPLE_RATE, samples)
        stream.input_finished()
        vectors.append(list(extractor.compute(stream)))
        print(f"{name}: {len(samples) / SAMPLE_RATE:.1f}s 已取向量")

    print("\n两两相似度（同一个人应该 > 0.6）：")
    scores = []
    for (i, a), (j, b) in combinations(list(enumerate(vectors)), 2):
        sc = cosine(a, b)
        scores.append(sc)
        print(f"  {Path(args.wavs[i]).name} vs {Path(args.wavs[j]).name}: {sc:.3f}")

    if scores and min(scores) < 0.6:
        print("\n⚠ 最低一对只有 %.3f，录音环境差异太大，注册后可能被自己拒。" % min(scores))
        if input("仍然写入？[y/N] ").strip().lower() != "y":
            raise SystemExit("已取消")

    mean = np.mean(np.asarray(vectors, dtype=np.float32), axis=0)
    mean = mean / (np.linalg.norm(mean) + 1e-9)

    prints = Voiceprints(Path(args.voiceprints), len(mean), args.threshold)
    prints.vectors[args.name] = [float(x) for x in mean]
    prints._save()
    print(f"\n写入 {args.voiceprints}：{args.name}，{len(mean)} 维，阈值 {args.threshold}")


if __name__ == "__main__":
    main()
