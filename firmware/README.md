# 固件（预编译）

跑在涂鸦 **T5AI-CORE** 板上的一个 TuyaOpen app：采音、把 PCM 推给手机、用一颗单色灯表示状态。

> ⚠️ 这里只有**烧录产物**，没有源码。源码还在 TuyaOpen 工作区里（`apps/diting/src/diting_main.c`），没有并进这个仓库——所以"固件也开源"目前只兑现了一半。见根 README「已知局限」第九条。

## 文件

| 文件 | 大小 | MD5 | 说明 |
| --- | --- | --- | --- |
| `diting_QIO_0.1.0.bin` | 2.46 MB | `fd4ce91e8352baab3322c996e3ab6bdb` | 当前构建（2026-11-06 11:55），**以这个为准** |
| `diting_UA_0.1.0.bin` | 2.20 MB | `6e12be3bd08f26fa8dd64404320d45fd` | 同一份固件的 UA 打包变体 |
| `diting_UG_0.1.0.bin` | 1.24 MB | `2cc8c1efafb05a315211a8283045e06e` | 同一份固件的 UG 打包变体 |

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
