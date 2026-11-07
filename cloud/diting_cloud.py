#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""谛听 · 云端入口

一条链路上的四件事，全在这一个进程里：

    板子 --TCP 19001--> [VAD 切片] --> [声纹闸门] --> [SenseVoice 识别] --TCP 19002--> App

音频只在这一跳之后变成文字；只有通过声纹闸门的那一句才会被推给 App，
其他人说话时 App 收到的是 accepted=false 的事件（现场演示用得上，能看见"它拒绝了"）。

帧格式与手机端 `NetRx.java` 一致，方便板子那边直接复用已有代码：
    4 字节小端长度 + PCM（16000Hz / 16bit / 单声道 / 小端）

用法见 README.md。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import struct
import time
from datetime import datetime
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np

try:
    import sherpa_onnx
except ImportError:  # pragma: no cover
    raise SystemExit("缺少 sherpa-onnx：见 requirements.txt")

SAMPLE_RATE = 16000


def cosine(a: list[float], b: list[float]) -> float:
    va, vb = np.asarray(a, dtype=np.float32), np.asarray(b, dtype=np.float32)
    denom = float(np.linalg.norm(va) * np.linalg.norm(vb))
    return 0.0 if denom == 0 else float(va @ vb / denom)
BYTES_PER_SAMPLE = 2
MAX_FRAME = 8192  # 与 NetRx.java 的上限一致

log = logging.getLogger("diting")


# ---------------------------------------------------------------- 模型


@dataclass
class Models:
    vad_cfg: Any
    speaker: Any
    asr: Any | None
    vad_window: int = 512

    def new_vad(self):
        return sherpa_onnx.VoiceActivityDetector(self.vad_cfg, buffer_size_in_seconds=30)

    @classmethod
    def load(cls, model_dir: Path, with_asr: bool = True, threads: int = 1) -> "Models":
        vad_cfg = sherpa_onnx.VadModelConfig()
        vad_cfg.silero_vad.model = str(model_dir / "silero_vad.onnx")
        vad_cfg.silero_vad.threshold = 0.42
        vad_cfg.silero_vad.min_silence_duration = 0.75
        vad_cfg.silero_vad.min_speech_duration = 0.30
        vad_cfg.silero_vad.max_speech_duration = 8.0
        vad_cfg.silero_vad.window_size = 512
        vad_cfg.sample_rate = SAMPLE_RATE
        vad_cfg.num_threads = threads
        vad_cfg.provider = "cpu"
        if not vad_cfg.validate():
            raise SystemExit("VAD 配置不合法，检查 model 路径")
        sp_cfg = sherpa_onnx.SpeakerEmbeddingExtractorConfig()
        sp_cfg.model = str(model_dir / "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx")
        sp_cfg.num_threads = threads
        sp_cfg.provider = "cpu"
        if not sp_cfg.validate():
            raise SystemExit("声纹模型缺失：" + sp_cfg.model)
        speaker = sherpa_onnx.SpeakerEmbeddingExtractor(sp_cfg)

        asr = None
        if with_asr:
            asr_model = model_dir / "model.int8.onnx"
            tokens = model_dir / "tokens.txt"
            if asr_model.exists() and tokens.exists():
                asr = sherpa_onnx.OfflineRecognizer.from_sense_voice(
                    model=str(asr_model),
                    tokens=str(tokens),
                    num_threads=threads,
                    use_itn=True,
                    # auto：SenseVoice 自己判定语种。写死 "zh" 时英文会被按中文
                    # 音素解码，英文句子会碎成汉字或直接空。
                    language="auto",
                )
            else:
                log.warning("ASR 模型不在 %s，先只跑 VAD+声纹", model_dir)

        return cls(vad_cfg=vad_cfg, speaker=speaker, asr=asr)


# ---------------------------------------------------------------- 声纹


