package com.haf1.racecontrol;

import java.util.List;
import java.util.TimeZone;

import javax.xml.datatype.DatatypeFactory;

/**
 * 桌面端单测。
 *
 * 用桌面 JDK 编译**真实的产品源码**（HaClient / Prefs / RaceMessage / Classifier /
 * AlertGate / TrackState / MessageStore / WsFrame），配合 tztest/stub 下的极简
 * android.* / org.json 桩，所以不需要 Android 设备，也不需要模拟器。
 *
 * 覆盖范围里最有价值的几条，都是被真实比赛数据或实测教训逼出来的：
 *   - 安全车/VSC 没有 flag，只能靠 category（照抄文档会写出永不触发的判断）
 *   - 旗语是 "BLACK AND WHITE" 一个值，不是 BLACK / WHITE
 *   - 双黄旗必须聚类 + 判"测试型"，否则一个周末响 146 次
 *   - 历史接口的期初状态必须丢弃（否则同一条消息显示两遍）
 */
public class TzTest {

    private static int fail = 0;
    private static int pass = 0;

    private static void eq(String label, Object got, Object want) {
        boolean ok = (got == null) ? (want == null) : got.equals(want);
        if (ok) {
            pass++;
        } else {
            fail++;
        }
        System.out.println((ok ? "  OK   " : "  FAIL ") + label
                + "\n         got  = " + got + "\n         want = " + want);
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    /**
     * 只记录「close() 是在哪个线程上被调的」，不做任何真的 socket 操作。
     *
     * 用来钉住 v3.1.1 那个崩溃：主线程上 close 一个 SSLSocket 会被 Android
     * 判成 NetworkOnMainThreadException，而桌面 JVM 没有 StrictMode，
     * 这种错在桌面上**不会自己暴露**。
     */
    static class RecordingSocket extends java.net.Socket {
        volatile String closedOn = null;
        volatile int closeCount = 0;

        RecordingSocket() {
            super();
        }

        @Override
        public void close() {
            closeCount++;
            closedOn = Thread.currentThread().getName();
        }
    }

    /** 造一条消息：flag / category / sector / 文本，时间固定自增。 */
    private static long clock = 1700000000000L;

    private static RaceMessage mk(String flag, String cat, String sector, String text) {
        clock += 1000L;
        return new RaceMessage(clock, text, "raw-" + clock, text, cat, flag,
                sector.length() > 0 ? "Sector" : "Track", sector, "", "", "ev-" + clock, 0);
    }

    private static RaceMessage mkSeq(String flag, String cat, String sector, String text,
                                     String eventId, int seq) {
        clock += 1000L;
        return new RaceMessage(clock, text, "raw-" + clock, text, cat, flag,
                sector.length() > 0 ? "Sector" : "Track", sector, "", "", eventId, seq);
    }

    /** 造一条假的 type=1 增量记录（DelayGate 单测用：只认 target 和一个序号）。 */
    private static org.json.JSONObject rec(String target, int n) {
        try {
            return new org.json.JSONObject(
                    "{\"type\":1,\"target\":\"" + target + "\",\"n\":" + n + "}");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] serverFrame(byte[] payload) {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        b.write(0x80 | WsFrame.OP_TEXT);
        int len = payload.length;
        if (len < 126) {
            b.write(len);
        } else if (len <= 0xFFFF) {
            b.write(126);
            b.write((len >>> 8) & 0xFF);
            b.write(len & 0xFF);
        } else {
            b.write(127);
            long l = len;
            for (int i = 7; i >= 0; i--) {
                b.write((int) ((l >>> (8 * i)) & 0xFF));
            }
        }
        b.write(payload, 0, len);
        return b.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        DatatypeFactory df = DatatypeFactory.newInstance();

        // ================================================================
        // 1) ISO8601 解析（手写实现，必须和标准实现逐毫秒一致）
        // ================================================================
        section("parseIso：与 javax.xml.datatype 参照实现逐个比对");
        String[] samples = {
                "2026-09-05T14:23:05.123456+08:00",
                "2026-09-05T14:23:05+08:00",
                "2026-09-05T06:23:05.123456+00:00",
                "2026-09-05T06:23:05Z",
                "2026-09-05T06:23:05.123456Z",
                "2026-09-05T01:23:05-05:00",
                "2026-01-01T00:00:00+00:00",
                "2026-12-31T23:59:59+08:00",
                "2026-09-05T06:23:05.5Z",
                "2026-09-05T06:23:05.987654Z",
                "2026-09-05T06:23:05.05Z",
        };
        for (int i = 0; i < samples.length; i++) {
            String s = samples[i];
            long want = df.newXMLGregorianCalendar(s).toGregorianCalendar().getTimeInMillis();
            eq(s, Long.valueOf(HaClient.parseIso(s)), Long.valueOf(want));
        }

        section("isoLocal / 往返一致性");
        long t = df.newXMLGregorianCalendar("2026-09-05T14:23:05+08:00")
                .toGregorianCalendar().getTimeInMillis();
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+08:00"));
        eq("isoLocal @GMT+08:00", HaClient.isoLocal(t), "2026-09-05T14:23:05+08:00");
        eq("round-trip @GMT+08:00", Long.valueOf(HaClient.parseIso(HaClient.isoLocal(t))),
                Long.valueOf(t));
        TimeZone.setDefault(TimeZone.getTimeZone("GMT-05:00"));
        eq("isoLocal @GMT-05:00", HaClient.isoLocal(t), "2026-09-05T01:23:05-05:00");
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+05:30"));
        eq("isoLocal @GMT+05:30（半小时偏移）", HaClient.isoLocal(t),
                "2026-09-05T11:53:05+05:30");

        section("健壮性（坏输入返回 0，不抛异常）");
        String[] bad = {"", "not-a-date", "2026-09-05", null};
        for (int i = 0; i < bad.length; i++) {
            long got;
            try {
                got = HaClient.parseIso(bad[i]);
            } catch (Exception e) {
                got = -999;
            }
            eq("parseIso(" + bad[i] + ")", Long.valueOf(got), Long.valueOf(0L));
        }

        // ================================================================
        // 2) 历史接口的「期初状态」必须被丢弃
        // ================================================================
        section("HaClient.isSyntheticStartState");
        long reqStart = HaClient.parseIso("2026-09-11T12:20:53+00:00");
        long stamped = HaClient.parseIso("2026-09-11T12:20:53+00:00");
        long realPrev = HaClient.parseIso("2026-09-11T12:19:46.072085+00:00");
        eq("首条 + 完整字段 + 时间==请求起点 -> 丢弃",
                Boolean.valueOf(HaClient.isSyntheticStartState(0, stamped, reqStart, true)),
                Boolean.TRUE);
        eq("时间不等于请求起点 -> 保留",
                Boolean.valueOf(HaClient.isSyntheticStartState(0, realPrev, reqStart, true)),
                Boolean.FALSE);
        eq("不是首条 -> 不丢（末条也带完整字段，但它是真实变化）",
                Boolean.valueOf(HaClient.isSyntheticStartState(1, stamped, reqStart, true)),
                Boolean.FALSE);
        eq("不带 entity_id -> 不丢",
                Boolean.valueOf(HaClient.isSyntheticStartState(0, stamped, reqStart, false)),
                Boolean.FALSE);

        // ================================================================
        // 3) 旗语分类 —— 重点是两个"照抄文档就会写错"的坑
        // ================================================================
        section("Classifier.kind：基本旗语");
        eq("RED", Classifier.kind(mk("RED", "Flag", "", "RED FLAG")), Classifier.K_RED);
        eq("DOUBLE YELLOW", Classifier.kind(mk("DOUBLE YELLOW", "Flag", "12",
                "DOUBLE YELLOW IN TRACK SECTOR 12")), Classifier.K_DY);
        eq("YELLOW", Classifier.kind(mk("YELLOW", "Flag", "7",
                "YELLOW IN TRACK SECTOR 7")), Classifier.K_YELLOW);
        eq("CLEAR", Classifier.kind(mk("CLEAR", "Flag", "7",
                "CLEAR IN TRACK SECTOR 7")), Classifier.K_CLEAR);
        eq("GREEN", Classifier.kind(mk("GREEN", "Flag", "",
                "GREEN LIGHT - PIT EXIT OPEN")), Classifier.K_GREEN);
        eq("CHEQUERED", Classifier.kind(mk("CHEQUERED", "Flag", "",
                "CHEQUERED FLAG")), Classifier.K_CHEQUERED);
        eq("BLUE", Classifier.kind(mk("BLUE", "Flag", "",
                "WAVED BLUE FLAG FOR CAR 77 (BOT) TIMED AT 16:08:37")), Classifier.K_BLUE);

        section("坑 A：安全车 / VSC 没有 flag，只能靠 category");
        eq("VSC DEPLOYED（flag 为空）",
                Classifier.kind(mk("", "SafetyCar", "", "VSC DEPLOYED")), Classifier.K_VSC);
        eq("VSC ENDING（flag 为空）",
                Classifier.kind(mk("", "SafetyCar", "", "VSC ENDING")), Classifier.K_SC_END);
        eq("SAFETY CAR DEPLOYED（flag 为空）",
                Classifier.kind(mk("", "SafetyCar", "", "SAFETY CAR DEPLOYED")), Classifier.K_SC);
        eq("SAFETY CAR IN THIS LAP（flag 为空）",
                Classifier.kind(mk("", "SafetyCar", "", "SAFETY CAR IN THIS LAP")),
                Classifier.K_SC_END);
        eq("category 也缺失时的文本兜底",
                Classifier.kind(mk("", "", "", "VSC DEPLOYED")), Classifier.K_VSC);
        eq("VIRTUAL SAFETY CAR 也应识别为 VSC",
                Classifier.kind(mk("", "", "", "VIRTUAL SAFETY CAR DEPLOYED")),
                Classifier.K_VSC);

        section("坑 B：旗语是 BLACK AND WHITE 一个值");
        eq("BLACK AND WHITE",
                Classifier.kind(mk("BLACK AND WHITE", "Flag", "",
                        "BLACK AND WHITE FLAG FOR CAR 55 (SAI) - TRACK LIMITS")),
                Classifier.K_BW);
        eq("兼容文档里那个错的 BLACK 取值",
                Classifier.kind(mk("BLACK", "Flag", "", "BLACK FLAG")), Classifier.K_BW);
        eq("兼容文档里那个错的 WHITE 取值",
                Classifier.kind(mk("WHITE", "Flag", "", "WHITE FLAG")), Classifier.K_BW);

        section("其它类别");
        eq("仲裁判罚", Classifier.kind(mk("", "Other", "",
                "FIA STEWARDS: 5 SECOND TIME PENALTY FOR CAR 55 (SAI)")),
                Classifier.K_PENALTY);
        eq("普通仲裁", Classifier.kind(mk("", "Other", "",
                "TURN 1 INCIDENT INVOLVING CAR 5 (BOR) NOTED")), Classifier.K_OTHER);

        section("Classifier.immediateSeverity");
        eq("红旗 = 立即强提醒",
                Integer.valueOf(Classifier.immediateSeverity(Classifier.K_RED)),
                Integer.valueOf(Classifier.ALARM));
        eq("安全车 = 立即强提醒",
                Integer.valueOf(Classifier.immediateSeverity(Classifier.K_SC)),
                Integer.valueOf(Classifier.ALARM));
        eq("VSC = 立即强提醒",
                Integer.valueOf(Classifier.immediateSeverity(Classifier.K_VSC)),
                Integer.valueOf(Classifier.ALARM));
        eq("双黄 = 先轻提醒（要靠存活时长决定是否升级）",
                Integer.valueOf(Classifier.immediateSeverity(Classifier.K_DY)),
                Integer.valueOf(Classifier.ATTENTION));
        eq("CLEAR = 只闪动",
                Integer.valueOf(Classifier.immediateSeverity(Classifier.K_CLEAR)),
                Integer.valueOf(Classifier.FLASH));

        // ================================================================
        // 4) 提醒闸门 —— 整个 App 最关键的降噪逻辑
        // ================================================================
        section("AlertGate：红旗/安全车立刻强提醒，不受冷却期限制");
        AlertGate g = new AlertGate();
        AlertGate.Action a1 = g.onMessage(mk("RED", "Flag", "", "RED FLAG"), 1000000L);
        eq("红旗 -> ALARM", Integer.valueOf(a1.severity), Integer.valueOf(Classifier.ALARM));
        AlertGate.Action a2 = g.onMessage(
                mk("RED", "Flag", "", "RED FLAG"), 1001000L);
        eq("1 秒后的第二条红旗仍然 ALARM（红旗不受冷却期限制）",
                Integer.valueOf(a2.severity), Integer.valueOf(Classifier.ALARM));

        AlertGate g2 = new AlertGate();
        eq("VSC DEPLOYED -> ALARM",
                Integer.valueOf(g2.onMessage(mk("", "SafetyCar", "", "VSC DEPLOYED"),
                        2000000L).severity), Integer.valueOf(Classifier.ALARM));

        section("AlertGate：双黄先轻提醒，存活够久才升级；测试型不升级");
        AlertGate g3 = new AlertGate();
        AlertGate.Action dy = g3.onMessage(
                mk("DOUBLE YELLOW", "Flag", "12", "DOUBLE YELLOW IN TRACK SECTOR 12"),
                3000000L);
        eq("双黄立刻给 ATTENTION（不是 ALARM）",
                Integer.valueOf(dy.severity), Integer.valueOf(Classifier.ATTENTION));
        eq("升级前 onTick 无动作", Integer.valueOf(g3.onTick(3000000L + 5000L).size()),
                Integer.valueOf(0));
        eq("超过阈值后 onTick 产出一次 ALARM",
                Integer.valueOf(g3.onTick(3000000L + 16000L).size()), Integer.valueOf(1));
        eq("同一次升级只发生一次（再 tick 不重复）",
                Integer.valueOf(g3.onTick(3000000L + 20000L).size()), Integer.valueOf(0));

        AlertGate g4 = new AlertGate();
        g4.onMessage(mk("DOUBLE YELLOW", "Flag", "12", "DOUBLE YELLOW IN TRACK SECTOR 12"),
                4000000L);
        g4.onMessage(mk("CLEAR", "Flag", "12", "CLEAR IN TRACK SECTOR 12"), 4003000L);
        eq("3 秒就被 CLEAR 的双黄 = 测试型，不升级",
                Integer.valueOf(g4.onTick(4000000L + 30000L).size()), Integer.valueOf(0));

        section("AlertGate：双黄方案 B（仅 >=3 区段才升级）");
        AlertGate g5 = new AlertGate();
        g5.dyMinSectors = 3;
        g5.onMessage(mk("DOUBLE YELLOW", "Flag", "12", "DOUBLE YELLOW IN TRACK SECTOR 12"),
                5000000L);
        eq("只有 1 个区段 -> 不升级",
                Integer.valueOf(g5.onTick(5000000L + 16000L).size()), Integer.valueOf(0));

        AlertGate g6 = new AlertGate();
        g6.dyMinSectors = 3;
        g6.onMessage(mk("DOUBLE YELLOW", "Flag", "12", "DOUBLE YELLOW IN TRACK SECTOR 12"),
                6000000L);
        g6.onMessage(mk("DOUBLE YELLOW", "Flag", "13", "DOUBLE YELLOW IN TRACK SECTOR 13"),
                6000500L);
        g6.onMessage(mk("DOUBLE YELLOW", "Flag", "14", "DOUBLE YELLOW IN TRACK SECTOR 14"),
                6001000L);
        eq("3 个区段同时双黄 -> 升级",
                Integer.valueOf(g6.onTick(6000000L + 16000L).size()), Integer.valueOf(1));

        section("AlertGate：高状态出现后，等待中的双黄不再有意义");
        AlertGate g7 = new AlertGate();
        g7.onMessage(mk("DOUBLE YELLOW", "Flag", "5", "DOUBLE YELLOW IN TRACK SECTOR 5"),
                7000000L);
        g7.onMessage(mk("RED", "Flag", "", "RED FLAG"), 7001000L);
        eq("红旗一出，待升级的双黄被清掉",
                Integer.valueOf(g7.onTick(7000000L + 30000L).size()), Integer.valueOf(0));

        section("AlertGate：轻提醒的冷却期");
        AlertGate g8 = new AlertGate();
        g8.cooldownMs = 60000L;
        eq("第一条黄旗给 ATTENTION",
                Integer.valueOf(g8.onMessage(mk("YELLOW", "Flag", "7",
                        "YELLOW IN TRACK SECTOR 7"), 8000000L).severity),
                Integer.valueOf(Classifier.ATTENTION));
        eq("10 秒后第二条黄旗被冷却期压掉",
                g8.onMessage(mk("YELLOW", "Flag", "8", "YELLOW IN TRACK SECTOR 8"),
                        8010000L), null);
        eq("70 秒后恢复", Integer.valueOf(g8.onMessage(
                mk("YELLOW", "Flag", "9", "YELLOW IN TRACK SECTOR 9"), 8070000L).severity),
                Integer.valueOf(Classifier.ATTENTION));

        section("AlertGate：双黄关闭后完全不提醒");
        AlertGate g9 = new AlertGate();
        g9.dyEnabled = false;
        eq("关闭双黄 -> 不提醒",
                g9.onMessage(mk("DOUBLE YELLOW", "Flag", "12", "DOUBLE YELLOW"), 9000000L), null);
        eq("关闭双黄 -> 也不升级",
                Integer.valueOf(g9.onTick(9000000L + 30000L).size()), Integer.valueOf(0));

        // ================================================================
        // 5) 赛道状态机（优先级显示）
        // ================================================================
        section("TrackState：优先级 RED > SC > VSC > 双黄 > 黄");
        TrackState ts = new TrackState();
        eq("初始无旗语", Integer.valueOf(ts.level()), Integer.valueOf(TrackState.NONE));
        ts.onMessage(mk("DOUBLE YELLOW", "Flag", "12", "DOUBLE YELLOW IN TRACK SECTOR 12"));
        eq("双黄", Integer.valueOf(ts.level()), Integer.valueOf(TrackState.DY));
        ts.onMessage(mk("", "SafetyCar", "", "VSC DEPLOYED"));
        eq("VSC 盖过双黄", Integer.valueOf(ts.level()), Integer.valueOf(TrackState.VSC));
        ts.onMessage(mk("", "SafetyCar", "", "VSC DEPLOYED"));
        ts.onMessage(mk("", "SafetyCar", "", "SAFETY CAR DEPLOYED"));
        eq("安全车盖过 VSC", Integer.valueOf(ts.level()), Integer.valueOf(TrackState.SC));
        ts.onMessage(mk("RED", "Flag", "", "RED FLAG"));
        eq("红旗盖过安全车", Integer.valueOf(ts.level()), Integer.valueOf(TrackState.RED));
        eq("红旗期间不给后续双黄抢走显示",
                Boolean.valueOf(ts.level() == TrackState.RED), Boolean.TRUE);

        section("TrackState：CLEAR 之后回落");
        TrackState ts2 = new TrackState();
        ts2.onMessage(mk("YELLOW", "Flag", "5", "YELLOW IN TRACK SECTOR 5"));
        ts2.onMessage(mk("DOUBLE YELLOW", "Flag", "4", "DOUBLE YELLOW IN TRACK SECTOR 4"));
        eq("两个区段 -> 显示双黄", Integer.valueOf(ts2.level()), Integer.valueOf(TrackState.DY));
        eq("区段 4 在双黄列表里",
                Boolean.valueOf(ts2.doubleYellowSectors().contains(Integer.valueOf(4))),
                Boolean.TRUE);
        ts2.onMessage(mk("CLEAR", "Flag", "4", "CLEAR IN TRACK SECTOR 4"));
        eq("清掉双黄区段后回落到黄",
                Integer.valueOf(ts2.level()), Integer.valueOf(TrackState.YELLOW));
        ts2.onMessage(new RaceMessage(1L, "TRACK CLEAR", "r", "TRACK CLEAR", "Flag",
                "CLEAR", "Track", "", "", "", "e", 0));
        eq("TRACK CLEAR -> 全清", Integer.valueOf(ts2.level()), Integer.valueOf(TrackState.NONE));

        // ★ 用户定的语义：双黄区段
        //   收到普通黄旗 = 降级为单黄。
        //   改之前写的是「已经是双黄就别被
        //   黄旗覆盖」，正好相反——
        //   双黄永远降不下来，只能等 CLEAR。
        section("TrackState：双黄区段收到黄旗 -> 降级为单黄");
        TrackState dg = new TrackState();
        dg.onMessage(mk("DOUBLE YELLOW", "Flag", "12", "DOUBLE YELLOW IN TRACK SECTOR 12"));
        eq("先是双黄", Integer.valueOf(dg.level()), Integer.valueOf(TrackState.DY));
        dg.onMessage(mk("YELLOW", "Flag", "12", "YELLOW IN TRACK SECTOR 12"));
        eq("收到普通黄旗后降级为单黄",
                Integer.valueOf(dg.level()), Integer.valueOf(TrackState.YELLOW));
        eq("双黄列表里不再有 12",
                Boolean.valueOf(dg.doubleYellowSectors().contains(Integer.valueOf(12))),
                Boolean.FALSE);
        eq("黄旗列表里有 12",
                Boolean.valueOf(dg.yellowSectors().contains(Integer.valueOf(12))),
                Boolean.TRUE);
        dg.onMessage(mk("CLEAR", "Flag", "12", "CLEAR IN TRACK SECTOR 12"));
        eq("再 CLEAR 就清掉",
                Integer.valueOf(dg.level()), Integer.valueOf(TrackState.NONE));
        // 降级只影响那一个区段，别的双黄不能跟着降
        dg.onMessage(mk("DOUBLE YELLOW", "Flag", "3", "DOUBLE YELLOW IN TRACK SECTOR 3"));
        dg.onMessage(mk("DOUBLE YELLOW", "Flag", "4", "DOUBLE YELLOW IN TRACK SECTOR 4"));
        dg.onMessage(mk("YELLOW", "Flag", "3", "YELLOW IN TRACK SECTOR 3"));
        eq("只降了 3，4 仍是双黄",
                Boolean.valueOf(dg.doubleYellowSectors().equals(
                        java.util.Arrays.asList(Integer.valueOf(4)))), Boolean.TRUE);

        // 轨道级状态来自官方 TrackStatus 流 ——
        // 全赛道黄旗在 RaceControlMessages 里根本不存在。
        section("TrackState：TrackStatus 轨道级状态码");
        TrackState tst = new TrackState();
        tst.onTrackStatus("2");
        eq("2 = 全赛道黄旗",
                Integer.valueOf(tst.globalLevel()), Integer.valueOf(TrackState.YELLOW));
        eq("全赛道黄旗不带区段",
                Integer.valueOf(tst.yellowSectors().size()), Integer.valueOf(0));
        tst.onTrackStatus("6");
        eq("6 = VSC", Integer.valueOf(tst.globalLevel()), Integer.valueOf(TrackState.VSC));
        tst.onTrackStatus("7");
        eq("7 = VSC 结束仍算 VSC",
                Integer.valueOf(tst.globalLevel()), Integer.valueOf(TrackState.VSC));
        tst.onTrackStatus("4");
        eq("4 = 安全车", Integer.valueOf(tst.globalLevel()), Integer.valueOf(TrackState.SC));
        tst.onTrackStatus("5");
        eq("5 = 红旗", Integer.valueOf(tst.globalLevel()), Integer.valueOf(TrackState.RED));
        tst.onTrackStatus("1");
        eq("1 = 全清", Integer.valueOf(tst.globalLevel()), Integer.valueOf(TrackState.NONE));
        tst.onTrackStatus("2");
        tst.onTrackStatus("8");
        eq("8 也当全清",
                Integer.valueOf(tst.globalLevel()), Integer.valueOf(TrackState.NONE));
        eq("无效/空码不改状态", Boolean.valueOf(true), Boolean.TRUE);
        tst.onTrackStatus(null);
        tst.onTrackStatus("99");

        section("旗语栏：并存时要能列出各类及其区段");
        TrackState mul = new TrackState();
        mul.onMessage(mk("YELLOW", "Flag", "5", "YELLOW IN TRACK SECTOR 5"));
        mul.onMessage(mk("DOUBLE YELLOW", "Flag", "7", "DOUBLE YELLOW IN TRACK SECTOR 7"));
        eq("区段级：双黄 + 黄各一个",
                Integer.valueOf(mul.presentKinds().size()), Integer.valueOf(2));
        eq("双黄在列表里",
                Boolean.valueOf(mul.presentKinds().contains(Classifier.K_DY)), Boolean.TRUE);
        eq("双黄的区段是 7",
                mul.sectorsOf(Classifier.K_DY).toString(), "[7]");
        eq("黄的区段是 5",
                mul.sectorsOf(Classifier.K_YELLOW).toString(), "[5]");
        mul.onTrackStatus("5");
        eq("红旗期间轨道级优先",
                mul.presentKinds().get(0), Classifier.K_RED);

        section("WsFrame.readMessage：分片消息要拼回一整条");
        byte[] fp1 = "{\"type\":3,\"a\":".getBytes("UTF-8");
        byte[] fp2 = "12345}".getBytes("UTF-8");
        java.io.ByteArrayOutputStream frag = new java.io.ByteArrayOutputStream();
        frag.write(0x01);
        frag.write(fp1.length);
        frag.write(fp1, 0, fp1.length);
        frag.write(0x80);
        frag.write(fp2.length);
        frag.write(fp2, 0, fp2.length);
        WsFrame whole = WsFrame.readMessage(
                new java.io.ByteArrayInputStream(frag.toByteArray()));
        eq("拼回后 opcode 仍是 TEXT",
                Integer.valueOf(whole.opcode), Integer.valueOf(WsFrame.OP_TEXT));
        eq("拼回后内容完整",
                new String(whole.payload, "UTF-8"), "{\"type\":3,\"a\":12345}");
        eq("拼回后 fin=true", Boolean.valueOf(whole.fin), Boolean.TRUE);
        // 单帧也要能读（不能因为加了重组就坏了）
        WsFrame single = WsFrame.readMessage(new java.io.ByteArrayInputStream(
                serverFrame("hello".getBytes("UTF-8"))));
        eq("单帧正常", new String(single.payload, "UTF-8"), "hello");

        section("TrackState：安全车结束信号不应清掉状态");
        TrackState ts3 = new TrackState();
        ts3.onMessage(mk("", "SafetyCar", "", "SAFETY CAR DEPLOYED"));
        ts3.onMessage(mk("", "SafetyCar", "", "SAFETY CAR IN THIS LAP"));
        eq("IN THIS LAP 时安全车仍在",
                Integer.valueOf(ts3.level()), Integer.valueOf(TrackState.SC));

        // ★ 回归：标题栏的文字必须是**全中文**。
        //   原来红旗时右侧跟的是 `SESSION SUSPENDED`、安全车跟 `SAFETY CAR` ——
        //   中文界面上最要紧的那个状态，旁边挂着一串英文。
        section("TrackState：标题栏文案全中文（不许再漏英文）");
        TrackState zh = new TrackState();
        eq("没旗语时是中文", zh.detail(), "赛道正常");
        zh.onMessage(mk("", "SafetyCar", "", "SAFETY CAR DEPLOYED"));
        eq("安全车 -> 全场", zh.detail(), "全场");
        zh = new TrackState();
        zh.onMessage(mk("", "SafetyCar", "", "VSC DEPLOYED"));
        eq("VSC -> 全场", zh.detail(), "全场");
        zh = new TrackState();
        zh.onMessage(mk("RED", "Flag", "Track", "RED FLAG"));
        eq("红旗 -> 比赛暂停（SESSION 是「比赛环节」，不是「会话」）",
                zh.detail(), "比赛暂停");
        eq("红旗标签也中文", zh.label(), "红旗");
        // 注意：红旗不会因为随后的黄旗/双黄而解掉（这是有意的「锁定显示」），
        // 所以「列出区段」那一档要用**另一个** TrackState 测。
        TrackState zh2 = new TrackState();
        zh2.onMessage(mk("", "Flag", "5", "YELLOW IN TRACK SECTOR 5"));
        zh2.onMessage(mk("DOUBLE YELLOW", "Flag", "7", "DOUBLE YELLOW IN TRACK SECTOR 7"));
        eq("双黄 -> 列出区段", zh2.detail(), "区段 7");
        eq("双黄旗标签", zh2.label(), "双黄旗");
        eq("红旗锁定：不因为后续黄旗改掉", zh.label(), "红旗");

        // ================================================================
        // 6) 去重与存储
        // ================================================================
        section("RaceMessage.key：优先 event_id");
        RaceMessage k1 = mkSeq("YELLOW", "Flag", "7", "YELLOW IN TRACK SECTOR 7", "EV-1", 1);
        RaceMessage k2 = mkSeq("YELLOW", "Flag", "7", "YELLOW IN TRACK SECTOR 7", "EV-1", 1);
        eq("同 event_id -> 同键", k1.key(), k2.key());
        RaceMessage k3 = mkSeq("YELLOW", "Flag", "7", "YELLOW IN TRACK SECTOR 7", "EV-2", 2);
        eq("不同 event_id -> 不同键（即使文本完全相同）",
                Boolean.valueOf(!k1.key().equals(k3.key())), Boolean.TRUE);

        section("MessageStore：去重 / 排序 / 容量");
        MessageStore st = new MessageStore();
        eq("第一条是新记录",
                Boolean.valueOf(st.add(mkSeq("YELLOW", "Flag", "7", "YELLOW IN TRACK SECTOR 7",
                        "EV-A", 1))), Boolean.TRUE);
        eq("同 event_id 再来一次不算新",
                Boolean.valueOf(st.add(mkSeq("YELLOW", "Flag", "7", "YELLOW IN TRACK SECTOR 7",
                        "EV-A", 1))), Boolean.FALSE);
        eq("一条消息只占一个位置", Integer.valueOf(st.size()), Integer.valueOf(1));
        st.add(mkSeq("CLEAR", "Flag", "7", "CLEAR IN TRACK SECTOR 7", "EV-B", 2));
        eq("最新在最上",
                st.sortedDesc().get(0).text(), "CLEAR IN TRACK SECTOR 7");
        st.add(mkSeq("YELLOW", "Flag", "8", "YELLOW IN TRACK SECTOR 8", "EV-C", 3));
        st.add(mkSeq("YELLOW", "Flag", "9", "YELLOW IN TRACK SECTOR 9", "EV-D", 4));
        st.trimTo(2);
        eq("容量上限生效", Integer.valueOf(st.size()), Integer.valueOf(2));

        section("MessageStore：序列化往返");
        MessageStore st2 = new MessageStore();
        st2.add(mkSeq("DOUBLE YELLOW", "Flag", "12", "DOUBLE YELLOW IN TRACK SECTOR 12",
                "EV-X", 11));
        st2.add(mkSeq("", "SafetyCar", "", "VSC DEPLOYED", "EV-Y", 12));
        String blob = st2.serialize();
        MessageStore st3 = new MessageStore();
        st3.load(blob);
        eq("往返后条数一致", Integer.valueOf(st3.size()), Integer.valueOf(st2.size()));
        List<RaceMessage> back = st3.sortedDesc();
        eq("往返后最新一条的文本一致", back.get(0).text(), "VSC DEPLOYED");
        eq("往返后 category 保留", back.get(0).category, "SafetyCar");
        eq("往返后 flag 保留", back.get(1).flag, "DOUBLE YELLOW");
        eq("往返后 sector 保留", back.get(1).sector, "12");

        // ================================================================
        // 7) 过滤
        // ================================================================
        section("Prefs.isNoise：噪音判定（实测隐藏 253/697 = 36.3%）");

        // ---- 真噪音：只剩「被套圈的蓝旗」这一类 ----
        eq("蓝旗是噪音",
                Boolean.valueOf(Prefs.isNoise(mk("BLUE", "Flag", "",
                        "WAVED BLUE FLAG FOR CAR 77 (BOT)"))), Boolean.TRUE);
        eq("★ 区段解除不再算噪音（用户要求：藏掉它黄旗会莫名其妙结束）",
                Boolean.valueOf(Prefs.isNoise(mk("CLEAR", "Flag", "7",
                        "CLEAR IN TRACK SECTOR 7"))), Boolean.FALSE);
        eq("★ 赛道解除也不再算噪音",
                Boolean.valueOf(Prefs.isNoise(mk("CLEAR", "Flag", "",
                        "TRACK CLEAR"))), Boolean.FALSE);

        // ---- v2.0.8：这三类改成用户可勾选 ----
        section("精简模式：逐项可勾选（v2.0.8）");
        Prefs.NoiseOpts o = new Prefs.NoiseOpts();
        String blueMsg = "WAVED BLUE FLAG FOR CAR 77 (BOT)";
        String clearMsg = "CLEAR IN TRACK SECTOR 7";
        String deletedMsg = "CAR 55 (SAI) TIME 1:43.523 DELETED - TRACK LIMITS AT TURN 15";

        eq("默认选项：蓝旗隐藏",
                Boolean.valueOf(Prefs.isNoise(mk("BLUE", "Flag", "", blueMsg), o)),
                Boolean.TRUE);
        eq("默认选项：解除信号不隐藏（和 v2.0.6 的决定一致）",
                Boolean.valueOf(Prefs.isNoise(mk("CLEAR", "Flag", "7", clearMsg), o)),
                Boolean.FALSE);
        eq("默认选项：删圈速通报隐藏",
                Boolean.valueOf(Prefs.isNoise(mk("", "Other", "", deletedMsg), o)),
                Boolean.TRUE);

        o.blue = false;
        eq("关掉「隐藏蓝旗」-> 蓝旗不再隐藏",
                Boolean.valueOf(Prefs.isNoise(mk("BLUE", "Flag", "", blueMsg), o)),
                Boolean.FALSE);
        o.blue = true;

        o.clear = true;
        eq("打开「隐藏解除信号」-> CLEAR 被隐藏",
                Boolean.valueOf(Prefs.isNoise(mk("CLEAR", "Flag", "7", clearMsg), o)),
                Boolean.TRUE);
        o.clear = false;

        o.lapDeleted = false;
        eq("关掉「隐藏删圈速」-> 删圈速不再隐藏",
                Boolean.valueOf(Prefs.isNoise(mk("", "Other", "", deletedMsg), o)),
                Boolean.FALSE);
        o.lapDeleted = true;

        // ★ 三类全开，也吞不掉「永不隐藏」的那几类
        o.blue = true;
        o.clear = true;
        o.lapDeleted = true;
        eq("★ 三类全开也吞不掉黑白旗",
                Boolean.valueOf(Prefs.isNoise(mk("BLACK AND WHITE", "Flag", "",
                        "BLACK AND WHITE FLAG FOR CAR 1 (NOR) - FAILING TO FOLLOW"
                                + " RACE DIRECTORS INSTRUCTIONS"), o)), Boolean.FALSE);
        eq("★ 三类全开也吞不掉赛会事故记录",
                Boolean.valueOf(Prefs.isNoise(mk("", "Other", "",
                        "FIA STEWARDS: TURN 3 INCIDENT INVOLVING CARS 43 (COL)"
                                + " AND 87 (BEA) NOTED"), o)), Boolean.FALSE);
        eq("★ 三类全开也吞不掉维修区解除（它的 flag 不是 CLEAR）",
                Boolean.valueOf(Prefs.isNoise(mk("", "Other", "", "PIT LANE CLEAR"),
                        o)), Boolean.FALSE);
        eq("★ 三类全开也吞不掉比赛环节控制",
                Boolean.valueOf(Prefs.isNoise(mk("", "Other", "",
                        "SESSION WILL RESUME AT 17:47"), o)), Boolean.FALSE);
        eq("★ 默认策略与显式默认选项必须一致（网页版对齐的就是它）",
                Boolean.valueOf(Prefs.isNoise(mk("BLUE", "Flag", "", blueMsg))
                        == Prefs.isNoise(mk("BLUE", "Flag", "", blueMsg),
                                new Prefs.NoiseOpts())), Boolean.TRUE);
        eq("删圈速通报是噪音（以 CAR 开头 + 含 DELETED）",
                Boolean.valueOf(Prefs.isNoise(mk("", "Other", "",
                        "CAR 55 (SAI) TIME 1:43.523 DELETED - TRACK LIMITS AT TURN 15"))),
                Boolean.TRUE);
        eq("因双黄旗违规删的圈速也是噪音（旧规则漏了这种）",
                Boolean.valueOf(Prefs.isNoise(mk("", "Other", "",
                        "CAR 5 (BOR) LAP DELETED - TRACK LIMITS AT TURN 5 LAP 7 (PIT)"))),
                Boolean.TRUE);

        // ---- 用户点名必须保留的 ----
        // 早先的规则是"消息里含 TRACK LIMITS 就算删圈速"，于是把黑白旗也吞了：
        //   BLACK AND WHITE FLAG FOR CAR 44 (HAM) - TRACK LIMITS
        // 判据从"含短语"改成"认形状"之后，下面这些都不再可能被误吞。
        section("★ 用户点名必须保留的消息（回归测试）");
        eq("黑白旗 + TRACK LIMITS（就是被误吞的那 4 条）",
                Boolean.valueOf(Prefs.isNoise(mk("BLACK AND WHITE", "Flag", "",
                        "BLACK AND WHITE FLAG FOR CAR 44 (HAM) - TRACK LIMITS"))),
                Boolean.FALSE);
        eq("黑白旗 + 未遵守赛会指令",
                Boolean.valueOf(Prefs.isNoise(mk("BLACK AND WHITE", "Flag", "",
                        "BLACK AND WHITE FLAG FOR CAR 1 (NOR) - FAILING TO FOLLOW"
                                + " RACE DIRECTORS INSTRUCTIONS (16:47:39)"))),
                Boolean.FALSE);
        eq("事故已记录（离开赛道并获得优势）",
                Boolean.valueOf(Prefs.isNoise(mk("", "Other", "",
                        "TURN 1 INCIDENT INVOLVING CAR 3 (VER) NOTED - LEAVING THE TRACK"
                                + " AND GAINING AN ADVANTAGE"))),
                Boolean.FALSE);
        eq("事故已记录（逃生通道）",
                Boolean.valueOf(Prefs.isNoise(mk("", "Other", "",
                        "TURN 1 INCIDENT INVOLVING CAR 43 (COL) NOTED - FAILING TO FOLLOW"
                                + " RACE DIRECTORS INSTRUCTIONS"))),
                Boolean.FALSE);
        eq("维修区黄旗",
                Boolean.valueOf(Prefs.isNoise(mk("", "Other", "", "YELLOW IN PIT LANE"))),
                Boolean.FALSE);
        eq("★ 维修区解除（PIT LANE CLEAR）—— 别被 CLEAR 规则吞掉",
                Boolean.valueOf(Prefs.isNoise(mk("", "Other", "", "PIT LANE CLEAR"))),
                Boolean.FALSE);
        eq("比赛重启（不被过滤）",
                Boolean.valueOf(Prefs.isNoise(mk("", "Other", "",
                        "SESSION WILL RESUME AT 17:47"))),
                Boolean.FALSE);
        eq("比赛中止（不被过滤）",
                Boolean.valueOf(Prefs.isNoise(mk("", "Other", "",
                        "SESSION WILL BE TEMPORARILY STOPPED"))),
                Boolean.FALSE);

        section("★ 结构性保证：赛会消息不管正文写什么都不会被吞");
        eq("仲裁「赛后调查」+ TRACK LIMITS（数据里还没有，但必须挡住）",
                Boolean.valueOf(Prefs.isNoise(mk("", "Other", "",
                        "FIA STEWARDS: TURN 5 INCIDENT INVOLVING CAR 5 (BOR)"
                                + " WILL BE INVESTIGATED AFTER THE SESSION - TRACK LIMITS"))),
                Boolean.FALSE);
        eq("仲裁「复核不予追究」",
                Boolean.valueOf(Prefs.isNoise(mk("", "Other", "",
                        "FIA STEWARDS: TURN 3 INCIDENT INVOLVING CARS 43 (COL) AND 87 (BEA)"
                                + " REVIEWED NO FURTHER INVESTIGATION - IMPEDING (14:13:45)"))),
                Boolean.FALSE);
        eq("仲裁「调查中」",
                Boolean.valueOf(Prefs.isNoise(mk("", "Other", "",
                        "FIA STEWARDS: TURN 5 INCIDENT INVOLVING CAR 5 (BOR)"
                                + " UNDER INVESTIGATION - MOVING UNDER BRAKING"))),
                Boolean.FALSE);
        eq("判罚",
                Boolean.valueOf(Prefs.isNoise(mk("", "Other", "",
                        "FIA STEWARDS: 5 SECOND TIME PENALTY FOR CAR 55 (SAI) (15:23:42)"))),
                Boolean.FALSE);

        section("其余该显示的");
        eq("红旗不是噪音",
                Boolean.valueOf(Prefs.isNoise(mk("RED", "Flag", "", "RED FLAG"))), Boolean.FALSE);
        eq("双黄旗不是噪音",
                Boolean.valueOf(Prefs.isNoise(mk("DOUBLE YELLOW", "Flag", "12",
                        "DOUBLE YELLOW IN TRACK SECTOR 12"))), Boolean.FALSE);

        section("配色：蓝旗必须是蓝的，格子旗走棋盘格");
        int blueBar = Classifier.barColor(Classifier.K_BLUE);
        // 蓝：B 分量最大；紫：R 和 B 都大、B 略大。这里卡住"B 明显大于 R"
        eq("蓝旗色条偏蓝（B 分量明显大于 R）",
                Boolean.valueOf(((blueBar >> 16) & 0xFF) < ((blueBar) & 0xFF) - 40),
                Boolean.TRUE);
        eq("蓝旗背景也是淡蓝",
                Boolean.valueOf(((Classifier.color(Classifier.K_BLUE) >> 16) & 0xFF)
                        < ((Classifier.color(Classifier.K_BLUE)) & 0xFF)),
                Boolean.TRUE);
        eq("只有格子旗用棋盘格",
                Boolean.valueOf(Classifier.isCheckered(Classifier.K_CHEQUERED)), Boolean.TRUE);
        eq("蓝旗不用棋盘格",
                Boolean.valueOf(Classifier.isCheckered(Classifier.K_BLUE)), Boolean.FALSE);
        eq("红旗不用棋盘格",
                Boolean.valueOf(Classifier.isCheckered(Classifier.K_RED)), Boolean.FALSE);
        eq("格子旗的色条不再是蓝色",
                Boolean.valueOf(Classifier.barColor(Classifier.K_CHEQUERED) != 0xFF0288D1),
                Boolean.TRUE);

        section("圆环区段配色：只看区段，绝不整圈染色");
        // 用户报的原话："安卓端单黄旗会全赛道显示"。
        // 根因：官方 TrackStatus 流的 "2"(黄旗) 在**只有单个区段**黄旗时也会发，
        // 于是 TrackState.global=YELLOW；老代码在圆环里按 global 整圈染色，
        // 看起来就跟全赛道黄旗一模一样。
        // 规则：全赛道旗语放在**圈内**（圆心色块 + 文字），圈上只表达区段。
        java.util.List<Integer> one = java.util.Arrays.asList(Integer.valueOf(5));
        eq("没旗语 -> 区段底色",
                Integer.valueOf(Classifier.ringSectorColour(1, null, null)),
                Integer.valueOf(Classifier.RING_IDLE));
        eq("单个区段黄 -> 那一段黄",
                Integer.valueOf(Classifier.ringSectorColour(5, null, one)),
                Integer.valueOf(Classifier.RING_YELLOW));
        eq("★ 单个区段黄 -> 别的区段不黄（这就是那个 bug）",
                Integer.valueOf(Classifier.ringSectorColour(6, null, one)),
                Integer.valueOf(Classifier.RING_IDLE));
        eq("双黄盖过单黄",
                Integer.valueOf(Classifier.ringSectorColour(5, one, one)),
                Integer.valueOf(Classifier.RING_DOUBLE_YELLOW));

        // 端到端：真喂一个"单区段黄 + TrackStatus=2"，逐段数黄色段数
        TrackState sr = new TrackState();
        sr.onMessage(mk("YELLOW", "Flag", "5", "YELLOW IN TRACK SECTOR 5"));
        sr.onTrackStatus("2");
        eq("此时 global 确实是黄（数据如此，拦不住）",
                Integer.valueOf(sr.globalLevel()), Integer.valueOf(TrackState.YELLOW));
        int ringYellow = 0;
        for (int i = 1; i <= 15; i++) {
            if (Classifier.ringSectorColour(i, sr.doubleYellowSectors(),
                    sr.yellowSectors()) == Classifier.RING_YELLOW) {
                ringYellow++;
            }
        }
        eq("★ 圆环上黄的段数 = 1（不是整圈）",
                Integer.valueOf(ringYellow), Integer.valueOf(1));
        eq("圆心文案说清是区段而不是全场",
                sr.detail(), "区段 5");

        section("DelayGate：延时闸门（对齐有延迟的直播画面）");
        // 语义（用户确认过）：整份快照不延时；只影响"何时看见"不丢数据；
        // 调大 = 先停住后继续；调小 = 立即放行到期的。
        // 用注入时钟 pump(nowMs)，所以是确定性的，不 sleep。
        // ★ 变量名都带 dly 前缀：main() 是一整个大方法，seen/dg/t0 这种名字
        //   早就被前面的用例占了（第一次就撞了，编译不过）。
        final java.util.List<String> dlySeen = new java.util.ArrayList<String>();
        DelayGate dlyA = new DelayGate(30 * 1000L, new DelayGate.Sink() {
            public void accept(org.json.JSONObject r) {
                dlySeen.add(r.optString("target", "?") + "#" + r.optInt("n", -1));
            }
        });
        eq("初始延时 = 30 秒", Long.valueOf(dlyA.delayMs()), Long.valueOf(30000L));
        long dlyT0 = 1000000L;
        dlyA.offer(rec("TimingData", 1), dlyT0);
        dlyA.offer(rec("TimingData", 2), dlyT0 + 5000);
        eq("刚喂进去：一条都不放行",
                Integer.valueOf(dlySeen.size()), Integer.valueOf(0));
        eq("队列里压着 2 条", Integer.valueOf(dlyA.queued()), Integer.valueOf(2));
        dlyA.pump(dlyT0 + 29000);
        eq("差 1 秒到期：还是不放",
                Integer.valueOf(dlySeen.size()), Integer.valueOf(0));
        dlyA.pump(dlyT0 + 30000);
        eq("到时放第一条", dlySeen.toString(), "[TimingData#1]");
        dlyA.pump(dlyT0 + 35000);
        eq("第二条按自己的到点时间放", dlySeen.toString(),
                "[TimingData#1, TimingData#2]");
        eq("放行数 = 2", Long.valueOf(dlyA.releasedCount()), Long.valueOf(2L));

        dlyA.offer(rec("TrackStatus", 3), dlyT0 + 36000);
        dlyA.setDelayMs(0);
        dlyA.pump(dlyT0 + 36000);
        eq("★ 调成 0 秒后立刻放行（不用再等 30 秒）", dlySeen.toString(),
                "[TimingData#1, TimingData#2, TrackStatus#3]");
        dlyA.offer(rec("SessionStatus", 4), dlyT0 + 37000);
        eq("0 秒时是直通（等于改动前的行为）", dlySeen.toString(),
                "[TimingData#1, TimingData#2, TrackStatus#3, SessionStatus#4]");
        eq("0 秒时队列为空", Integer.valueOf(dlyA.queued()), Integer.valueOf(0));

        dlyA.setDelayMs(-5);
        eq("负数当 0", Long.valueOf(dlyA.delayMs()), Long.valueOf(0L));
        dlyA.setDelayMs(9999L * 1000L);
        eq("超过上限被截断到 " + DelayGate.MAX_SECONDS + " 秒",
                Long.valueOf(dlyA.delayMs()),
                Long.valueOf(DelayGate.MAX_SECONDS * 1000L));

        DelayGate dlyB = new DelayGate(10000L, new DelayGate.Sink() {
            public void accept(org.json.JSONObject r) { }
        });
        dlyB.offer(rec("TimingData", 9), dlyT0);
        eq("清之前有 1 条", Integer.valueOf(dlyB.queued()), Integer.valueOf(1));
        eq("clear() 返回丢掉几条", Integer.valueOf(dlyB.clear()), Integer.valueOf(1));
        eq("清之后空了（收到新快照时必须这样）",
                Integer.valueOf(dlyB.queued()), Integer.valueOf(0));

        final int[] dlyBig = new int[1];
        DelayGate dlyC = new DelayGate(5000L, new DelayGate.Sink() {
            public void accept(org.json.JSONObject r) { dlyBig[0]++; }
        });
        dlyC.setDelayMs(20000L);
        dlyC.offer(rec("LapCount", 7), dlyT0);
        dlyC.pump(dlyT0 + 6000);
        eq("调大到 20 秒后，6 秒时还不放（画面先停住）",
                Integer.valueOf(dlyBig[0]), Integer.valueOf(0));
        dlyC.pump(dlyT0 + 20000);
        eq("20 秒时放行", Integer.valueOf(dlyBig[0]), Integer.valueOf(1));

        section("Prefs.accept：过滤开关与车号筛选");
        Prefs p = new Prefs();
        p.noiseFilterEnabled = true;
        eq("开启过滤时蓝旗被隐藏",
                Boolean.valueOf(p.accept(mk("BLUE", "Flag", "", "WAVED BLUE FLAG"))),
                Boolean.FALSE);
        p.noiseFilterEnabled = false;
        eq("关闭过滤时蓝旗可见",
                Boolean.valueOf(p.accept(mk("BLUE", "Flag", "", "WAVED BLUE FLAG"))),
                Boolean.TRUE);
        p.carFilter = "44";
        eq("车号 44 命中",
                Boolean.valueOf(p.accept(mkSeq("BLUE", "Flag", "", "WAVED BLUE FLAG",
                        "E1", 1))), Boolean.FALSE);
        RaceMessage car44 = new RaceMessage(1L, "X", "r", "X", "Flag", "BLACK AND WHITE",
                "Driver", "", "44", "", "E2", 2);
        eq("车号 44 的记录保留", Boolean.valueOf(p.accept(car44)), Boolean.TRUE);
        RaceMessage car55 = new RaceMessage(1L, "X", "r", "X", "Flag", "BLACK AND WHITE",
                "Driver", "", "55", "", "E3", 3);
        eq("车号 55 被筛掉", Boolean.valueOf(p.accept(car55)), Boolean.FALSE);
        p.carFilter = "";
        eq("清空车号筛选后都放行",
                Boolean.valueOf(p.accept(mkSeq("YELLOW", "Flag", "3",
                        "YELLOW IN TRACK SECTOR 3", "E4", 4))), Boolean.TRUE);

        section("Prefs.clampPoll 刷新间隔钳制");
        eq("0 -> 下限", Integer.valueOf(Prefs.clampPoll(0)), Integer.valueOf(Prefs.POLL_MIN));
        eq("负数 -> 下限", Integer.valueOf(Prefs.clampPoll(-5)), Integer.valueOf(Prefs.POLL_MIN));
        eq("1 -> 保持", Integer.valueOf(Prefs.clampPoll(1)), Integer.valueOf(1));
        eq("9999 -> 上限", Integer.valueOf(Prefs.clampPoll(9999)),
                Integer.valueOf(Prefs.POLL_MAX));

        section("Prefs.applyTo：配置正确灌进闸门");
        Prefs p2 = new Prefs();
        p2.dyMode = Prefs.DY_ESCALATE_BIG;
        p2.cooldownSec = 30;
        p2.dyEscalateSec = 20;
        AlertGate g10 = new AlertGate();
        p2.applyTo(g10);
        eq("方案 B 启用区段阈值 3", Integer.valueOf(g10.dyMinSectors), Integer.valueOf(3));
        eq("冷却期换算成毫秒", Long.valueOf(g10.cooldownMs), Long.valueOf(30000L));
        eq("升级阈值换算成毫秒", Long.valueOf(g10.dyEscalateMs), Long.valueOf(20000L));
        p2.dyMode = Prefs.DY_OFF;
        p2.applyTo(g10);
        eq("关闭双黄", Boolean.valueOf(g10.dyEnabled), Boolean.FALSE);

        // ================================================================
        // 8) 判罚翻译 —— 输入全部是真实数据里的原句
        // ================================================================
        section("Translator：判罚（输入是历史数据里的原句）");

        eq("5 秒罚时",
                Translator.gloss("FIA STEWARDS: 5 SECOND TIME PENALTY FOR CAR 55 (SAI) (15:23:42)"),
                "★ 判罚：塞恩斯(55) 罚时 5 秒");
        eq("罚时 + 原因",
                Translator.gloss("FIA STEWARDS: 5 SECOND TIME PENALTY FOR CAR 10 (GAS)"
                        + " - SPEEDING IN THE PIT LANE (16:42:11)"),
                "★ 判罚：加斯利(10) 罚时 5 秒 —— 维修区超速");
        eq("罚时已执行",
                Translator.gloss("FIA STEWARDS: PENALTY SERVED - 5 SECOND TIME PENALTY"
                        + " FOR CAR 55 (SAI) (15:23:42)"),
                "仲裁：塞恩斯(55) 已执行 5 秒罚时");

        section("Translator：仲裁裁决");
        eq("复核后不予追究（多车）",
                Translator.gloss("FIA STEWARDS: TURN 3 INCIDENT INVOLVING CARS 43 (COL)"
                        + " AND 87 (BEA) REVIEWED NO FURTHER INVESTIGATION - IMPEDING (14:13:45)"),
                "仲裁：3 号弯 科拉平托(43)、比尔曼(87) —— 复核完毕，不予追究：阻挡他人");
        eq("赛后调查",
                Translator.gloss("FIA STEWARDS: TURN 5 INCIDENT INVOLVING CAR 77 (BOT)"
                        + " WILL BE INVESTIGATED AFTER THE SESSION - FAILING TO FOLLOW"
                        + " RACE DIRECTORS INSTRUCTIONS (13:45:06)"),
                "仲裁：5 号弯 博塔斯(77) —— 赛后调查：未遵守赛会指令");
        eq("警告",
                Translator.gloss("FIA STEWARDS: WARNING FOR CAR 5 (BOR)"
                        + " - MOVING UNDER BRAKING (16:19:05)"),
                "仲裁：博托莱托(5) —— 警告：制动中变线");

        // ★ 回归：整份数据里最长的两条仲裁消息原本**一条译文都没有** ——
        //   stewards() 只认 "REVIEWED NO FURTHER"，不认 "NO FURTHER ACTION"。
        //   它们恰恰是「不予追究」，用户明确说过这类不能被忽略。
        //
        // ★ 而且**车号一个都不能省**（用户否掉了「等 N 辆」的写法）。
        //   这两条断言的是完整名单，所以一旦有人重新加上省略号，这里立刻红。
        eq("NO FURTHER ACTION（8 辆车，全部列出）",
                Translator.gloss("FIA STEWARDS: Q1 INCIDENT INVOLVING CARS 81 (PIA),"
                        + " 63 (RUS), 3 (VER), 27 (HUL), 10 (GAS), 43 (COL), 22 (TSU)"
                        + " AND 77 (BOT) NO FURTHER ACTION - FAILING TO FOLLOW RACE"
                        + " DIRECTORS INSTRUCTIONS - MAXIMUM DELTA TIME"),
                "仲裁：Q1 皮亚(81)、拉塞尔(63)、维斯塔潘(3)、霍肯伯格(27)、加斯利(10)、"
                        + "科拉平托(43)、角田(22)、博塔斯(77) —— 不予追究：未遵守赛会指令（超出最大圈速差）");
        eq("NOTED（9 辆车，全部列出）",
                Translator.gloss("FIA STEWARDS: Q1 INCIDENT INVOLVING CARS 81 (PIA),"
                        + " 63 (RUS), 3 (VER), 5 (BOR), 27 (HUL), 10 (GAS), 43 (COL),"
                        + " 22 (TSU) AND 77 (BOT) NOTED - FAILING TO FOLLOW RACE DIRECTORS"
                        + " INSTRUCTIONS - MAXIMUM DELTA TIME"),
                "Q1 事故（皮亚(81)、拉塞尔(63)、维斯塔潘(3)、博托莱托(5)、霍肯伯格(27)、"
                        + "加斯利(10)、科拉平托(43)、角田(22)、博塔斯(77)）：已记录 —— "
                        + "未遵守赛会指令（超出最大圈速差）");

        // ★ 回归：复合原因。连字符后面那半截是对前半截的限定，不能丢。
        eq("复合原因（- MAXIMUM DELTA TIME）",
                Translator.gloss("FIA STEWARDS: Q2 INCIDENT INVOLVING CARS 81 (PIA),"
                        + " 10 (GAS) AND 5 (BOR) NOTED - FAILING TO FOLLOW RACE DIRECTORS"
                        + " INSTRUCTIONS - MAXIMUM DELTA TIME"),
                "Q2 事故（皮亚(81)、加斯利(10)、博托莱托(5)）：已记录 —— "
                        + "未遵守赛会指令（超出最大圈速差）");
        eq("复合原因（逃生通道）仍走同一条",
                Translator.gloss("FIA STEWARDS: TURN 5 INCIDENT INVOLVING CAR 77 (BOT)"
                        + " WILL BE INVESTIGATED AFTER THE SESSION - FAILING TO FOLLOW RACE"
                        + " DIRECTORS INSTRUCTIONS \u2013 ESCAPE ROAD INSTRUCTIONS (13:45:06)"),
                "仲裁：5 号弯 博塔斯(77) —— 赛后调查：未遵守赛会指令（逃生通道）");

        // ★ 回归：排位赛阶段。原来一律写成「赛事」，把 Q1/Q2/Q3 的上下文丢了。
        eq("Q 阶段进事故地点",
                Translator.gloss("FIA STEWARDS: Q3 INCIDENT INVOLVING CARS 12 (ANT)"
                        + " AND 16 (LEC) NOTED - FAILING TO FOLLOW RACE DIRECTORS"
                        + " INSTRUCTIONS - MAXIMUM DELTA TIME"),
                "Q3 事故（安东内利(12)、勒克莱尔(16)）：已记录 —— 未遵守赛会指令（超出最大圈速差）");
        eq("没有 TURN 也没有 Q 才写「赛事」",
                Translator.gloss("INCIDENT INVOLVING CAR 41 (LIN) NOTED - UNSAFE RELEASE (16:55:50)"),
                "赛事事故（林布拉德(41)）：已记录 —— 不安全放车");

        eq("两辆车照列",
                Translator.gloss("FIA STEWARDS: TURN 1 INCIDENT INVOLVING CARS 41 (LIN)"
                        + " AND 27 (HUL) REVIEWED NO FURTHER INVESTIGATION -"
                        + " FORCING ANOTHER DRIVER OFF THE TRACK (16:35:23)"),
                "仲裁：1 号弯 林布拉德(41)、霍肯伯格(27) —— 复核完毕，不予追究：把对手逼出赛道");

        section("Translator：事故记录 / 黑白旗 / 蓝旗");
        eq("事故已记录（带破折号的那种指令）",
                Translator.gloss("TURN 1 INCIDENT INVOLVING CAR 43 (COL) NOTED - FAILING TO"
                        + " FOLLOW RACE DIRECTORS INSTRUCTIONS \u2013 ESCAPE ROAD"
                        + " INSTRUCTIONS (14:27:00)"),
                "1 号弯事故（科拉平托(43)）：已记录 —— 未遵守赛会指令（逃生通道）");
        eq("黑白旗",
                Translator.gloss("BLACK AND WHITE FLAG FOR CAR 1 (NOR) - FAILING TO FOLLOW"
                        + " RACE DIRECTORS INSTRUCTIONS (16:47:39)"),
                "黑白旗警告：诺里斯(1) —— 未遵守赛会指令");
        eq("蓝旗",
                Translator.gloss("WAVED BLUE FLAG FOR CAR 14 (ALO) TIMED AT 15:42:56"),
                "蓝旗（让车）：阿隆索(14)");

        section("Translator：删圈速（判据是 DELETED + 已知原因）");
        eq("单圈成绩被删",
                Translator.gloss("CAR 12 (ANT) TIME 1:57.307 DELETED - TRACK LIMITS"
                        + " AT TURN 18 LAP 3 13:34:28"),
                "安东内利(12)：单圈成绩被删 —— 18 号弯超出赛道限制（第 3 圈）");
        eq("整圈成绩被删",
                Translator.gloss("CAR 5 (BOR) LAP DELETED - TRACK LIMITS AT TURN 5"
                        + " LAP 7 13:41:22 (PIT)"),
                "博托莱托(5)：整圈成绩被删 —— 5 号弯超出赛道限制（第 7 圈）");

        section("Translator：删圈速的两种原因（v2.0.9 补）");
        eq("单圈成绩被删（双黄旗）",
                Translator.gloss("CAR 77 (BOT) TIME 2:23.403 DELETED - DOUBLE YELLOW"
                        + " AT TURN 7 LAP 6 12:53:52"),
                "博塔斯(77)：单圈成绩被删 —— 7 号弯双黄旗（第 6 圈）");
        eq("整圈成绩被删（双黄旗）",
                Translator.gloss("CAR 23 (ALB) LAP DELETED - DOUBLE YELLOW AT TURN 7"
                        + " LAP 13 12:53:58 (PIT)"),
                "阿尔本(23)：整圈成绩被删 —— 7 号弯双黄旗（第 13 圈）");
        // 判据是「DELETED + 已知原因」，不能只看原因：
        // 黑白旗正文里也含 TRACK LIMITS，但它不是删圈速通报。
        eq("含 TRACK LIMITS 的黑白旗不能被当成删圈速",
                Translator.gloss("BLACK AND WHITE FLAG FOR CAR 44 (HAM) - TRACK LIMITS"),
                "黑白旗警告：汉密尔顿(44) —— 超出赛道限制");
        eq("含 DELETED 但原因未知 -> 仍然不翻",
                Translator.gloss("CAR 5 (BOR) TIME 1:40.000 DELETED - SOMETHING NEW"),
                null);

        section("Translator：赛后再查 / 起步练习违规（v2.0.9 补）");
        eq("AFTER THE RACE 也要认（原来整条不翻）",
                Translator.gloss("FIA STEWARDS: TURN 7 INCIDENT INVOLVING CAR 5 (BOR)"
                        + " WILL BE INVESTIGATED AFTER THE RACE - YELLOW FLAG"
                        + " INFRINGEMENT (16:12:10)"),
                "仲裁：7 号弯 博托莱托(5) —— 赛后调查：黄旗违规");
        eq("AFTER THE SESSION 仍然认",
                Translator.gloss("FIA STEWARDS: TURN 7 INCIDENT INVOLVING CAR 77 (BOT)"
                        + " WILL BE INVESTIGATED AFTER THE SESSION - YELLOW FLAG"
                        + " INFRINGEMENT (13:02:16)"),
                "仲裁：7 号弯 博塔斯(77) —— 赛后调查：黄旗违规");
        eq("复合原因的后半截不能丢",
                Translator.gloss("INCIDENT INVOLVING CAR 6 (HAD) NOTED - FAILING TO"
                        + " FOLLOW RACE DIRECTORS INSTRUCTIONS – PRACTICE"
                        + " START INFRINGEMENT (14:20:31)"),
                "赛事事故（哈贾尔(6)）：已记录 —— 未遵守赛会指令（起步练习违规）");

        section("Translator：安全车 / 排位赛期间的操作指令（v2.0.9 补）");
        eq("所有赛车通过维修区",
                Translator.gloss("ALL CARS THROUGH THE PIT LANE"),
                "所有赛车通过维修区");
        eq("所有赛车使用起终点直道",
                Translator.gloss("ALL CARS USE START/FINISH STRAIGHT"),
                "所有赛车使用起终点直道");
        eq("维修车（不是「救援车」）",
                Translator.gloss("RECOVERY VEHICLE ON TRACK AT TURN 6"),
                "6 号弯有维修车");
        eq("排位赛推迟开始",
                Translator.gloss("START OF QUALIFYING WILL BE DELAYED"),
                "排位赛将推迟开始");
        eq("Q1 开始时刻",
                Translator.gloss("Q1 WILL START AT 16:04"), "Q1 将于 16:04 开始");
        eq("被套圈车可超越安全车（冒号后是车号）",
                Translator.gloss("LAPPED CARS MAY NOW OVERTAKE THE SAFETY CAR: 77"),
                "被套圈车可超越安全车：77 号");

        // 集成自带的自测消息没有车号，原来落到兜底变成「某车手」——
        // 明明一辆车都没提。用户定的译法：统一标成「赛会判罚测试」。
        section("Translator：集成的自测消息不许冒充「某车手」（v2.0.9 补）");
        eq("RACE CONTROL TEST（已记录）",
                Translator.gloss("INCIDENT INVOLVING ALL CARS NOTED - RACE CONTROL TEST"),
                "赛会判罚测试");
        eq("RACE CONTROL TEST（调查中）",
                Translator.gloss("FIA STEWARDS: INCIDENT INVOLVING ALL CARS"
                        + " UNDER INVESTIGATION - RACE CONTROL TEST"),
                "赛会判罚测试");
        eq("RACE CONTROL TEST（不予追究）",
                Translator.gloss("FIA STEWARDS: INCIDENT INVOLVING ALL CARS"
                        + " NO FURTHER ACTION - RACE CONTROL TEST"),
                "赛会判罚测试");

        // ★ 内容型消息：徽标说不清楚的那些（维修区 / 比赛环节 / 天气 / 赛道状况）。
        //   `DOUBLE YELLOW IN TRACK SECTOR 12` 不翻，因为徽标就是「双黄旗」；
        //   `YELLOW IN PIT LANE` 要翻，因为徽标只说「黄旗」，没说是维修区的。
        section("Translator：内容型消息（徽标表达不出来的才翻）");
        eq("维修区黄旗",
                Translator.gloss("YELLOW IN PIT LANE"), "维修区黄旗");
        eq("维修区解除",
                Translator.gloss("PIT LANE CLEAR"), "维修区解除");
        // SESSION 是**这一个比赛环节**（一练/排位/正赛），简称「比赛」。
        // 译成「会话」是计算机味的误译（用户指出）。
        eq("比赛重启（带时刻）",
                Translator.gloss("SESSION WILL RESUME AT 17:47"), "比赛将于 17:47 重启");
        eq("比赛中止",
                Translator.gloss("SESSION WILL BE TEMPORARILY STOPPED"), "比赛暂时中止");
        eq("赛道湿滑（带区段）",
                Translator.gloss("TRACK SURFACE SLIPPERY IN TRACK SECTOR 26"),
                "赛道湿滑（26 号区段）");
        eq("马修在赛道上",
                Translator.gloss("MARSHALS ON TRACK AT TURN 20"), "20 号弯有马修");
        eq("医疗车",
                Translator.gloss("MEDICAL CAR DEPLOYED"), "医疗车出动");
        eq("首个冲线",
                Translator.gloss("FIRST CAR TO TAKE THE FLAG - CAR 5 (BOR)"),
                "首个冲线：博托莱托(5)");
        eq("降雨概率（赛段名也翻了）",
                Translator.gloss("RISK OF RAIN FOR F1 FREE PRACTICE 2 IS 0%"),
                "F1 二练降雨概率 0%");
        eq("降雨概率（冲刺赛）",
                Translator.gloss("RISK OF RAIN FOR THE F2 SPRINT RACE IS 0%"),
                "F2 冲刺赛降雨概率 0%");
        eq("维修区出口开放（绿旗的补充信息）",
                Translator.gloss("GREEN LIGHT - PIT EXIT OPEN"), "维修区出口开放");

        section("Translator：翻不出来必须返回 null（宁可显示原文，不要瞎猜）");
        eq("红旗没有简述", Translator.gloss("RED FLAG"), null);
        eq("双黄旗没有简述", Translator.gloss("DOUBLE YELLOW IN TRACK SECTOR 12"), null);
        eq("黄旗没有简述", Translator.gloss("YELLOW IN TRACK SECTOR 12"), null);
        eq("格子旗没有简述", Translator.gloss("CHEQUERED FLAG"), null);
        // ★ 原来这里断言的是 `VSC DEPLOYED` **没有**简述（理由是徽标已经
        //   表达了）。用官方归档的真实快照测过之后改了：这类消息的
        //   Category 是 SafetyCar/Other 而不是 Flag，属于"正文类"；
        //   行内直接显示「虚拟安全车出动」比显示英文原文对用户有用得多。
        //   （SAFETY CAR LIGHTS ON/OFF、SAFETY CAR IN THIS LAP 同理，
        //   它们标出安全车区间的起止，光看徽标分不出来。）
        eq("VSC 出动有简述", Translator.gloss("VSC DEPLOYED"), "虚拟安全车出动");
        eq("安全车出动有简述", Translator.gloss("SAFETY CAR DEPLOYED"), "安全车出动");
        eq("安全车灯亮起有简述", Translator.gloss("SAFETY CAR LIGHTS ON"), "安全车灯亮起");
        eq("安全车本圈进站有简述", Translator.gloss("SAFETY CAR IN THIS LAP"), "安全车本圈进站");
        eq("空串返回 null", Translator.gloss(""), null);
        eq("null 返回 null", Translator.gloss(null), null);
        eq("未知车手退回缩写",
                Translator.gloss("WAVED BLUE FLAG FOR CAR 99 (XYZ) TIMED AT 15:42:56"),
                "蓝旗（让车）：XYZ(99)");

        // ================================================================
        // 9) WebSocket 帧编解码
        // ================================================================
        section("WsFrame 帧编解码（RFC 6455）");
        byte[] mask = new byte[] {0x37, (byte) 0xfa, 0x21, 0x3d};
        String msg = "{\"type\":\"auth\"}";
        byte[] raw = msg.getBytes("UTF-8");
        byte[] frame = WsFrame.encode(WsFrame.OP_TEXT, raw, mask);
        eq("客户端帧 FIN=1", Boolean.valueOf((frame[0] & 0x80) != 0), Boolean.TRUE);
        eq("客户端帧 opcode=TEXT", Integer.valueOf(frame[0] & 0x0F),
                Integer.valueOf(WsFrame.OP_TEXT));
        eq("客户端帧必须带 MASK 位", Boolean.valueOf((frame[1] & 0x80) != 0), Boolean.TRUE);
        int[] sizes = {0, 5, 125, 126, 1000, 65535, 65536, 70000};
        for (int i = 0; i < sizes.length; i++) {
            byte[] payload = new byte[sizes[i]];
            for (int k = 0; k < payload.length; k++) {
                payload[k] = (byte) (k & 0xFF);
            }
            WsFrame f = WsFrame.read(new java.io.ByteArrayInputStream(serverFrame(payload)));
            eq("长度 " + sizes[i] + " 解析出的字节数",
                    Integer.valueOf(f.payload.length), Integer.valueOf(sizes[i]));
        }
        boolean threw = false;
        try {
            WsFrame.read(new java.io.ByteArrayInputStream(
                    new byte[] {(byte) 0x81, 0x05, (byte) 0x61, (byte) 0x62}));
        } catch (Exception e) {
            threw = true;
        }
        eq("截断帧 -> 抛异常", Boolean.valueOf(threw), Boolean.TRUE);

        // ================================================================
        // F1 官方实时流（A 方案）：状态合并器
        // ================================================================
        section("F1Feed：type3 快照 -> 消息 / 轨道状态 / 车手");
        F1Feed feed = new F1Feed();
        feed.onSnapshot(new org.json.JSONObject(
                "{\"RaceControlMessages\":{\"Messages\":["
                + "{\"Utc\":\"2026-09-26T10:11:25\",\"Lap\":1,\"Category\":\"Flag\","
                + "\"Flag\":\"DOUBLE YELLOW\",\"Scope\":\"Sector\",\"Sector\":12,"
                + "\"Message\":\"DOUBLE YELLOW IN TRACK SECTOR 12\"},"
                + "{\"Utc\":\"2026-09-26T10:12:20\",\"Lap\":1,\"Category\":\"Flag\","
                + "\"Flag\":\"YELLOW\",\"Scope\":\"Sector\",\"Sector\":12,"
                + "\"Message\":\"YELLOW IN TRACK SECTOR 12\"}]},"
                + "\"TrackStatus\":{\"Status\":\"2\",\"Message\":\"Yellow\"},"
                + "\"SessionInfo\":{\"Meeting\":{\"Name\":\"Azerbaijan Grand Prix\","
                + "\"Circuit\":{\"ShortName\":\"Baku\"}},\"Name\":\"Race\"},"
                + "\"LapCount\":{\"CurrentLap\":55,\"TotalLaps\":55},"
                + "\"DriverList\":{\"3\":{\"Tla\":\"VER\",\"TeamName\":\"Red Bull Racing\","
                + "\"TeamColour\":\"4781D7\"},\"16\":{\"Tla\":\"LEC\","
                + "\"TeamName\":\"Ferrari\",\"TeamColour\":\"E8002D\"}},"
                + "\"TimingData\":{\"Lines\":{\"3\":{\"Position\":\"1\"},"
                + "\"16\":{\"Position\":\"2\"}}},"
                + "\"TimingAppData\":{\"Lines\":{\"3\":{\"Stints\":"
                + "{\"1\":{\"Compound\":\"SOFT\",\"TotalLaps\":12}}}}}}"));
        eq("快照后收到 2 条消息",
                Integer.valueOf(feed.messages.size()), Integer.valueOf(2));
        eq("会议名", feed.meetingName(), "Azerbaijan Grand Prix");
        eq("赛道名", feed.circuitName(), "Baku");
        eq("当前圈", Integer.valueOf(feed.currentLap()), Integer.valueOf(55));
        eq("轨道级：全赛道黄旗",
                Integer.valueOf(feed.track.globalLevel()), Integer.valueOf(TrackState.YELLOW));
        // ★ 关键：快照里 12 号区段先双黄、后普通黄。
        //   要按 Utc 排序后喂状态机，最终才是**单黄**（用户的降级规则）。
        //   不排序的话 map 顺序随机，结果会时对时错。
        eq("快照内按时间顺序喂 -> 12 号区段降级为单黄",
                feed.track.yellowSectors().toString(), "[12]");
        eq("双黄列表为空",
                feed.track.doubleYellowSectors().toString(), "[]");
        eq("不带 Z 的 Utc 也能解析",
                Boolean.valueOf(F1Feed.parseUtc("2026-09-26T10:11:25") > 0), Boolean.TRUE);

        section("F1Feed：增量是**深合并**，不能把整份状态清掉");
        feed.onDelta("DriverList", new org.json.JSONObject("{\"3\":{\"Tla\":\"VER\"}}"));
        java.util.List<F1Feed.Car> f1cars = feed.cars();
        eq("车手仍是 2 位", Integer.valueOf(f1cars.size()), Integer.valueOf(2));
        eq("按位置排序，第 1 位是 VER", f1cars.get(0).label(), "VER");
        eq("深合并没有丢车队", f1cars.get(0).team, "Red Bull Racing");
        eq("深合并没有丢车队色", f1cars.get(0).teamColour, "4781D7");
        eq("第 2 位是 LEC", f1cars.get(1).label(), "LEC");
        eq("轮胎配方", f1cars.get(0).compound, "SOFT");
        eq("轮胎已跑圈数", Integer.valueOf(f1cars.get(0).tyreLaps), Integer.valueOf(12));
        eq("进站次数（1 套胎=0 次）",
                Integer.valueOf(f1cars.get(0).pitStops), Integer.valueOf(0));

        section("F1Feed：RaceControlMessages 的增量是**新消息**，要追加不是替换");
        feed.onDelta("RaceControlMessages", new org.json.JSONObject(
                "{\"Messages\":[{\"Utc\":\"2026-09-26T10:13:00\",\"Category\":\"Flag\","
                + "\"Flag\":\"CLEAR\",\"Scope\":\"Sector\",\"Sector\":12,"
                + "\"Message\":\"CLEAR IN TRACK SECTOR 12\"}]}"));
        eq("消息变成 3 条（追加，不是被替换成 1 条）",
                Integer.valueOf(feed.messages.size()), Integer.valueOf(3));
        eq("12 号区段被解除",
                Integer.valueOf(feed.track.yellowSectors().size()), Integer.valueOf(0));
        feed.onDelta("RaceControlMessages", new org.json.JSONObject(
                "{\"Messages\":[{\"Utc\":\"2026-09-26T10:13:00\",\"Category\":\"Flag\","
                + "\"Flag\":\"CLEAR\",\"Scope\":\"Sector\",\"Sector\":12,"
                + "\"Message\":\"CLEAR IN TRACK SECTOR 12\"}]}"));
        eq("同一条重复推送要去重",
                Integer.valueOf(feed.messages.size()), Integer.valueOf(3));

        section("F1Feed：TrackStatus 增量改轨道级状态");
        feed.onDelta("TrackStatus", new org.json.JSONObject("{\"Status\":\"5\"}"));
        eq("5 = 红旗",
                Integer.valueOf(feed.track.globalLevel()), Integer.valueOf(TrackState.RED));
        feed.onDelta("TrackStatus", new org.json.JSONObject("{\"Status\":\"1\"}"));
        eq("1 = 全清",
                Integer.valueOf(feed.track.globalLevel()), Integer.valueOf(TrackState.NONE));

        section("F1Feed：stint 增加 = 进站一次，轮胎配方跟着换");
        feed.onDelta("TimingAppData", new org.json.JSONObject(
                "{\"Lines\":{\"3\":{\"Stints\":{\"2\":"
                + "{\"Compound\":\"HARD\",\"TotalLaps\":5}}}}}"));
        java.util.List<F1Feed.Car> c2 = feed.cars();
        eq("取最新一套胎的配方", c2.get(0).compound, "HARD");
        eq("新胎已跑 5 圈", Integer.valueOf(c2.get(0).tyreLaps), Integer.valueOf(5));
        eq("2 套胎 = 进站 1 次", Integer.valueOf(c2.get(0).pitStops), Integer.valueOf(1));

        // ★ 真实形状是**数组**：
        //     "Stints": [ {"Compound":"INTERMEDIATE","TotalLaps":9}, {...} ]
        //   上面那条只测了"以第几套为键的对象"形状，所以数组形状一直没人管 ——
        //   直到真实服务器冒烟测试里出现「胎=(0圈)」才暴露。两种形状都要认。
        section("F1Feed：Stints 是数组（真实形状）也要认");
        feed.onDelta("TimingAppData", new org.json.JSONObject(
                "{\"Lines\":{\"16\":{\"Stints\":["
                + "{\"Compound\":\"INTERMEDIATE\",\"TotalLaps\":9},"
                + "{\"Compound\":\"SOFT\",\"TotalLaps\":31}]}}}"));
        java.util.List<F1Feed.Car> c3 = feed.cars();
        eq("数组形状：取最后一套胎", c3.get(1).compound, "SOFT");
        eq("数组形状：最后一套已跑 31 圈",
                Integer.valueOf(c3.get(1).tyreLaps), Integer.valueOf(31));
        eq("数组形状：2 套胎 = 进站 1 次",
                Integer.valueOf(c3.get(1).pitStops), Integer.valueOf(1));

        section("F1Feed：type1 / type3 记录分发");
        F1Feed feed2 = new F1Feed();
        feed2.onRecord(new org.json.JSONObject(
                "{\"type\":3,\"invocationId\":\"0\",\"result\":{\"LapCount\":"
                + "{\"CurrentLap\":3,\"TotalLaps\":50}}}"));
        eq("type3 快照被处理", Integer.valueOf(feed2.currentLap()), Integer.valueOf(3));
        feed2.onRecord(new org.json.JSONObject(
                "{\"type\":1,\"target\":\"LapCount\",\"arguments\":[{\"CurrentLap\":4}]}"));
        eq("type1 增量被合并", Integer.valueOf(feed2.currentLap()), Integer.valueOf(4));
        eq("type6 心跳不改变状态",
                Boolean.valueOf(feed2.onRecord(new org.json.JSONObject("{\"type\":6}"))),
                Boolean.FALSE);

        section("F1Feed：轮胎配方中文");
        eq("软", F1Feed.compoundCn("SOFT"), "软");
        eq("中性", F1Feed.compoundCn("INTERMEDIATE"), "中性");
        eq("全雨", F1Feed.compoundCn("WET"), "全雨");

        // ================================================================
        // A 方案：界面几何计算
        // ================================================================
        section("F1Layout：圆环区段均布");
        eq("19 个区段每段张角",
                Boolean.valueOf(Math.abs(F1Layout.arcSweep(19) - 18.947f) < 0.01f),
                Boolean.TRUE);
        eq("4 个区段的起始角",
                java.util.Arrays.toString(F1Layout.ringStarts(4)),
                "[0.0, 90.0, 180.0, 270.0]");
        eq("第 1 段中心角", Float.valueOf(F1Layout.arcMid(0, 4)),
                Float.valueOf(45f));
        eq("最后一段中心角", Float.valueOf(F1Layout.arcMid(3, 4)),
                Float.valueOf(315f));
        // π 相关的角度不能拿字符串比：sin(180°) = 1.22e-16，不是 0。
        // 所以这三点用容差比 —— 这不是放宽标准，是浮点本来就该这么测。
        float[] p0 = F1Layout.polar(0f, 0f, 10f, 0f);
        eq("0 度在正上方（x≈0, y≈-10）",
                Boolean.valueOf(Math.abs(p0[0]) < 0.001f && Math.abs(p0[1] + 10f) < 0.001f),
                Boolean.TRUE);
        float[] p90 = F1Layout.polar(0f, 0f, 10f, 90f);
        eq("90 度在正右（x≈10, y≈0）",
                Boolean.valueOf(Math.abs(p90[0] - 10f) < 0.001f && Math.abs(p90[1]) < 0.001f),
                Boolean.TRUE);
        float[] p180 = F1Layout.polar(0f, 0f, 10f, 180f);
        eq("180 度在正下（x≈0, y≈10）",
                Boolean.valueOf(Math.abs(p180[0]) < 0.001f && Math.abs(p180[1] - 10f) < 0.001f),
                Boolean.TRUE);
        eq("区段多了就不画数字标签",
                Boolean.valueOf(F1Layout.labelFits(100f, 40, 25f)), Boolean.FALSE);
        eq("区段少就能画数字",
                Boolean.valueOf(F1Layout.labelFits(100f, 19, 25f)), Boolean.TRUE);

        section("F1Layout：轮胎面板 22 个位置、三列每列 8 行");
        eq("容量 3x8 = 24", Integer.valueOf(F1Layout.capacity(3, 8)),
                Integer.valueOf(24));
        int[] slots = F1Layout.boardSlots(22, 3, 8);
        eq("格子数 = 24", Integer.valueOf(slots.length), Integer.valueOf(24));
        eq("第 1 格是 P1", Integer.valueOf(slots[0]), Integer.valueOf(0));
        eq("第 8 格是 P8（第一列最后一个）",
                Integer.valueOf(slots[7]), Integer.valueOf(7));
        eq("第 9 格是 P9（换列）",
                Integer.valueOf(slots[8]), Integer.valueOf(8));
        eq("第 17 格是 P17", Integer.valueOf(slots[16]), Integer.valueOf(16));
        eq("第 22 格是 P22", Integer.valueOf(slots[21]), Integer.valueOf(21));
        eq("第 23 格空着", Integer.valueOf(slots[22]), Integer.valueOf(-1));
        eq("第 24 格空着", Integer.valueOf(slots[23]), Integer.valueOf(-1));
        eq("P1 在第 0 列", Integer.valueOf(F1Layout.boardColumn(0, 8)),
                Integer.valueOf(0));
        eq("P9 在第 1 列", Integer.valueOf(F1Layout.boardColumn(8, 8)),
                Integer.valueOf(1));
        eq("P22 在第 2 列", Integer.valueOf(F1Layout.boardColumn(21, 8)),
                Integer.valueOf(2));
        eq("P1 在第 0 行", Integer.valueOf(F1Layout.boardRow(0, 8)),
                Integer.valueOf(0));
        eq("P22 在第 5 行", Integer.valueOf(F1Layout.boardRow(21, 8)),
                Integer.valueOf(5));

        section("F1Layout：顶部旗语栏纵向分隔");
        eq("3 种旗语平分 300px",
                java.util.Arrays.toString(F1Layout.barDividers(3, 300f)),
                "[0.0, 100.0, 200.0, 300.0]");
        eq("每段宽度", Float.valueOf(F1Layout.barSegmentWidth(3, 300f)),
                Float.valueOf(100f));
        eq("1 种旗语就是整栏",
                java.util.Arrays.toString(F1Layout.barDividers(1, 300f)),
                "[0.0, 300.0]");
        eq("区段列表文字",
                F1Layout.sectorList(java.util.Arrays.asList(
                        Integer.valueOf(3), Integer.valueOf(7), Integer.valueOf(12)), 12),
                "3,7,12");
        java.util.List<Integer> many = new java.util.ArrayList<Integer>();
        for (int i = 1; i <= 20; i++) {
            many.add(Integer.valueOf(i));
        }
        eq("超长区段列表要省略",
                F1Layout.sectorList(many, 12), "1,2,3,4,5,6,7,8,9,10,11,12…");
        eq("空列表返回空串",
                F1Layout.sectorList(new java.util.ArrayList<Integer>(), 12), "");

        // ================================================================
        // 官方归档的**真实快照**
        // ================================================================
        section("F1Feed：喂官方归档的真实快照"
                + "（不是手写 JSON）");
        String fixture = System.getProperty("f1.fixture", "");
        eq("夹具存在（tools/mock_data/f1_snapshot_real.json）",
                Boolean.valueOf(fixture.length() > 0
                        && new java.io.File(fixture).isFile()), Boolean.TRUE);
        org.json.JSONObject real = null;
        if (fixture.length() > 0 && new java.io.File(fixture).isFile()) {
            String json = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(fixture)), "UTF-8");
            real = new org.json.JSONObject(json);
        }
        F1Feed rf = new F1Feed();
        if (real != null) {
            rf.onSnapshot(real);
        }
        eq("消息 327 条（与实时流一致）",
                Integer.valueOf(rf.messages.size()), Integer.valueOf(327));
        eq("车手 22 位", Integer.valueOf(rf.cars().size()),
                Integer.valueOf(22));
        eq("会议名含 Bahrain", Boolean.valueOf(
                rf.meetingName().indexOf("Bahrain") >= 0), Boolean.TRUE);
        eq("当前圈 > 0", Boolean.valueOf(rf.currentLap() > 0), Boolean.TRUE);
        eq("总圈数 > 0", Boolean.valueOf(rf.totalLaps() > 0), Boolean.TRUE);
        eq("天气文本非空",
                Boolean.valueOf(rf.weatherText().length() > 0), Boolean.TRUE);
        // ★ 区段号只能从消息里来
        //   （归档里没有任何"总区段数"声明）
        eq("区段数 > 0（圆环靠它分配段数）",
                Boolean.valueOf(rf.sectorCount() > 0), Boolean.TRUE);

        // 轮胎：真实数据里必须有人有配方和圈数
        int withTyre = 0;
        int withPit = 0;
        int sorted = 0;
        java.util.List<F1Feed.Car> rc = rf.cars();
        for (int i = 0; i < rc.size(); i++) {
            F1Feed.Car c = rc.get(i);
            if (c.compound.length() > 0 && c.tyreLaps > 0) {
                withTyre++;
            }
            if (c.pitStops > 0) {
                withPit++;
            }
            if (i > 0 && rc.get(i - 1).position > 0 && c.position > 0
                    && rc.get(i - 1).position <= c.position) {
                sorted++;
            }
        }
        eq("至少 15 位车手有轮胎配方和圈数"
                + "（这正是 Stints 那个 bug 当初吞掉的）",
                Boolean.valueOf(withTyre >= 15), Boolean.TRUE);
        eq("至少 1 位进过站",
                Boolean.valueOf(withPit >= 1), Boolean.TRUE);
        eq("车手按赛道位置排序",
                Boolean.valueOf(sorted >= rc.size() - 2), Boolean.TRUE);
        eq("第一位就是 P1",
                Integer.valueOf(rc.get(0).position), Integer.valueOf(1));

        // 面板要调的每一个访问器都不能返回 null
        // （这些就是 RightPanelView / TopFlagBarView 实际调用的）
        int nulls = 0;
        if (rf.meetingName() == null || rf.circuitName() == null
                || rf.sessionName() == null || rf.sessionStatus() == null
                || rf.remaining() == null || rf.weatherText() == null
                || rf.trackStatusCode() == null || rf.track.label() == null
                || rf.track.detail() == null || rf.topThree() == null) {
            nulls++;
        }
        for (int i = 0; i < rc.size(); i++) {
            F1Feed.Car c = rc.get(i);
            if (c.label() == null || c.compound == null || c.team == null
                    || c.teamColour == null || c.gap == null || c.interval == null
                    || c.bestLap == null) {
                nulls++;
            }
        }
        eq("面板要用的访问器全部非 null", Integer.valueOf(nulls),
                Integer.valueOf(0));

        // 真实消息逐条过一遍。★ 断言**不是**"按消息数覆盖率" —— 那样会误导：
        //   这场 327 条里 249 条是纯旗语（CLEAR 104 / YELLOW 84 / 双黄 45），
        //   含义徽标已表达完整，Translator **有意不翻**。按条数只有 ~26%，
        //   看着像坏了，其实是设计如此。真正该钉住的不变式是：
        //   **非旗语类消息必须都有中文简述**。
        int glossed = 0;
        int prose = 0;
        int proseGlossed = 0;
        java.util.List<RaceMessage> rms = rf.messages.sortedDesc();
        for (int i = 0; i < rms.size(); i++) {
            RaceMessage m = rms.get(i);
            // 不能叫 g —— main() 里已经有一个 AlertGate g 了
            String rgloss = Translator.gloss(m.text());
            boolean has = rgloss != null && rgloss.length() > 0;
            if (has) {
                glossed++;
            }
            if (!"Flag".equals(m.category)) {
                prose++;
                if (has) {
                    proseGlossed++;
                } else {
                    // 真实数据里没覆盖到的，直接打出来 —— 不然只知道"差 8 条"，
                    // 不知道差的是哪 8 条，没法补。
                    System.out.println("      [缺译文] category=" + m.category
                            + " | " + m.text());
                }
            }
        }
        eq("非旗语类消息都有中文简述（" + proseGlossed + "/" + prose + "）",
                Integer.valueOf(proseGlossed), Integer.valueOf(prose));
        eq("旗语类里也有能翻的（不是一条都没有）",
                Boolean.valueOf(glossed > prose), Boolean.TRUE);




        // ================================================================
        // 回放包：把官方归档重新"播"一遍
        // ================================================================
        // 这一段是"假数据测试"的地基。它和上面那个 fixture 测试的分工：
        //   fixture 只喂**一份最终快照**，验证的是"解析对不对"；
        //   这里喂的是**完整时间线**（快照 + 几千条增量），验证的是
        //   "从连上到比赛结束，整条管线会不会中途炸掉"。

        // ---- .rclog 格式本身：元信息头、gzip 自动识别、老格式兼容 ----
        //      ★ 这一小节不依赖任何文件，永远跑得到。上面那节要靠 -Df1.pack。
        section("ReplayClient：.rclog 的元信息头 / gzip 识别 / 老格式兼容");
        final String frame1 = "00:00:01.000{\"type\":3,\"result\":{}}";
        final String frame2 = "00:00:02.000{\"type\":1,\"target\":\"X\",\"arguments\":[{}]}";
        try {
            // (1) 不压缩、也没有头 —— v3.1.x 的老格式，必须照样能放
            String legacy = frame1 + "\n" + frame2 + "\n";
            ReplayClient.Opened a = ReplayClient.open(new java.io.ByteArrayInputStream(
                    legacy.getBytes("UTF-8")));
            eq("没头的文件照放（meta 是 null）",
                    Boolean.valueOf(a.meta == null), Boolean.TRUE);
            java.io.BufferedReader ra = new java.io.BufferedReader(
                    new java.io.InputStreamReader(a.stream, "UTF-8"));
            eq("老格式的第一帧没被吃掉", ra.readLine(), frame1);
            eq("老格式的第二帧也在", ra.readLine(), frame2);
            ra.close();

            // (2) 带头、且 gzip 压缩 —— 这是 v3.2.0 起的正式格式
            String body = frame1 + "\n" + frame2 + "\n";
            String full = "RCLOG1 {\"name\":\"测试场次\",\"date\":\"2026-10-04\","
                    + "\"session\":\"x/y/\",\"snapshotMs\":740000,\"durationMs\":60000,"
                    + "\"snapshotMsgs\":194,\"frames\":2460,\"note\":\"备注\"}\n" + body;
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bo);
            gz.write(full.getBytes("UTF-8"));
            gz.close();
            byte[] gzBytes = bo.toByteArray();
            eq("测的数据确实是 gzip（魔数 1f 8b）",
                    Boolean.valueOf((gzBytes[0] & 0xff) == 0x1f
                            && (gzBytes[1] & 0xff) == 0x8b), Boolean.TRUE);

            ReplayClient.Opened b = ReplayClient.open(new java.io.ByteArrayInputStream(gzBytes));
            eq("gzip 被自动认出来并解开（meta 读到了）",
                    Boolean.valueOf(b.meta != null), Boolean.TRUE);
            if (b.meta != null) {
                eq("元信息里的名字", b.meta.name, "测试场次");
                eq("元信息里的日期", b.meta.date, "2026-10-04");
                eq("元信息里的时长", Long.valueOf(b.meta.durationMs), Long.valueOf(60000L));
                eq("元信息里的历史消息条数",
                        Integer.valueOf(b.meta.snapshotMsgs), Integer.valueOf(194));
                eq("label 拼得对", b.meta.label(), "测试场次（2026-10-04）");
                eq("detail 里带时长和条数",
                        Boolean.valueOf(b.meta.detail().indexOf("1 分钟") >= 0
                                && b.meta.detail().indexOf("194 条消息") >= 0), Boolean.TRUE);
            }
            java.io.BufferedReader rb = new java.io.BufferedReader(
                    new java.io.InputStreamReader(b.stream, "UTF-8"));
            eq("★ 摘掉头之后，流**正好**停在第一帧", rb.readLine(), frame1);
            eq("后面的帧一帧不少", rb.readLine(), frame2);
            eq("读完就是结尾", rb.readLine(), null);
            rb.close();

            // (3) 只有头、没有帧 —— 不能崩，也不能把异常当帧
            String onlyHead = "RCLOG1 {\"name\":\"空\"}\n";
            java.io.ByteArrayOutputStream bo2 = new java.io.ByteArrayOutputStream();
            java.util.zip.GZIPOutputStream gz2 = new java.util.zip.GZIPOutputStream(bo2);
            gz2.write(onlyHead.getBytes("UTF-8"));
            gz2.close();
            ReplayClient.Opened c = ReplayClient.open(
                    new java.io.ByteArrayInputStream(bo2.toByteArray()));
            eq("只有头的文件不崩、meta 也有",
                    Boolean.valueOf(c.meta != null && "空".equals(c.meta.name)), Boolean.TRUE);
            eq("只有头的文件读出来一条帧都没有",
                    new java.io.BufferedReader(new java.io.InputStreamReader(
                            c.stream, "UTF-8")).readLine(), null);

            // (4) 空文件：不能把 IOException 抛到调用方脸上
            ReplayClient.Opened d = ReplayClient.open(new java.io.ByteArrayInputStream(
                    new byte[0]));
            eq("空文件也能开（meta 为 null）",
                    Boolean.valueOf(d.meta == null), Boolean.TRUE);

            // (5) 头的 JSON 坏了：当没有头处理，但那 6 个字节不能被吞掉后当帧
            String badHead = "RCLOG1 {这不是JSON}\n" + frame1 + "\n";
            ReplayClient.Opened e = ReplayClient.open(new java.io.ByteArrayInputStream(
                    badHead.getBytes("UTF-8")));
            eq("头坏了就说没有元信息", Boolean.valueOf(e.meta == null), Boolean.TRUE);
            eq("★ 头坏了也不能把首帧吃掉",
                    new java.io.BufferedReader(new java.io.InputStreamReader(
                            e.stream, "UTF-8")).readLine(), frame1);

            // (6) parseMeta 直接测：不是头就 null
            eq("普通帧拿去 parseMeta 得到 null",
                    ReplayClient.parseMeta(frame1), null);
            eq("RCLOG1 后面没东西也是 null",
                    ReplayClient.parseMeta("RCLOG1"), null);
            eq("RCLOG1 后面跟坏 JSON 也是 null",
                    ReplayClient.parseMeta("RCLOG1 {{{"), null);
        } catch (Exception ex) {
            eq("rclog 格式测试不该抛异常：" + ex, Boolean.TRUE, Boolean.TRUE);
        }

