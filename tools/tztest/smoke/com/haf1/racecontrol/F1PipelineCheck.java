package com.haf1.racecontrol;

import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * 在桌面上**完整模拟 App 的数据管线**，看列表到底会不会是空的。
 *
 * 用户装上 App 后「完全没东西」，但同一个 F1Client 在桌面上能取到 327 条。
 * 所以要逐段对照：解析 -> 过滤（Prefs.accept）-> 分类 -> 翻译，
 * 看哪一段把消息吃掉了。
 *
 * 用法（由 dsh/tools/f1_pipeline_check.py 调用）：
 *     java com.haf1.racecontrol.F1PipelineCheck <fixture.json> [<快照|实时秒数>]
 */
public class F1PipelineCheck {

    public static void main(String[] args) throws Exception {
        String fixture = args.length > 0 ? args[0] : "";
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 0;

        F1Feed feed = new F1Feed();
        if (seconds > 0) {
            // 真连一次官方流，走的就是 App 里那条路
            System.out.println("== 真连官方流 " + seconds + " 秒");
            final F1Client c = new F1Client(null);
            final F1Feed live = c.feed();
            Thread t = new Thread(new Runnable() {
                public void run() {
                    c.runForever();
                }
            });
            t.setDaemon(true);
            t.start();
            long end = System.currentTimeMillis() + seconds * 1000L;
            while (System.currentTimeMillis() < end) {
                Thread.sleep(300);
            }
            c.stop();
            Thread.sleep(300);
            feed = live;
        } else if (fixture.length() > 0) {
            System.out.println("== 用夹具快照（不走网络）");
            String json = new String(Files.readAllBytes(Paths.get(fixture)), "UTF-8");
            feed.onSnapshot(new JSONObject(json));
        }

        System.out.println();
        System.out.println("1) 解析：消息 " + feed.messages.size()
                + " 条，车手 " + feed.cars().size()
                + " 位，会议「" + feed.meetingName() + "」");

        // 2) 过滤：App 里只有通过 accept() 的才进列表
        Prefs p = new Prefs();          // 等于全新安装的默认值
        System.out.println();
        System.out.println("2) 过滤：noiseFilterEnabled=" + p.noiseFilterEnabled
                + "  noiseHideBlue=" + p.noiseHideBlue
                + "  noiseHideClear=" + p.noiseHideClear
                + "  noiseHideDeleted=" + p.noiseHideDeleted
                + "  carFilter=「" + p.carFilter + "」");

        List<RaceMessage> all = feed.messages.sortedDesc();
        int pass = 0;
        for (int i = 0; i < all.size(); i++) {
            if (p.accept(all.get(i))) {
                pass++;
            }
        }
        System.out.println("   通过过滤（会进列表）：" + pass + " / " + all.size());

        // 3) 渲染：跟 RowAdapter 一样的取值方式
        System.out.println();
        System.out.println("3) 列表前 10 行（App 里会长这样）：");
        int shown = 0;
        for (int i = 0; i < all.size() && shown < 10; i++) {
            RaceMessage m = all.get(i);
            if (!p.accept(m)) {
                continue;
            }
            String kind = Classifier.kind(m);
            String g = Translator.gloss(m.text());
            shown++;
            System.out.println("   [" + Classifier.label(kind) + "] "
                    + (g != null && g.length() > 0 ? g + "   ← " + m.text() : m.text()));
        }
        if (shown == 0) {
            System.out.println("   （空！列表里一条都没有）");
        }

        System.out.println();
        System.out.println("4) 旗语栏会显示：");
        List<String> kinds = feed.track.presentKinds();
        if (kinds.isEmpty()) {
            System.out.println("   「无旗语」（track==null 时是「等待数据」）");
        } else {
            for (int i = 0; i < kinds.size(); i++) {
                System.out.println("   " + Classifier.label(kinds.get(i)) + " "
                        + feed.track.sectorsOf(kinds.get(i)));
            }
        }

        System.out.println();
        System.out.println("5) 状态行文字（App 顶部那条）：");
        System.out.println("   ⏱ " + feed.meetingName() + " " + feed.sessionName()
                + "  " + feed.messages.size() + " 条  第 "
                + feed.currentLap() + "/" + feed.totalLaps() + " 圈");

        System.out.println();
        System.out.println(pass > 0 ? "PIPELINE_OK" : "PIPELINE_EMPTY");
    }
}