class Voiceprints:
    """只记一个主人：`owner`。其余一律判为他人。

    `search()` 用 sherpa-onnx 自带的 SpeakerEmbeddingManager 做余弦相似度检索，
    阈值默认 0.62 —— CAM++ 在中文 16k 上同人通常在 0.7 以上、异人 0.4 以下，
    0.62 是留出噪声余量的位置。现场可以调，调完写进 /status 能看见。
    """

    OWNER = "me"

    def __init__(self, path: Path, dim: int, threshold: float):
        self.path = path
        self.threshold = threshold
        self.dim = dim
        # 主人可以存最多 3 条向量：同一支麦克风下不同音量/语速差异很大，
        # 取最高分比死守一条更容易认对，同时不放宽阈值。
        self.vectors: dict[str, list[list[float]]] = {}
        self.MAX_PER_SPEAKER = 3
        self._load()

    def _load(self) -> None:
        if not self.path.exists():
            return
        data = json.loads(self.path.read_text())
        raw = data.get("speakers", {})
        for name, vec in raw.items():
            if vec and isinstance(vec[0], (int, float)):
                self.vectors[name] = [list(vec)]          # 旧格式：单条
            else:
                self.vectors[name] = [list(x) for x in vec]
        log.info("载入声纹：%s", list(self.vectors))

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        speakers = {
            k: [[float(x) for x in one] for one in vecs] for k, vecs in self.vectors.items()
        }
        self.path.write_text(
            json.dumps(
                {"owner": self.OWNER, "threshold": self.threshold, "speakers": speakers},
                ensure_ascii=False,
            )
        )

    @property
    def enrolled(self) -> bool:
        return self.OWNER in self.vectors

    def enroll(self, embedding: list[float]) -> None:
        """同一个人最多留 3 条：和已有的太像就替换那条，不然就追加。"""
        vecs = self.vectors.setdefault(self.OWNER, [])
        emb = list(embedding)
        best_i, best = -1, -1.0
        for i, one in enumerate(vecs):
            sc = cosine(one, emb)
            if sc > best:
                best_i, best = i, sc
        if best >= 0.92 and best_i >= 0:
            vecs[best_i] = emb                     # 几乎一样，替换，别把库里塞满
        else:
            vecs.append(emb)
            if len(vecs) > self.MAX_PER_SPEAKER:
                vecs.pop(0)
        self._save()

    def score(self, embedding: list[float]) -> float:
        """和主人所有模板里最像的那条比。"""
        if not self.enrolled:
            return 0.0
        return max(cosine(one, embedding) for one in self.vectors[self.OWNER])

    def wipe(self) -> None:
        """删掉主人向量：之后谁说话都是 unregistered，重新录一次才算数。"""
        self.vectors.clear()
        self._save()

    def judge(self, embedding: list[float], extra: float = 0.0) -> tuple[str, float]:
        """返回 (说话人, 相似度)。没注册过就返回 ("unregistered", 0)。

        extra 是临时加严量：一秒以内的短句提取出的向量不稳，同人分数会掉、
        异人也可能偶然冒高，所以短句按更高一点的阈值判。
        """
        if not self.enrolled:
            return "unregistered", 0.0
        score = self.score(embedding)
        need = min(0.99, self.threshold + extra)
        return (self.OWNER if score >= need else "other"), score


# ---------------------------------------------------------------- 管线


@dataclass
class Event:
    ts: float
    speaker: str
    score: float
    text: str
    duration: float
    accepted: bool
    lang: str = ""
    noise: bool = False

    def to_json(self) -> str:
        return json.dumps(
            {
                "type": "utterance",
                "ts": self.ts,
                "speaker": self.speaker,
                "score": round(self.score, 3),
                "text": self.text,
                "duration": round(self.duration, 2),
                "accepted": self.accepted,
                "lang": self.lang,
            },
            ensure_ascii=False,
        )