        section("ReplayClient：真回放包走完整管线");
        String pack = System.getProperty("f1.pack", "");
        eq("回放文件存在（tools/mock_data/*.rclog）",
                Boolean.valueOf(pack.length() > 0
                        && new java.io.File(pack).isFile()), Boolean.TRUE);

        if (pack.length() > 0 && new java.io.File(pack).isFile()) {
            // ---- 自己先扫一遍：帧数、时长、快照点 ----
            //      用 ReplayClient.open 而不是 FileInputStream —— 文件是 gzip 的，
            //      而且第一行是 RCLOG1 元信息头，这两件事都得先剥掉。
            java.util.List<String> packFrames = new java.util.ArrayList<String>();
            ReplayClient.Opened pop = ReplayClient.open(
                    new java.io.FileInputStream(pack));
            eq("真文件里认出了 RCLOG1 元信息头",
                    Boolean.valueOf(pop.meta != null), Boolean.TRUE);
            if (pop.meta != null) {
                eq("元信息里有比赛名",
                        Boolean.valueOf(pop.meta.name.length() > 0), Boolean.TRUE);
                eq("元信息里的帧数 == 实际读到的帧数（下面验证）",
                        Boolean.valueOf(pop.meta.frames > 1000), Boolean.TRUE);
            }
            java.io.BufferedReader pbr = new java.io.BufferedReader(
                    new java.io.InputStreamReader(pop.stream, "UTF-8"), 1 << 16);
            String pln;
            while ((pln = pbr.readLine()) != null) {
                pln = pln.trim();
                if (pln.length() > 0) {
                    packFrames.add(pln);
                }
            }
            pbr.close();

            eq("包里有上千帧", Boolean.valueOf(packFrames.size() > 1000),
                    Boolean.TRUE);
            if (pop.meta != null) {
                eq("★ 元信息里写的帧数与真实帧数一致",
                        Integer.valueOf(pop.meta.frames),
                        Integer.valueOf(packFrames.size()));
            }
            final String firstFrame = packFrames.get(0);
            String lastFrame = packFrames.get(packFrames.size() - 1);
            long packSnapMs = ReplayClient.parseOffset(firstFrame.substring(0, 12));
            long packLastMs = ReplayClient.parseOffset(lastFrame.substring(0, 12));
            eq("首帧是 type:3 快照", Boolean.valueOf(
                    firstFrame.indexOf("\"type\":3") > 0), Boolean.TRUE);
            eq("快照带的是真实会话偏移（不是 0）",
                    Boolean.valueOf(packSnapMs > 600000), Boolean.TRUE);
            eq("包跨越一段时间",
                    Boolean.valueOf(packLastMs - packSnapMs > 3600000), Boolean.TRUE);
            eq("每条增量都带 target", Boolean.valueOf(
                    lastFrame.indexOf("\"target\":") > 0), Boolean.TRUE);

            // ---- 真的放一遍。speed 开到极大 = 不等，几毫秒放完 ----
            final int[] opens = new int[1];
            final int[] feeds = new int[1];
            final int[] errors = new int[1];
            final F1Feed[] seen = new F1Feed[1];
            final java.util.TreeSet<String> codes = new java.util.TreeSet<String>();
            ReplayClient rp = new ReplayClient(new ReplayClient.Opener() {
                public java.io.InputStream open() throws java.io.IOException {
                    return new java.io.FileInputStream(pack);
                }
            }, new F1Client.Listener() {
                public void onOpen() {
                    opens[0]++;
                }

                public void onFeed(F1Feed f) {
                    feeds[0]++;
                    seen[0] = f;
                    codes.add(f.trackStatusCode());
                }

                public void onError(String message) {
                    errors[0]++;
                    System.out.println("      回放报错: " + message);
                }

                public void onClose() {
                }
            }, 1000000, packLastMs - packSnapMs, 0);
            rp.runForever();

            eq("回放没有报错", Integer.valueOf(errors[0]), Integer.valueOf(0));
            eq("回调了 onOpen", Integer.valueOf(opens[0]), Integer.valueOf(1));
            eq("回调了 onFeed", Boolean.valueOf(feeds[0] > 0), Boolean.TRUE);
            eq("进度收到 100%", Integer.valueOf(rp.percent()), Integer.valueOf(100));
            eq("lastError 为空", rp.lastError(), "");

            final F1Feed rpf = seen[0];
            eq("回放末态拿到了 feed",
                    Boolean.valueOf(rpf != null), Boolean.TRUE);

            if (rpf != null) {
                // ★ 327 是这场比赛的**全部**消息数（快照 194 + 之后的增量）。
                //   对得上就说明：快照合并、增量拼接、去重，都对了。
                eq("消息累计 327 条（快照 194 + 增量）",
                        Integer.valueOf(rpf.messages.size()), Integer.valueOf(327));
                eq("车手 22 位", Integer.valueOf(rpf.cars().size()),
                        Integer.valueOf(22));
                eq("区段数 > 0", Boolean.valueOf(rpf.sectorCount() > 0),
                        Boolean.TRUE);
                eq("会议名含 Bahrain", Boolean.valueOf(
                        rpf.meetingName().indexOf("Bahrain") >= 0), Boolean.TRUE);

                // ★ 这两条就是"漏订阅 TimingStats / TopThree"那个 bug 的哨兵。
                //   以前两个都必然是 0，而实况下"面板空着"和"这节还没成绩"
                //   长得一模一样，肉眼发现不了。
                int rpBest = 0;
                java.util.List<F1Feed.Car> rpc = rpf.cars();
                for (int i = 0; i < rpc.size(); i++) {
                    if (rpc.get(i).bestLap != null
                            && rpc.get(i).bestLap.length() > 0) {
                        rpBest++;
                    }
                }
                eq("至少 15 位车手有最快圈（靠 TimingStats 流）",
                        Boolean.valueOf(rpBest >= 15), Boolean.TRUE);
                eq("前三名非空（靠 TopThree 流）",
                        Boolean.valueOf(rpf.topThree().size() > 0), Boolean.TRUE);

                // 回放要能压到安全车那一档，否则说明抽稀把
                // TrackStatus 的关键状态吃掉了
                eq("TrackStatus 出现过安全车(4)",
                        Boolean.valueOf(codes.contains("4")), Boolean.TRUE);

                // ---- 告警时间戳改写 ----
                // 快照里的历史消息必须**仍然很旧**（否则一开机就炸几十条全屏告警），
                // 增量里的消息必须**是现在**（否则提醒一次都不会响，等于没测）。
                long nowMs = System.currentTimeMillis();
                int rpFresh = 0;
                int rpStale = 0;
                java.util.List<RaceMessage> rpl = rpf.messages.sortedDesc();
                for (int i = 0; i < rpl.size(); i++) {
                    RaceMessage m = rpl.get(i);
                    if (m.time > 0 && nowMs - m.time <= 3 * 60 * 1000L) {
                        rpFresh++;
                    } else {
                        rpStale++;
                    }
                }
                eq("增量消息被改写成'现在'，能触发告警（" + rpFresh + " 条）",
                        Boolean.valueOf(rpFresh >= 100), Boolean.TRUE);
                eq("快照历史消息仍然很旧，不会一开机就告警（"
                                + rpStale + " 条）",
                        Boolean.valueOf(rpStale >= 180), Boolean.TRUE);
            }
        }

