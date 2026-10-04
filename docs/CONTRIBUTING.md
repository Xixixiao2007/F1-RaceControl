# 构建、测试与发版

面向**改这个仓库的人**。只想装 App 的话看 [使用说明](USAGE.md)。

---

## 构建

整套构建不需要 Gradle / Android Studio / JDK 17，一次完整构建约 10 秒。

```bash
python tools/fetch_sdk.py              # 首次：下载最小 Android 工具集（约 236 MB）
python tools/run_tests.py              # 桌面单测（153 项）
python tools/build_apk.py              # 构建 APK
python tools/run_all_tests.py          # 全套测试（单元 + mock 自测 + 端到端 + 发布工具），共 7 项
python tools/try_feel.py               # 真机手感测试台：按键就往手机推一段剧本
```

## 发版

```bash
python tools/release.py                               # 只检查（构建 + 过两道版本号闸门）
python tools/release.py --publish --notes <说明.md>    # 检查通过后一路发到底
python tools/release.py --push-only                   # 发完版又补了文档：只推引用，不重发
```

**发版前必须改 `app/AndroidManifest.xml` 的 `versionCode`（每次 +1）和
`versionName`（patch 位）。** 不改的话 `release.py` 会直接拒绝 —— 有两道闸：
本地（源码变了但 versionCode 没变）、远端（该版本号已发布过、资产内容又不同）。

补文档用 `--push-only`。**别在这种情况下再跑 `--publish`**：APK 不是逐字节
可复现的，它会重建重传，Release 说明里写死的 SHA256 当场作废。

> 为什么要有这两道闸：v2.0.0 期间连着重建了 4 次、每次覆盖同一个 Release 资产，
> 结果 **4 个不同的 APK 全叫 `2.0.0`**（versionCode 都是 1）。
> 用户分不出自己装的是哪一个，先下载的人手里还是旧文件。
> 一道是纪律，一道是兜底。

## 怎么在没有比赛的时候测

比赛两周才有一次。`tools/mock_ha.py` 是个本地的假 Home Assistant
（REST + WebSocket），可以**回放 697 条真实比赛消息**，也能跑剧本：
`red_flag` / `safety_car` / `vsc` / `test_double_yellow` / `penalty` /
`race_start` / `burst`。

```bash
python tools/mock_ha.py --scenario vsc
```

把手机上的地址改成这台电脑的 IP，就能在真机上把闪动、分级、强提醒、确认、
重连全测一遍。

**剧本里的每条消息都是逐字取自真实数据的完整句子**，不是编的短句 ——
比如超赛道限制在真实数据里写的是
`CAR 44 (HAM) TIME 1:34.625 DELETED - TRACK LIMITS AT TURN 17 LAP 20 14:17:09`，
而不是 `TRACK LIMITS AT TURN 4`。用假短句测，测的就不是分类、过滤、翻译的真实输入。
唯一的例外是安全车那两条：那一整个周末只出了 VSC，没有真安全车。

`tools/try_feel.py` 是给真机手感用的：自动探测局域网 IP、起假 HA、把手机该填的
三项打在屏幕上，然后按数字键就往手机推一段剧本。开局停在「没比赛」状态，
不会一上来就用历史回填把屏幕刷满。

## 目录

### App 源码 `app/src/com/haf1/racecontrol/`

**A 方案 —— 主线（v3.0.0 起：直连 F1 官方公开流，不带任何令牌）**

```
F1Client.java         直连官方公开流：negotiate -> WebSocket 握手 -> 订阅 -> 自动重连
F1Feed.java           把增量流深合并成状态（纯逻辑，可单测）
F1Layout.java         界面几何计算：圆环均布 / 轮胎面板分格 / 旗语栏分段（纯逻辑）
F1MainActivity.java   主界面：横屏左右分栏 + 顶部多旗语栏 + 告警接线
RightPanelView.java   右侧面板 6 屏：赛道图 / 轮胎进站 / 成绩 / 天气 / 最快圈 / 环节
TopFlagBarView.java   顶部旗语栏：多旗语并存时纵向分隔，每段列自己的区段
```

**状态机、解析与翻译（两条数据链共用）**

```
RaceMessage.java      一条消息（含全部属性）
Classifier.java       旗语分类（纯函数，可单测）
AlertGate.java        聚类 / 升级 / 冷却 —— 降噪的核心
TrackState.java       赛道状态机（优先级；含官方 TrackStatus 状态码 1/2/4/5/6/7/8）
MessageStore.java     去重 / 容量 / 落盘
Translator.java       消息的中文简述（纯函数，可单测）
WsFrame.java          RFC 6455 帧编解码（含分片重组 readMessage）
```

**提醒与设置**

```
Notifier.java         声音 + 震动 + 通知栏
AlertActivity.java    强提醒的全屏横幅
CheckerDrawable.java  格子旗的棋盘格背景
SettingsActivity.java 设置：数据源 / 显示与过滤 / 提醒 / 试听 / 关于
Prefs.java            设置项的读写、钳制与过滤规则
```

**旧 HA 版（代码保留作为兜底，但已不是启动入口）**

```
MainActivity.java     旧主界面（连 Home Assistant）
HaClient.java         HA REST（历史接口）
HaWebSocket.java      HA WebSocket 实时推送
```

### 工具 `tools/`

```
build_apk.py          构建：javac -> d8 -> aapt2 -> apksigner（不走 Gradle）
release.py            发版：构建 -> 打标签 -> 推 main -> 建/更新 Release
                      -> 上传 -> 匿名下载复验 SHA256
run_all_tests.py      全套：单测 + 假 HA 自测 + 端到端 + 发布工具自测
run_tests.py          桌面单测（对着 android-23 的 android.jar 编译）
run_e2e.py            端到端：真实客户端代码 <=> 假 HA
test_mock_ha.py       假 HA 自测
test_release_meta.py  发布工具自测
mock_ha.py            本地假 HA（REST + WebSocket），回放真实数据 / 跑剧本
try_feel.py           真机手感测试台
fetch_sdk.py          下载 Android 工具集（约 236 MB）
make_icon.py          生成图标
```

### 测试与数据 `tools/tztest/`、`tools/mock_data/`

```
tztest/src/           桌面单测（TzTest.java）
tztest/stub/          android.* / org.json 的极简桩，让纯逻辑能在桌面上跑
tztest/e2e/           IntegrationTest（对假 HA）、GlossDump（翻译诊断）
tztest/smoke/         F1Smoke（真连官方流）、F1PipelineCheck（完整模拟 App 数据管线）
mock_data/racecontrol_history.tsv            697 条真实比赛消息（HA 录制）
mock_data/racecontrol_history_0924_0926.tsv  另一个比赛周末的消息
mock_data/f1_snapshot_real.json              官方归档真实快照
                                             （某站正赛：327 条消息 / 22 位车手）
```

`mock_data/f1_snapshot_real.json` 是**官方归档的真实形状**，不是手写的 ——
`Stints` 那个 bug（真实数据里是数组、代码按对象解析）就是手写夹具发现不了的，
只有真数据能戳破。由 `dsh/tools/build_f1_fixture.py` 生成。