class Pipeline:
    """音频进来，事件出去。所有推理都在线程池里跑，不挡事件循环。"""

    def __init__(self, models: Models, prints: Voiceprints):
        self.models = models
        self.prints = prints
        self._lock = asyncio.Lock()
        self._asr_lang = ""

    # SenseVoice 会给每段打一个事件标签：<|Speech|> 是人在说话，
    # <|BGM|> / <|Applause|> / <|Laughter|> 这些是音乐、掌声、笑声。
    # 它会把背景音乐「听写」成歌词一样的中文，而声纹那边对音乐也可能给出
    # 0.5 出头的分数、刚好越过阈值——两件事叠在一起，音乐就被记成主人说的话了。
    # 模型自己给的事件标签比声纹分数更可信：非 Speech 一律不记。
    NOISE_EVENTS = {"BGM", "Applause", "Laughter", "Cry", "Sneeze", "Cough"}

    def _embed(self, samples: np.ndarray) -> list[float]:
        stream = self.models.speaker.create_stream()
        stream.accept_waveform(SAMPLE_RATE, samples)
        stream.input_finished()
        return list(self.models.speaker.compute(stream))

    def _asr(self, samples: np.ndarray) -> str:
        self._asr_event = ""
        if self.models.asr is None:
            return ""
        stream = self.models.asr.create_stream()
        stream.accept_waveform(SAMPLE_RATE, samples)
        self.models.asr.decode_stream(stream)
        result = stream.result
        self._asr_event = getattr(result, "event", "") or ""
        self._asr_lang = (getattr(result, "lang", "") or "").strip("<>|")
        return result.text.strip()

    def process(self, samples: np.ndarray) -> Event:
        duration = len(samples) / SAMPLE_RATE
        embedding = self._embed(samples)
        # 一秒以内的句子声纹不可靠：同人常掉到 0.3 出头，异人也可能碰巧冒高。
        short = duration < 1.2
        speaker, score = self.prints.judge(embedding, extra=0.06 if short else 0.0)
        self._asr_event = ""
        self._asr_lang = ""
        text = self._asr(samples) if speaker != "other" else ""
        noise = self._asr_event.strip("<>|") in self.NOISE_EVENTS
        if noise:
            log.info("这段是%s，不是人说话，不记", self._asr_event)
            text = ""
        return Event(
            ts=time.time(),
            speaker=speaker,
            score=score,
            text=text,
            duration=duration,
            accepted=(speaker == self.prints.OWNER) and not noise,
            lang=self._asr_lang,
            noise=noise,
        )

    async def process_async(self, samples: np.ndarray) -> Event:
        # 串行化：2 核机器上并发推理只会互相拖慢
        async with self._lock:
            return await asyncio.get_running_loop().run_in_executor(None, self.process, samples)


# ---------------------------------------------------------------- 服务


class Hub:
    """App 侧的订阅者。板子和 App 用两条独立的 TCP，互不牵连。"""

    def __init__(self) -> None:
        self._clients: set[asyncio.StreamWriter] = set()
        self.backlog: list[str] = []

    def add(self, writer: asyncio.StreamWriter) -> None:
        self._clients.add(writer)
        if self.backlog:
            for line in self.backlog[-20:]:
                writer.write((line + "\n").encode())

    def drop(self, writer: asyncio.StreamWriter) -> None:
        self._clients.discard(writer)

    def publish_line(self, line: str) -> None:
        """发一条不进回放队列的实时状态（板子在不在线）。"""
        for w in list(self._clients):
            try:
                w.write((line + "\n").encode())
            except Exception:
                self._clients.discard(w)

    def publish(self, event: Event) -> None:
        line = event.to_json()
        self.backlog.append(line)
        if len(self.backlog) > 200:
            self.backlog = self.backlog[-200:]
        for w in list(self._clients):
            try:
                w.write((line + "\n").encode())
            except Exception:
                self._clients.discard(w)
        log.info("事件 %s", line)


