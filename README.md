# 谛听

[![Android CI](https://github.com/RUI-debug769/diting/actions/workflows/android-build.yml/badge.svg)](https://github.com/RUI-debug769/diting/actions/workflows/android-build.yml)
[![Release](https://img.shields.io/github/v/release/RUI-debug769/diting)](https://github.com/RUI-debug769/diting/releases/latest)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

一个只听你的记言器。

一块没有屏幕的挂坠挂在身上。它听得见周围所有人，但只把你自己的话记进你的账本。一天结束，它把这一天压成三栏：**事实、待办、需你留意**。原文一个字不改，收在下面，七天后自动清掉。

> 状态：2026-11-06，比赛版本（v0.1）。板子 → 云 → 手机的全链路已经真机跑通：主人说一句，左栏落一条（声纹 `0.661`，3.17 秒），旁人说不进账本，点「让它写」三栏出判断。没做完的地方都写在「已知局限」里，不在别处美化。

| 主屏 | 设置 |
| --- | --- |
| ![主屏](docs/screens/main-summary.png) | ![设置](docs/screens/settings.png) |

## 装到手机上

不想自己编译，直接拿现成的包：[Releases](https://github.com/RUI-debug769/diting/releases/latest) → `diting.apk`（v0.1，`com.diting.app`，`minSdk 26` / `targetSdk 34`，约 215 KB）。

1. 在手机上允许「安装未知来源应用」。
2. 装好打开，进设置页填云端地址（默认 `112.124.111.67:19002`）和 DeepSeek 的 Key。
3. 小米 / 红米报 `INSTALL_FAILED_USER_RESTRICTED` 时，改用 `adb install -r -i com.android.vending diting.apk`。

三点说明：包是 `debug.keystore` 签的（私钥不入库，CI 每次现生成），只适合装在自己机器上试用，正式分发给别人要换 release 签名；APK 不是提交进仓库的，由 GitHub Actions 跑 `android/build_apk.sh` 出包并挂到 Release；每次出包都会校验签名和版本号。

## 它不是什么

**不是录音笔。** 它不存音频，没有回放，也没有「昨天下午那段声音」可以翻。落盘的只有文字。

**不是聊天助手。** 它不回答问题、不查天气、不主动找你说话。你说，它记；它压成三栏，你可以不看。

**不是一个把大模型包了一层的套壳。** 左栏的字来自语音识别器（SenseVoice），不经过任何大模型，错字也原样留着。三栏才是大模型写的。这一点可以当场验证：把密钥删掉再点「让它写」，左栏照样有字，三栏退回规则兜底。

**不是端侧隐私。** 原始 PCM 会离开板子，走公网到我们自己的服务器。它在内存里被切句、比对声纹、转成文字，然后丢掉——不写盘、不留档。但「声音去了自己的服务器」和「声音没出门」是两句话，这里只说前一句。

**不是成品。** 两天半做出来的东西：能演示，不耐用。固件要外接供电、要连热点；云端是一台机器，没有冗余。

## 它是怎么连起来的

```
挂坠（T5AI-CORE） --原始 PCM--> 云（自建 ECS） --一句话的 JSON--> 手机
                                  切句 · 认人 · 转字              三栏 · 七天
```

```mermaid
flowchart LR
    FW["挂坠固件<br/>采音 16k/16bit/单声道<br/>TCP 推流"] -->|"PCM，每 40ms 一片"| CL["云端<br/>Silero VAD · CAM++ 声纹 · SenseVoice"]
    CL -->|"accepted 的一句话"| APP["手机 App<br/>左栏：原话"]
    APP -->|"当天原话文本"| LM["DeepSeek<br/>/chat/completions"]
    LM -->|"三栏 JSON"| APP
```

三层各管一件事：

| 层 | 只做 | 不做 |
| --- | --- | --- |
| 固件 | 采音、推流、断线重连、20 秒没推出去就自己重启 | 不识别、不存、不判断 |
| 云端 | 切句、认人、转字，只把「主人说的话」推给手机 | 不渲染、不写三栏 |
| 手机 | 收话、存文字、按天归档、请求三栏 | 不碰声音 |

链路细节：板子每 40 ms 推一片 16k/16bit/单声道的 PCM，帧格式是 4 字节小端长度加数据，目标 `112.124.111.67:19001`（固件里写死）。云端用 Silero VAD 切成整句，CAM++ 声纹跟已注册的模板比（阈值 `0.48`，最多 3 个模板取最高分），SenseVoice int8 转字；BGM、掌声、笑声这类非语音事件直接丢掉。只有 `accepted` 的话才推到手机的 `19002` 端口。

手机不做流式渲染：一句进来就存一句，按天落盘。点「让它写」（或到设置里那个自动整理时间）时，把当天原话一次性交给 DeepSeek，要它输出三栏 JSON。

## 三分钟跑起来

### 你需要

- JDK 17
- Android SDK：`build-tools/34.0.0` 和 `platforms/android-34`
- 一台安卓手机，系统 8.0 以上（`minSdk 26`）。目前只在一台小米上装过。
- 要跑全链路：T5AI-CORE 板子、能烧录的电脑、TuyaOpen、一台跑 `cloud/` 的服务器。

### A. 只跑 App（没有板子也能看界面）

Release 里已经有包的话，这一节可以跳过；下面是从源码出包。

```bash
git clone https://github.com/RUI-debug769/diting.git
cd diting/android
printf 'sdk.dir=%s\n' "<你的 Android SDK 路径>" > local.properties
export JAVA_HOME="<你的 JDK 17 路径>"
export ANDROID_HOME="<你的 Android SDK 路径>"
./build_apk.sh
adb install -r build/diting.apk
```

`build_apk.sh` 只依赖 `android.jar` 和 build-tools，不拉 AndroidX；仓库里留着 Gradle 脚手架，这一版没有用它出过包。

小米、红米这类系统如果报 `INSTALL_FAILED_USER_RESTRICTED`，指定一个安装器身份就能过：

```bash
adb install -r -i com.android.vending build/diting.apk
```

装好之后：打开 → 设置里填云端地址（默认 `112.124.111.67:19002`）和 DeepSeek 的 Key。没有板子时，左栏会一直空着——这一版没有「说一句」按钮，声音只从挂坠来。

### B. 接上板子（全链路）

1. 手机开热点，名字改成 `Murdo`，密码填固件里编译进去的那个。
2. 云端：`cloud/deploy.sh`，装依赖、下载模型、起 `diting` 服务；`cloud/tools/enroll_push.py` 注册声纹。
3. 烧固件：`cd ~/tuya-ws/TuyaOpen/apps/diting && ../../.venv/bin/python ../../tos.py flash -p /dev/ttyACM0 -b 921600`（刷完板子自动重启）。
4. 板子上电，连上热点。App 底部出现「挂坠在线，正在听」。
5. 对着板子说话，左栏出现原话；让旁人说话，左栏不动。

`firmware/` 里是预编译的烧录包（含 2026-11-06 的 select 死等修复和残留配网旗子修复），源码在 TuyaOpen 工作区。

### C. 只看提示词

三栏的全部来源是 `android/app/src/main/java/com/diting/app/Lm.java` 里的 `SUMMARY_SYSTEM` 常量。把它和一条记录拼起来，贴给任何一个对话模型，你能看到它是怎么判断的，也包括它判断错的时候。

## 一条记录长什么样

手机上按天存一份，键是 `yyyy-MM-dd`：

```json
{
  "events": [
    {"mine": "记一下，明天下午三点和张老师开会。", "denied": false},
    {"mine": "今天下午去图书馆还书", "denied": false}
  ],
  "summary": {
    "facts": ["开饭时间早上9点至下午5点"],
    "todos": ["明天下午3点和张老师开会", "今天下午去图书馆还书"],
    "watch": ["开饭时间反复说了好几遍"]
  }
}
```

云端推给手机的一行 JSON 长这样：

```json
{"type": "utterance", "speaker": "me", "score": 0.661,
 "text": "记一下明天下午3点和张老师开会。", "duration": 3.17, "accepted": true}
```

三条规矩：

- 不存音频。任何字段里都不许出现音频路径、base64、二进制。
- `mine` 不做清洗。要修错字就另开字段，不要覆盖它。
- 七天前的天整份删掉，`summary` 一起删。

`schema/record.md` 是更早的一版逐条 JSONL 设计（给导出用的），App 现在按天存，字段含义不变。

## 已知局限

**一、声纹只有一个模板时，阈值只能折中。** 主人 0.66 过，但背景音乐也被转成过「歌词」拿到 0.51–0.59。现在的办法是按 SenseVoice 的声学事件（BGM、掌声、笑声、哭、咳、喷嚏）先丢一轮，再比声纹；这挡得住音乐，挡不住一个嗓音接近的人。

**二、方言和口音取决于识别模型。** SenseVoice 对普通话好，方言看运气。我们没有做任何方言处理，也没有词表。

**三、板子不是可穿戴成品。** 它要连热点、要外接供电，外壳是手工挖的孔。演示时挂脖子上可以，日常戴一整天不行。

**四、云端是一台机器。** 没有冗余、没有队列，进程挂了链路就断。看门狗能让板子自己重连，但云不在了它也连不上。

**五、三栏的质量看模型当天的状态。** 提示词写得硬（最多几条、每条多长、宁可空不要凑），但同一个模型不同时候的判断差别不小。我们没有做过系统评测，也没有横向换模型比过。

**六、七天之后是真的删。** 没有导出、没有备份，删了找不回来。这是设计（忘记是功能），但也是风险。

**七、只在一台机器上试过。** 小米 HyperOS，机型代号 `25080RABDG`。换别的手机、别的系统，后台限制、权限弹窗都可能不一样。

**八、固件只开源了一半。** 仓库里是能直接烧的 bin，源码还在 TuyaOpen 的工作区，没并进来。你能验证它烧出来的行为，还不能自己改它。

## 隐私

**存了什么。** 手机上只有文字：每天的原话、三栏判断、以及「有没有被否定」的标记。七天。

**发出去了什么。** 两段：一是板子推给云端的原始 PCM（切句、认人、转字用，内存里处理完就丢，不写盘）；二是点「让它写」时，手机把当天原话文本发给 DeepSeek。请求里没有音频、没有通讯录、没有位置、没有设备号。

**没控制住的。** 两条。一是云端那一跳：PCM 走公网明文 TCP 到 `112.124.111.67`，链路上能抓包的人能听到原始声音——我们没有做传输加密。二是密钥：DeepSeek 的 Key 明文存在 App 私有目录里，手机被 root 或被拿走就不安全。

**删数据。** 设置 → 记录 → 清空今天的记录；或者等七天。原文和三栏一起删，不留副本。

**立场。** 一个整天听你说话的设备必须开源，否则没人有理由信任它。开源不是为了让人读着放心，是为了让人能查：编译它、改它、看它到底发了什么。这也是为什么许可是 Apache-2.0。

## 仓库结构

```
diting/
├── README.md              你在这里
├── LICENSE                Apache-2.0
├── NOTICE
├── .github/workflows/     CI：跑 build_apk.sh 出包，tag 时发 Release
├── android/               安卓 App（Java，不依赖 AndroidX 的出包脚本）
│   ├── build_apk.sh
│   ├── gradle             脚手架（这一版没用来出包）
│   └── app/src/main/java/com/diting/app/
│       ├── MainActivity.java     主屏：三栏卡片 + 折叠原话
│       ├── SettingsActivity.java 时段、声纹、记录、WiFi、云端与模型
│       ├── Store.java            按天存档（events + summary），七天清理
│       ├── Lm.java               三栏提示词、请求、规则兜底
│       ├── Cloud.java            收云端推来的一句话
│       ├── NetRx.java            收板子的 PCM、算 RMS（备用通道）
│       ├── Enroll.java           声纹注册
│       ├── AutoWrite*.java       每天 19:39 自动整理
│       └── DayView.java          旧的两栏画布（已不挂到主屏）
├── cloud/                 云端：VAD + 声纹 + 识别（sherpa-onnx）
├── firmware/              预编译烧录包（源码待并）
├── hardware/shell/        外壳参数化建模，见 hardware/shell/README.md（STL/STEP 可再生成，不入库）
├── schema/                早期记录格式与示例
├── prompts/               重写提示词原文与实验笔记
├── docs/                  设计、硬件、演示、问答、截图
└── out/                   更早的演示截图
```

## 许可

Apache-2.0，全文见 [LICENSE](LICENSE)。

可以商用，可以闭源改，要求保留版权声明和 `NOTICE`，附带一条专利授权。选它不选 GPL：这个项目希望被人拿回家改、装在自己家里，传染性许可会挡住一部分人；要防的只是「抹掉出处」，署名条款够用。

## 如何贡献

最需要四样东西：

1. 传输加密。PCM 现在是明文 TCP，这是隐私那一节最软的一条。
2. 更强的声纹。多模板、现场标阈值，宁可漏记，不可错记。
3. 方言词表。一个词一条：怎么写、怎么读、普通话怎么说、谁教的、哪里的话。
4. 外壳。现在是手工挖的孔。

提 PR 的话：一个 PR 一件事；新增依赖要说清楚为什么，尤其是要联网的；不要提交真实录音、真实日记、`debug.keystore`、`local.properties`、模型权重。

## 待核实

1. 换一台非小米的手机，后台收流能撑多久。
2. 板子连续推流能撑多久不断——目前最长只跑过半小时。
3. 三栏提示词在别的模型上是什么表现，没有横向比过。
4. 方言可用度：没有样本，说不清，别在会上给百分比。
