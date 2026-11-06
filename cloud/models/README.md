# 模型文件（不随仓库分发）

`cloud/` 需要四个模型文件，全部来自 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 生态，放在 `cloud/models/` 下：

| 文件 | 用途 | 大小 | 来源 |
| --- | --- | --- | --- |
| `silero_vad.onnx` | 语音活动检测，切片 | ~0.6 MB | [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases)（`silero_vad.onnx`） |
| `3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx` | 声纹向量（CAM++ 中文） | ~27 MB | 同上，3D-Speaker CAM++ 中文模型 |
| `model.int8.onnx` | SenseVoice 语音识别（int8） | ~229 MB | 同上 / ModelScope 的 SenseVoiceSmall int8 导出 |
| `tokens.txt` | 识别器词表，必须与 `model.int8.onnx` 同批 | ~0.3 MB | 与上者同一发布包 |

`zh.wav` / `en.wav` / `ja.wav` 是三个测试音频，不随仓库分发。

校验方式：缺文件时服务会在启动阶段直接退出并打印缺失路径，不会静默降级（`--no-asr` 除外）。

```bash
python diting_cloud.py --models models
# 缺模型时：SystemExit("声纹模型缺失：models/3dspeaker_...onnx")
```

模型各自的许可跟随上游发布（silero-VAD 为 MIT，SenseVoice 与 3D-Speaker 见其仓库），不适用本仓库的 Apache-2.0。

> 为什么不在仓库里：`model.int8.onnx` 单个 229 MB，超过 GitHub 单文件 100 MB 限制；即使走 LFS，克隆成本也会让这个仓库失去"能随手 clone 下来读"的意义。