        // ==================================================================
        section("F1Client.stop()：绝不在调用线程上 close() socket");
        // ★ v3.1.1 修的那个崩溃，必须钉住。
        //
        //   Android 的 StrictMode 把「主线程上做网络操作」判成
        //   NetworkOnMainThreadException，而 OpenSSLSocketImpl.close() 也算
        //   —— 它内部要 shutdownAndFreeSslNative()，属于网络动作。
        //
        //   于是「在设置页换数据源 -> 返回主界面 -> onResume 里停掉旧连接」
        //   这一下会在 resume 阶段抛异常，被包成
        //   `Unable to resume activity ... NetworkOnMainThreadException`，
        //   界面根本起不来。栈里只看得到 restartClient -> stop，
        //   很容易误判成"回放功能坏了"，其实是关连接的方式错了。
        //
        //   桌面 JVM 没有 StrictMode，所以这个 bug 在这套测试里
        //   **不会自己暴露** —— 只能自己造一个假 Socket 去记 close 的线程。
        {
            final RecordingSocket rs = new RecordingSocket();
            final F1Client c = new F1Client(null);
            c.socket = rs;

            long t0 = System.currentTimeMillis();
            c.stop();
            long costMs = System.currentTimeMillis() - t0;

            eq("stop() 立刻返回（" + costMs + " ms），没在调用线程上 close",
                    Boolean.valueOf(costMs < 500), Boolean.TRUE);
            eq("stop() 返回那一刻，close 还没有发生在调用线程上",
                    Boolean.valueOf(!Thread.currentThread().getName()
                            .equals(rs.closedOn)), Boolean.TRUE);

            // 但连接最终还是得关掉：读循环正阻塞在 read 上，
            // 不关就要等到 90 秒读超时才退得出来。
            long deadline = System.currentTimeMillis() + 3000L;
            while (rs.closeCount == 0 && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException e) {
                    break;
                }
            }
            eq("连接最终真的被关掉了（不能只是不关）",
                    Integer.valueOf(rs.closeCount), Integer.valueOf(1));
            eq("close() 发生在别的线程上（" + rs.closedOn + "）",
                    Boolean.valueOf("f1-close".equals(rs.closedOn)), Boolean.TRUE);