class Server:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.models: Models | None = None
        self.prints: Voiceprints | None = None
        self.pipeline: Pipeline | None = None
        self.hub = Hub()
        # 记录时段存成 json，App 改一次就落盘，重启不丢
        self.window_path = Path(args.voiceprints).parent / "window.json"
        self.window = self._load_window()
        self._in_window = None  # 上次算出来的时段状态，只在变化时打日志
        self.enroll_from_board_until = 0.0  # > now 表示正等着用板子录声纹
        self.board_writer = None            # 板子那条 TCP，用来给它下发 WiFi 配置

    # -- 记录时段
    def _load_window(self) -> tuple[str, str]:
        try:
            data = json.loads(self.window_path.read_text())
            return str(data.get("start", "00:00")), str(data.get("end", "00:00"))
        except Exception:
            return "00:00", "00:00"

    def _save_window(self) -> None:
        self.window_path.parent.mkdir(parents=True, exist_ok=True)
        self.window_path.write_text(
            json.dumps({"start": self.window[0], "end": self.window[1]}, ensure_ascii=False)
        )

    @staticmethod
    def _minutes(hhmm: str) -> int | None:
        try:
            h, m = hhmm.split(":")
            h, m = int(h), int(m)
        except Exception:
            return None
        if not (0 <= h < 24 and 0 <= m < 60):
            return None
        return h * 60 + m

    def in_window(self, now: datetime | None = None) -> bool:
        """开始==结束 表示全天；start>end 表示跨零点（22:00→07:00）。"""
        start = self._minutes(self.window[0])
        end = self._minutes(self.window[1])
        if start is None or end is None or start == end:
            return True
        t = (now or datetime.now())
        minutes = t.hour * 60 + t.minute
        return start <= minutes < end if start < end else (minutes >= start or minutes < end)

    def _check_window(self) -> None:
        """状态变了才打日志，免得每片音频刷一行。"""
        now_in = self.in_window()
        if now_in != self._in_window:
            self._in_window = now_in
            log.info(
                "记录时段 %s-%s：%s",
                self.window[0], self.window[1],
                "开始了，只记这段" if now_in else "到点了，先不记",
            )

    def _status(self) -> dict:
        assert self.prints
        return {
            "type": "status",
            "enrolled": self.prints.enrolled,
            "window": {"start": self.window[0], "end": self.window[1]},
            "in_window": self.in_window(),
            "threshold": self.prints.threshold,
        }

    def _to_board(self, obj: dict) -> bool:
        """把一行 JSON 塞给板子；板子在收，收不到就当没发过。"""
        w = self.board_writer
        if w is None or w.is_closing():
            return False
        try:
            w.write((json.dumps(obj, ensure_ascii=False) + "\n").encode())
            return True
        except Exception:
            self.board_writer = None
            return False

    def _sound(self, event: Event) -> None:
        """让挂坠出声。判定就发生在这里，所以它和手机收到事件是同一刻。

        板子不在线就什么都不做：手机那边知道自己响（App 知道板子在不在）。
        噪声（BGM/笑声/咳嗽）不算「不是你」，不出声——那不是拒绝，是没听见人话。
        """
        if event.accepted:
            name = "tick"
        elif event.speaker == "other" and not event.noise:
            name = "hush"
        else:
            return
        sent = self._to_board({"cmd": "sound", "name": name})
        log.info("声语 %s → 板子%s", name, "已下发" if sent else "没连上，没发出去")

    def _control(self, cmd: dict) -> dict:
        """App 在 19002 上发一行 JSON，云回一行。"""
        name = str(cmd.get("cmd", ""))
        if name == "window":
            start = str(cmd.get("start", self.window[0]))
            end = str(cmd.get("end", self.window[1]))
            if self._minutes(start) is None or self._minutes(end) is None:
                return {"type": "ack", "ok": False, "message": "时间要写成 20:00 这样"}
            self.window = (start, end)
            self._save_window()
            self._in_window = None
            log.info("App 把记录时段改成 %s-%s", start, end)
            return {"type": "ack", "ok": True, "message": "时段改了", **self._status()}
        if name == "wifi":
            ssid = str(cmd.get("ssid") or "").strip()
            pswd = str(cmd.get("pswd") or "").strip()
            if not ssid:
                return {"type": "ack", "ok": False, "message": "WiFi 名不能空着"}
            if not self._to_board({"cmd": "wifi", "ssid": ssid, "pswd": pswd}):
                return {"type": "ack", "ok": False,
                        "message": "板子没连着云端——先让它连上，或者用「配网模式」"}
            log.info("App 让板子换成 WiFi「%s」", ssid)
            return {"type": "ack", "ok": True, "message": "发给板子了，它会重连「" + ssid + "」"}

        if name == "reprovision":
            # 清掉板子上的 WiFi，重启后进配网热点
            if not self._to_board({"cmd": "reprovision"}):
                return {"type": "ack", "ok": False, "message": "板子没连着云端，没法让它重新配网"}
            log.info("App 让板子清掉 WiFi、进配网模式")
            return {"type": "ack", "ok": True, "message": "板子会重启并开一个 Diting-Setup 热点"}

        if name == "wipe":
            assert self.prints
            self.prints.wipe()
            log.info("App 删掉了已录声纹")
            return {"type": "ack", "ok": True, "message": "声纹删了", **self._status()}
        if name == "enroll_board":
            assert self.prints
            self.enroll_from_board_until = time.time() + 30
            log.info("App 要用板子的麦克风录声纹（30 秒内有效）")
            return {"type": "ack", "ok": True,
                    "message": "好，现在对着板子说 8 秒，说完等一下"}
        if name == "sound":
            which = str(cmd.get("name") or "tick")
            if which not in ("tick", "hush"):
                which = "tick"
            if not self._to_board({"cmd": "sound", "name": which}):
                return {"type": "ack", "ok": False, "message": "板子没连着云端"}
            log.info("App 试听声语 %s", which)
            return {"type": "ack", "ok": True, "message": "响了一声：" + which}
        if name == "status":
            return {"type": "ack", "ok": True, **self._status()}
        return {"type": "ack", "ok": False, "message": "不认识的命令"}

    # -- 板子：PCM 进来
    async def handle_board(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        peer = writer.get_extra_info("peername")
        log.info("板子接入 %s", peer)
        pending = b""
        vad = None          # 真的来音频才建 VAD：纯日志通道不该占这份内存
        is_audio = False
        try:
            while True:
                chunk = await reader.read(4096)
                if not chunk:
                    break
                pending += chunk
                while len(pending) >= 4:
                    (n,) = struct.unpack("<I", pending[:4])
                    if n in (0x7FFFFFFF, 0x7FFFFFFE):
                        # 0x7FFFFFFF 诊断行 / 0x7FFFFFFE 日志通道：暗号 + 4 字节文本长度 + UTF-8
                        if len(pending) < 8:
                            break
                        (tlen,) = struct.unpack("<I", pending[4:8])
                        if tlen <= 0 or tlen > 4096:
                            log.warning("坏诊断长度 %s，断连", tlen)
                            return
                        if len(pending) < 8 + tlen:
                            break
                        text = pending[8 : 8 + tlen].decode("utf-8", "replace")
                        pending = pending[8 + tlen :]
                        if n == 0x7FFFFFFF:
                            log.info("板子日记：%s", text)
                        else:
                            for line in text.splitlines():
                                if line.strip():
                                    log.info("板上日志：%s", line)
                        continue
                    if n <= 0 or n > MAX_FRAME:
                        log.warning("坏帧长度 %s，断连", n)
                        return
                    if len(pending) < 4 + n:
                        break
                    pcm = pending[4 : 4 + n]
                    pending = pending[4 + n :]
                    if not is_audio:
                        is_audio = True
                        self.board_writer = writer
                        self.hub.publish_line(json.dumps({"type": "board", "online": True}))
                    if vad is None:
                        vad = self._new_vad()
                    await self._feed(vad, pcm)
            if vad is not None:
                vad.flush()
                await self._drain(vad)
        except (ConnectionResetError, asyncio.IncompleteReadError):
            pass
        finally:
            if self.board_writer is writer:
                self.board_writer = None
                self.hub.publish_line(json.dumps({"type": "board", "online": False}))
            log.info("板子断开 %s", peer)
            writer.close()

    def _new_vad(self):
        assert self.models
        return self.models.new_vad()

    async def _feed(self, vad, pcm: bytes) -> None:
        assert self.models
        # 16bit 小端 → float32 [-1,1]
        samples = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0
        win = self.models.vad_window
        for i in range(0, len(samples) - win + 1, win):
            vad.accept_waveform(samples[i : i + win])
        await self._drain(vad)

    async def _drain(self, vad) -> None:
        self._check_window()
        if not self._in_window:
            # 时段外：把切出来的段扔掉，不跑声纹也不跑识别，省 CPU
            while not vad.empty():
                vad.pop()
            return
        while not vad.empty():
            seg = vad.front
            # 必须先取样本再 pop：front 返回的是引用，任何后续方法调用都会让它失效
            samples = np.array(seg.samples, dtype=np.float32)
            vad.pop()
            if len(samples) < SAMPLE_RATE:  # 短于 1 秒：声纹判不准，ASR 也没用，直接当噪声
                continue
            if self.enroll_from_board_until > time.time():
                # 用板子自己的麦克风录：同一支麦克风，同人分数会明显高于手机录的那版
                assert self.pipeline and self.prints
                emb = self.pipeline._embed(samples)
                if self.prints.enrolled:
                    score = self.prints.score(emb)
                    self.prints.enroll(emb)
                    msg = f"用板子记住了你的声音（和上一版相似度 {score:.2f}）"
                else:
                    self.prints.enroll(emb)
                    msg = "用板子记住了你的声音"
                self.enroll_from_board_until = 0.0
                log.info(msg)
                self.hub.publish_line(json.dumps(
                    {"type": "notice", "text": msg}, ensure_ascii=False))
                continue
            assert self.pipeline
            event = await self.pipeline.process_async(samples)
            self.hub.publish(event)
            self._sound(event)

    # -- 注册声纹：App 把一段自己的声音推上来（同样的帧格式，推完就关）
    async def handle_enroll(self, reader, writer) -> None:
        peer = writer.get_extra_info("peername")
        log.info("注册请求 %s", peer)
        pcm = bytearray()
        pending = b""
        try:
            while True:
                chunk = await reader.read(4096)
                if not chunk:
                    break
                pending += chunk
                while len(pending) >= 4:
                    (n,) = struct.unpack("<I", pending[:4])
                    if n == 0x7FFFFFFF:
                        # 板子的诊断行：暗号 + 4 字节文本长度 + UTF-8
                        if len(pending) < 8:
                            break
                        (tlen,) = struct.unpack("<I", pending[4:8])
                        if tlen <= 0 or tlen > 4096:
                            log.warning("坏诊断长度 %s，断连", tlen)
                            return
                        if len(pending) < 8 + tlen:
                            break
                        text = pending[8 : 8 + tlen].decode("utf-8", "replace")
                        pending = pending[8 + tlen :]
                        log.info("板子日记：%s", text)
                        continue
                    if n <= 0 or n > MAX_FRAME:
                        log.warning("坏帧长度 %s，断连", n)
                        return
                    if len(pending) < 4 + n:
                        break
                    pcm += pending[4 : 4 + n]
                    pending = pending[4 + n :]
        except (ConnectionResetError, asyncio.IncompleteReadError):
            return

        samples = np.frombuffer(bytes(pcm), dtype="<i2").astype(np.float32) / 32768.0
        seconds = len(samples) / SAMPLE_RATE
        reply: dict = {"type": "enroll", "seconds": round(seconds, 2)}
        speech = self._speech_only(samples)
        if len(speech) < SAMPLE_RATE * 3 // 2 and len(samples) > len(speech):
            # VAD 只挑出不到 1.5 秒：多半是手机麦克风小、或者人离得远。
            # 拿这么短的片段去定一个人的声音太冒险，退而取整段里最响的 3 秒。
            speech = self._loudest_window(samples, 3.0)
        if len(speech) < SAMPLE_RATE // 2:
            log.info("注册结果 %s", reply)
            reply.update(ok=False, kind="too_short", message="没听清，再说长一点")
        else:
            assert self.pipeline and self.prints
            emb = self.pipeline._embed(speech)
            if self.prints.enrolled:
                score = self.prints.score(emb)
                reply["score"] = round(score, 3)
                if score < 0.5:
                    reply.update(ok=False, kind="mismatch",
                                 message="这段和已注册的声音不像，没有覆盖")
                else:
                    self.prints.enroll(emb)      # 追加一条模板，不覆盖旧的
                    reply.update(ok=True, kind="updated", message="又记了一遍你的声音")
            else:
                self.prints.enroll(emb)
                reply.update(ok=True, kind="enrolled", message="记住了")
            reply["speech_seconds"] = round(len(speech) / SAMPLE_RATE, 2)
            log.info("注册结果 %s", reply)

        try:
            writer.write(json.dumps(reply, ensure_ascii=False).encode() + b"\n")
            await writer.drain()
        except (ConnectionResetError, BrokenPipeError):
            pass
        finally:
            writer.close()

    @staticmethod
    def _loudest_window(samples: np.ndarray, seconds: float) -> np.ndarray:
        """整段里能量最高的连续 seconds 秒。VAD 挑不出东西时用它兜底。"""
        want = int(seconds * SAMPLE_RATE)
        if len(samples) <= want:
            return samples
        hop = SAMPLE_RATE // 50                      # 20ms 一格
        frames = len(samples) // hop
        energy = np.array([float(np.mean(np.square(samples[i * hop:(i + 1) * hop])))
                           for i in range(frames)])
        step = max(1, want // hop)
        if frames <= step:
            return samples
        sums = np.convolve(energy, np.ones(step), mode="valid")
        start = int(np.argmax(sums)) * hop
        return samples[start:start + want]

    def _speech_only(self, samples: np.ndarray) -> np.ndarray:
        """把静音切掉再取声纹——静音段会把平均向量往原点拉，同一人相似度会掉。"""
        vad = self._new_vad()
        win = self.models.vad_window if self.models else 512
        for i in range(0, len(samples) - win + 1, win):
            vad.accept_waveform(samples[i : i + win])
        vad.flush()
        parts = []
        while not vad.empty():
            seg = vad.front
            parts.append(np.array(seg.samples, dtype=np.float32))
            vad.pop()
        if not parts:
            return samples
        return np.concatenate(parts)

    # -- App：事件出去
    async def handle_app(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        peer = writer.get_extra_info("peername")
        log.info("App 接入 %s", peer)
        self.hub.add(writer)
        try:
            while True:
                line = await reader.readline()
                if not line:
                    break
                try:
                    cmd = json.loads(line.decode("utf-8", "replace").strip())
                except Exception:
                    continue
                reply = self._control(cmd if isinstance(cmd, dict) else {})
                writer.write((json.dumps(reply, ensure_ascii=False) + "\n").encode())
                await writer.drain()
        except (ConnectionResetError, asyncio.IncompleteReadError):
            pass
        finally:
            self.hub.drop(writer)
            writer.close()

    async def start(self) -> None:
        a = self.args
        self.models = Models.load(Path(a.models), with_asr=not a.no_asr, threads=a.threads)
        self.prints = Voiceprints(Path(a.voiceprints), self.models.speaker.dim, a.threshold)
        self.pipeline = Pipeline(self.models, self.prints)

        board = await asyncio.start_server(self.handle_board, a.bind, a.board_port)
        app = await asyncio.start_server(self.handle_app, a.bind, a.app_port)
        enroll = await asyncio.start_server(self.handle_enroll, a.bind, a.enroll_port)
        log.info(
            "听板子在 %s:%s，听 App 在 %s:%s，听注册在 %s:%s",
            a.bind, a.board_port, a.bind, a.app_port, a.bind, a.enroll_port,
        )
        log.info("声纹阈值 %.2f，已注册：%s", a.threshold, self.prints.enrolled)
        async with board, app, enroll:
            await asyncio.gather(
                board.serve_forever(), app.serve_forever(), enroll.serve_forever()
            )


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="谛听云端")
    p.add_argument("--models", default="models")
    p.add_argument("--voiceprints", default="data/voiceprints.json")
    p.add_argument("--bind", default="0.0.0.0")
    p.add_argument("--board-port", type=int, default=19001)
    p.add_argument("--app-port", type=int, default=19002)
    p.add_argument("--enroll-port", type=int, default=19003)
    p.add_argument("--threshold", type=float, default=0.48)  # 同人短句实测 0.48-0.73，陌生人 0.0-0.32
    p.add_argument("--threads", type=int, default=int(os.environ.get("DITING_THREADS", "1")))
    p.add_argument("--no-asr", action="store_true", help="只跑 VAD+声纹，不加载 ASR")
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        datefmt="%H:%M:%S",
    )
    args = parse_args(argv)
    try:
        asyncio.run(Server(args).start())
    except KeyboardInterrupt:
        log.info("停止")


if __name__ == "__main__":
    main()
