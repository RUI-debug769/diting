# 谛听 · 云端（可选）

一台可选的中间站：接板子推上来的 PCM，做 VAD 切片、声纹闸门、语音识别，再把结果交给 App。

```
板子 --TCP 19001--> [VAD 切片] --> [声纹闸门] --> [SenseVoice 识别] --TCP 19002--> App
```

不需要声纹时，整个 `cloud/` 可以完全不跑——App 的转文字走手机系统听写。

## 它只做什么

- 收 PCM：16 kHz / 16 bit / 单声道，每帧 `4 字节小端长度 + 数据`，和 `NetRx.java`、固件一致。
- 用 silero-VAD 切片，用 CAM++ 判定是不是「我」的声音，不是就丢。
- 是「我」的片段交给 SenseVoice 转文字，按行推给 App。
- 不重写、不存音频到磁盘、不落日记。

## 依赖

```bash
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
```

`requirements.txt` 只有两项：`sherpa-onnx>=1.12`、`numpy>=1.26`。模型是外部的，不进仓库，见 [models/README.md](models/README.md)。

## 跑起来

```bash
python diting_cloud.py                       # 默认 --models models，端口 19001/19002/19003
python diting_cloud.py --no-asr              # 只跑 VAD + 声纹，不加载识别模型
python diting_cloud.py --threshold 0.48      # 声纹阈值
```

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `--models` | `models` | 模型目录 |
| `--voiceprints` | `data/voiceprints.json` | 声纹库 |
| `--bind` | `0.0.0.0` | 监听地址 |
| `--board-port` | `19001` | 板子推流入口 |
| `--app-port` | `19002` | App 收结果入口 |
| `--enroll-port` | `19003` | 声纹注册入口 |
| `--threshold` | `0.48` | 同人短句实测 0.48–0.73，陌生人 0.0–0.32 |
| `--threads` | `$DITING_THREADS` 或 1 | ONNX 线程数 |

## 声纹注册

```bash
python enroll.py me my-1.wav my-2.wav my-3.wav --models models
```

三段同一个人、不同时间的录音，各自算一条 192 维向量再取平均，写进 `data/voiceprints.json`。
脚本会打印三段之间的两两相似度：**彼此到不了 0.6 就不要注册**——那样平均值卡 0.62，主人自己也会被拒。

阈值的原则是宁可漏记，不可错记。

## 部署

`deploy.sh` 用 rsync 把代码同步到一台 Linux 主机，并安装 systemd 服务。目标主机、端口、密钥全部走环境变量，仓库里不放真实地址：

```bash
HOST=<你的服务器> USER_=<用户名> KEY=<你的私钥路径> ./deploy.sh
```

主机上需要有 `python3` + `pip3`（`deploy.sh` 直接用系统解释器，依赖进 dist-packages），以及 `/opt/diting` 的写权限。服务定义见 [deploy/diting.service](deploy/diting.service)，内存上限 1200M，日志在 `/var/log/diting.log`。

## 隐私

- 只在你显式部署第三方中转时才会用到这条链路；请求发生在你的板子、你的服务器、你的手机之间。
- 服务不保存音频文件：PCM 只存在于内存里的切片缓冲区。
- 声纹库 `data/voiceprints.json` 是向量，不是录音。
- 这个目录不会被 App 的默认配置指过来，`Store.java` 里的默认地址是空的，需要你自己填。