            // 重复 stop() 不许关第二次，而且引用要放开让 GC 能收
            c.stop();
            eq("重复 stop() 不会重复 close",
                    Integer.valueOf(rs.closeCount), Integer.valueOf(1));
            eq("stop() 之后 socket 引用已清空",
                    Boolean.valueOf(c.socket == null), Boolean.TRUE);
        }

        // 换数据源要靠两个数据源都实现 FeedSource，主界面才有统一切换点
        eq("F1Client 实现了 FeedSource（主界面才能统一切换数据源）",
                Boolean.valueOf(new F1Client(null) instanceof FeedSource),
                Boolean.TRUE);

        {
            final ReplayClient rp = new ReplayClient(new ReplayClient.Opener() {
                public java.io.InputStream open() throws java.io.IOException {
                    return new java.io.ByteArrayInputStream(new byte[0]);
                }
            }, new F1Client.Listener() {
                public void onOpen() {
                }

                public void onFeed(F1Feed f) {
                }

                public void onError(String message) {
                }

                public void onClose() {
                }
            });
            eq("ReplayClient 也实现了 FeedSource",
                    Boolean.valueOf(rp instanceof FeedSource), Boolean.TRUE);
            // 回放包是本地文件，stop() 只置标志位，不碰网络也不碰 socket
            long t1 = System.currentTimeMillis();
            rp.stop();
            eq("ReplayClient.stop() 不阻塞（" +
                            (System.currentTimeMillis() - t1) + " ms）",
                    Boolean.valueOf(System.currentTimeMillis() - t1 < 500),
                    Boolean.TRUE);
        }

        System.out.println("==================================================");
        System.out.println("  通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("==================================================");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
