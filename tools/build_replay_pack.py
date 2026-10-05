#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把官方归档的一场会话，压成「回放包」——下发格式与线上 SignalR **逐字节同构**。

## 为什么需要它

真机上的崩溃只在实际比赛数据下复现，而一年只有二十几场；崩了还得再等一周。
官方**归档**里每场比赛的每条流都是完整存着的，把各条流按时间戳归并，就能
重建出一模一样的下发序列 —— 于是"等实际比赛"变成"随时重放今天这场"。

## 线上客户端看到什么

    1) 一条快照   {"type":3,"result":{"RaceControlMessages":{...}, ...}}
    2) 一串增量   {"type":1,"target":"TimingData","arguments":[{...}]}
    记录之间用 0x1e (RS) 分隔。

## 归档长什么样

每个流一个 *.jsonStream 文件，**每行**是 `<会话偏移>{该流的一次增量}`：

    00:12:14.518{"Messages":[{"Utc":"2026-10-04T06:19:14",...}]}

注意 TrackStatus / RaceControlMessages 这类流，**中间态不能合并掉** ——
旗语状态机和消息列表要靠每一条增量推进。所以本脚本输出的是一条**时间线**，
不是一份"最终状态"。只有快照那一条是合并出来的。

## 用法

    python tools/build_replay_pack.py                    # 默认巴林 2026 正赛
    python tools/build_replay_pack.py \
        --session "2026/2026-09-26_Azerbaijan_Grand_Prix/2026-09-26_Race/" \
        --id baku2026_race --name "2026 阿塞拜疆站 正赛" --date 2026-09-26

## 产物落在哪

    tools/mock_data/<id>.rclog    就这一个文件

★ 这一个文件**同时**是两件事：

    1. 桌面单测的夹具（run_tests.py 用它跑完整管线）
    2. 挂在 GitHub Release 上的示例数据，用户下载后在 App 里选它来回放

  所以只留一份、只产出一次，不往 APK 里塞。
  （v3.1.x 把两个包打进了 APK，体积从 85 KB 涨到 845 KB，
   其中 751 KB 全是示例数据 —— v3.2.0 起 App 不再内置任何示例数据。）

## .rclog 是什么

一行元信息 + 一行一帧的完整时间线，整体 gzip：

    RCLOG1 {"name":"...","date":"...","snapshotMs":740000,...}
    00:12:20.000{"type":3,"result":{...}}          <- 完整快照
    00:12:21.000{"type":1,"target":"...",...}      <- 增量，同一时刻的多条用 0x1e 分隔
    ...

第一行是**可选的**（没有它也能放，只是 App 显示不出这是什么比赛）。
下发格式与线上 WebSocket 逐字节同构，所以 App 里解析和渲染走的是
和实时完全同一份代码。

★ `.rclog` 只用于**赛后读归档**，不做实时录制 —— 它是导出/导入格式，
  不是录像格式。

## ★ 归档服务器必须直连

