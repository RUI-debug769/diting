# 固件（预编译）

跑在涂鸦 **T5AI-CORE** 板上的一个 TuyaOpen app：采音、把 PCM 推给手机、用一颗单色灯表示状态。

> ⚠️ 这里只有**烧录产物**，没有源码。源码还在 TuyaOpen 工作区里（`apps/diting/src/diting_main.c`），没有并进这个仓库——所以"固件也开源"目前只兑现了一半。见根 README「已知局限」第九条。

## 文件

| 文件 | 大小 | MD5 | 说明 |
| --- | --- | --- | --- |
| `diting_QIO_0.1.0.bin` | 2.46 MB | `0752ed86974dc51476d3a99c72dcd13c` | 当前构建（2026-11-07 11:23，按 `TUYA_T5AI_CORE` 编译，声语直推 PCM + 打开 DAC），**以这个为准** |
| `diting_UA_0.1.0.bin` | 2.26 MB | `718857c0f532cd3267ef1166f2b2b210` | 同一份固件的 UA 打包变体 |
| `diting_UG_0.1.0.bin` | 1.24 MB | `16f63845f0ebb21b01b4d7064628ab49` | 同一份固件的 UG 打包变体 |

三个文件是同一份代码的三种打包方式，功能一致；烧录用 QIO。旧构建 `diting_QIO_0.1.0_1105_2100.bin` 只在本地保留，不进仓库。

## 它做什么

```
T5AI 麦克风 → ai_audio_input（手动 VAD，一直开着）→ 40ms 一片
   → 队列（最多 50 片，约 2 秒）→ 发送线程 → TCP <手机/服务器>:19001
```

帧格式和两个接收端完全一致：**4 字节小端长度 + PCM 16 kHz / 16 bit / 单声道**。

- 不识别、不存声纹、不落盘。
- 断了自动重连（退避 2 秒），队列满了丢最旧的片，绝不阻塞麦克风线程。
- 网络只走你配置的那一个地址，不访问公网其它地方。
- 出声（2026-11-07 起）：云端判定「记下 / 不是你」的那一刻顺着同一条 TCP 发 `{"cmd":"sound","name":"tick"}`，板子用 16 kHz 内存合成的两声回应——不带头部资源，也没有人声。板子在线时手机不再重复响。
- 播放走 `tdl_audio_play` 直推 PCM16，不经过 AI 播放器服务与 WAV 解码器。出声前会显式 `tdl_audio_volume_set(70)` 把 DAC 打开——codec 注册时 `play_volume` 是 80，但这条路不经过 AI 播放器，显式设一次才能保证 unmute。每次收到、每次播放都会通过 `__diag` 回一行（服务器日志里的「板子日记」），心跳里也带 `voice=`（codec 句柄）、`pcm=`（合成是否就绪）、`vol=`（DAC 是否已打开）。

## 板子与喇叭

按 `TUYA_T5AI_CORE` 板级编译（功放使能在 **GPIO39**）。**板上没有喇叭**：只有一颗 1W 功放和一个 JST PH 1.25 mm 的喇叭座，要外接 4Ω 1–3W 的喇叭才听得到。外壳 `hardware/shell/params.py` 里 `HAS_SPEAKER = False`，没有出声孔。

## 烧录前必须改的四个值

固件里现在填的是编译时的值，换环境要重新编译。源码顶部（`apps/diting/src/diting_main.c`）：

```c
#define DITING_WIFI_SSID "your-ssid"
#define DITING_WIFI_PSWD "your-pswd"
#define DITING_CLOUD_IP   "<你的手机地址或服务器地址>"
#define DITING_CLOUD_PORT 19001
```

WiFi 填错时板子会一直连不上，日志里 `网络断了` 反复出现。

## 烧录

需要 [TuyaOpen](https://github.com/tuya/TuyaOpen) 工作区：

```bash
cd ~/tuya-ws/TuyaOpen/apps/diting
uv run --project ~/tuya-ws/TuyaOpen python ~/tuya-ws/TuyaOpen/tos.py build
uv run --project ~/tuya-ws/TuyaOpen python ~/tuya-ws/TuyaOpen/tos.py flash -p /dev/ttyACM0 -b 921600
```

必须在 app 目录里跑；在 TuyaOpen 仓库根跑会报 `TuyaOpen root cannot be regarded as project root.`

## 灯语

一颗单色灯，用亮灭的节律表示六件事：待机、在听、听到别人、被否定、夜间书写、断连。

灯只有一种颜色，这不是省事。七色灯是音箱和路由器的语言，一颗单色的灯在呼吸，才像活的东西。灯由固件驱动，App 反向控制那一段还没写——详见 `docs/挂坠固件-推流.md`。