本机环境变量里常有 HTTP(S)_PROXY，而 F1 的 CDN 会把代理出口 403 掉，
所以下面强制绕开代理。另外归档在赛后还会继续吐两三个小时的数据，
默认不清，`--snapshot-ms` 只决定"从哪一刻接上"。
"""
import argparse
import gzip
import json
import os
import re
import sys
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# 从脚本位置推仓库根，别写死绝对路径 —— 别人克隆下来也能跑。
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
MOCK_DIR = os.path.join(HERE, "mock_data")

# .rclog 的魔数与版本。App 靠它认出"这是回放文件"并取元信息。
MAGIC = "RCLOG1"

BASE = "https://livetiming.formula1.com/static"

# ★ 必须和 F1Feed.STREAMS 一致：多抓没用，少抓就是 bug（TimingStats 那次翻车）。
STREAMS = [
    "RaceControlMessages", "TrackStatus", "SessionStatus", "SessionInfo",
    "LapCount", "DriverList", "TimingData", "TimingAppData",
    "ExtrapolatedClock", "WeatherData", "TimingStats", "TopThree",
]

# 抽稀：这几条流每秒好几条，全留会让包大十倍；按毫秒窗口合并（0 = 一条不留地全留）
THIN_MS = {
    "TimingData": 6000,
    "TimingStats": 12000,
    "TopThree": 12000,
    "TimingAppData": 8000,
    "DriverList": 4000,
    "WeatherData": 10000,
    "LapCount": 2000,
}

# 快照覆盖到哪一刻（这段的增量全部合并成 type:3 快照，之后才走 type:1）
#
# ★ 这个参数决定"模拟什么场景"：
#     - 比赛刚开始时打开 App  → 快照很小，之后慢慢长起来
#     - 比赛中途打开 App      → 快照一次就是**完整体**（327 条消息 + 22 车全字段）
#   真机上那次闪退是后者，所以两种都要能造出来。
SNAPSHOT_MS = 740000

RS = "\x1e"
LINE_RE = re.compile(r"^(\d{2}):(\d{2}):(\d{2})\.(\d{3})(.*)$")

OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
UA = {"User-Agent": "HA-F1-RaceControl"}


# 归档不会变，缓存到本地 —— 重建一次要拉 30 MB。不进版本库（见 .gitignore）。
CACHE = os.path.join(HERE, ".archive-cache")


def fetch(path):
    """直连归档。★ 本机环境变量里有 HTTP(S)_PROXY，而 F1 的 CDN 会 403 掉代理出口。

    归档不会变，缓存到本地 —— 重建一次要拉 30 MB，不缓存太亏。
    """
    key = path.replace("/", "__")
    hit = os.path.join(CACHE, key)
    if os.path.exists(hit) and os.path.getsize(hit) > 0:
        with open(hit, "rb") as fh:
            return fh.read().decode("utf-8-sig", "replace")
    url = BASE + "/" + path
    req = urllib.request.Request(url, headers=UA)
    with OPENER.open(req, timeout=90) as r:
        raw = r.read()
    os.makedirs(CACHE, exist_ok=True)
    with open(hit, "wb") as fh:
        fh.write(raw)
    return raw.decode("utf-8-sig", "replace")


def parse_ms(ts):
    m = LINE_RE.match(ts)
    if not m:
        return None
    h, mi, s, ms = (int(m.group(i)) for i in (1, 2, 3, 4))
    return ((h * 60 + mi) * 60 + s) * 1000 + ms


def parse_stream(text):
    """→ [(ms, obj), ...]，忽略解析不了的行（归档里没有，但防一手）。"""
    out = []
    for raw in text.split("\n"):
        raw = raw.strip()
        if not raw:
            continue
        t = parse_ms(raw[:12])
        if t is None:
            continue
        body = raw[12:].strip()
        if not body.startswith("{"):
            continue
        try:
            out.append((t, json.loads(body)))
        except ValueError:
            continue
    return out


def fmt_ms(t):
    h = t // 3600000
    mi = (t // 60000) % 60
    s = (t // 1000) % 60
    ms = t % 1000
    return "%02d:%02d:%02d.%03d" % (h, mi, s, ms)


def deep_merge(dst, src):
    for k, v in src.items():
        if isinstance(v, dict) and isinstance(dst.get(k), dict):
            deep_merge(dst[k], v)
        else:
            dst[k] = v


def merge_into(dst, src, stream):
    """RaceControlMessages 是"累积"，不是"覆盖" —— 单独处理。"""
    if stream == "RaceControlMessages" and "Messages" in src:
        acc = dst.setdefault("Messages", [])
        # 归档里 Messages 有时是 list、有时是 dict（键是行号），两种都吃
        msgs = src["Messages"]
        if isinstance(msgs, dict):
            msgs = list(msgs.values())
        for m in msgs:
            if m not in acc:
                acc.append(m)
        rest = dict((k, v) for k, v in src.items() if k != "Messages")
        deep_merge(dst, rest)
    else:
        deep_merge(dst, src)


def thin(events, window):
    """把 events 按 window 毫秒分桶、桶内深合并，取桶首时间戳。"""
    if not window or window <= 0:
        return events
    out = []
    cur_bucket = None
    cur_obj = None
    cur_t = None
    for t, obj in events:
        b = t // window
        if b != cur_bucket:
            if cur_obj is not None:
                out.append((cur_t, cur_obj))
            cur_bucket = b
            cur_obj = {}
            cur_t = t
        deep_merge(cur_obj, obj)
    if cur_obj is not None:
        out.append((cur_t, cur_obj))
    return out


def build(session_path, pack_id, name, date, snapshot_ms, extra=None):
    print("会话:", session_path, " 快照点:", fmt_ms(snapshot_ms))
    raw = {}
    for s in STREAMS:
        try:
            txt = fetch(session_path + s + ".jsonStream")
        except Exception as e:
            print("  跳过 %-22s %s" % (s, type(e).__name__))
            continue
        ev = parse_stream(txt)
        print("  %-22s %7d KB → %6d 条增量" % (s, len(txt) // 1024, len(ev)))
        if ev:
            raw[s] = ev

    if not raw:
        raise SystemExit("一条流都没抓到")

    # ---- 1) 快照：把 snapshot_ms 之前的增量合并起来 ----
    snapshot = {}
    timeline = []
    for s, ev in raw.items():
        snap = {}
        for t, obj in ev:
            if t <= snapshot_ms:
                merge_into(snap, obj, s)
            else:
                timeline.append((t, s, obj))
        if snap:
            snapshot[s] = snap

    msgs = snapshot.get("RaceControlMessages", {}).get("Messages", [])
    print("  快照内含 %d 条赛事控制消息" % len(msgs))

    # ---- 2) 抽稀 ----
    by_stream = {}
    for t, s, obj in timeline:
        by_stream.setdefault(s, []).append((t, obj))
    timeline = []
    for s, ev in by_stream.items():
        kept = thin(ev, THIN_MS.get(s, 0))
        print("  %-22s 抽稀 %6d → %6d" % (s, len(ev), len(kept)))
        for t, obj in kept:
            timeline.append((t, s, obj))
    timeline.sort(key=lambda x: x[0])

    # ---- 3) 归并成帧 ----
    # ★ 快照那条标的是**真实**会话偏移，不是 0 —— 回放要按它算"从哪一刻开始播"。
    lines = [fmt_ms(snapshot_ms) + json.dumps(
        {"type": 3, "result": snapshot}, ensure_ascii=False, separators=(",", ":"))]
    i = 0
    n = len(timeline)
    while i < n:
        t = timeline[i][0]
        recs = []
        while i < n and timeline[i][0] == t:
            recs.append(json.dumps(
                {"type": 1, "target": timeline[i][1],
                 "arguments": [timeline[i][2]]},
                ensure_ascii=False, separators=(",", ":")))
            i += 1
        lines.append(fmt_ms(t) + RS.join(recs))

    body = "\n".join(lines) + "\n"

    last = timeline[-1][0] if timeline else snapshot_ms
    meta = {
        "id": pack_id,
        "name": name,
        "date": date,
        "session": session_path,
        "snapshotMs": snapshot_ms,
        "startMs": snapshot_ms,
        "endMs": last,
        "frames": len(lines),
        "snapshotMsgs": len(msgs),
        "durationMs": last - snapshot_ms,
    }
    if extra:
        meta.update(extra)

    # 元信息头单独一行；App 会跳过它，同时用它显示"这是什么比赛"。
    # ★ 不带头的时间线也能放（老文件），所以它是**可选**的。
    text = MAGIC + " " + json.dumps(meta, ensure_ascii=False,
                                    separators=(",", ":")) + "\n" + body
    raw = text.encode("utf-8")
    gz = gzip.compress(raw, 9)

    os.makedirs(MOCK_DIR, exist_ok=True)
    out_name = pack_id + ".rclog"
    out_path = os.path.join(MOCK_DIR, out_name)
    with open(out_path, "wb") as fh:
        fh.write(gz)

    meta["file"] = out_name
    meta["bytes"] = len(gz)
    meta["rawBytes"] = len(raw)

    print("\n%s  %.1f KB 原始 → %.1f KB gzip；%d 帧；可播 %s → %s（%s）"
          % (out_name, len(raw) / 1024.0, len(gz) / 1024.0, len(lines),
             fmt_ms(snapshot_ms), fmt_ms(last), fmt_ms(last - snapshot_ms)))
    print("落在: %s" % out_path)
    return meta


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--session", default="2026/2026-10-04_Bahrain_Grand_Prix/2026-10-04_Race/")
    ap.add_argument("--id", default="bahrain2026_race")
    ap.add_argument("--name", default="2026 巴林站 正赛 · 从头看")
    ap.add_argument("--date", default="2026-10-04")
    ap.add_argument("--snapshot-ms", type=int, default=SNAPSHOT_MS)
    ap.add_argument("--note", default="")
    args = ap.parse_args()

    extra = {"note": args.note} if args.note else None
    build(args.session, args.id, args.name, args.date, args.snapshot_ms, extra)
    print()
    print("这个文件同时是单测夹具和 Release 示例数据，不用再拷到别处。")


if __name__ == "__main__":
    main()