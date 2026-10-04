package com.haf1.racecontrol;

/**
 * 拿**真实的 F1Client 产品代码**连一次官方流，看能不能收到数据。
 *
 * 为什么值得单独跑：单测只能证明"合并逻辑对"，证明不了"协议实现对"。
 * 而协议（negotiate / 握手 / 分片 / 心跳）恰恰是最容易写错、且错了
 * 只在真机上才暴露的部分。所以这里把 F1Client 编出来，直接连官方服务器。
 *
 * 只在联网时跑（不属于 run_all_tests 的离线套件）。
 * 成功时最后打印 SMOKE_OK。
 */
public class F1Smoke {

    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 35;
        final F1Client client = new F1Client(new F1Client.Listener() {
            private int feeds = 0;

            public void onOpen() {
                System.out.println("  [OK] 握手上完成并订阅");
            }

            public void onFeed(F1Feed feed) {
                feeds++;
                if (feeds <= 3 || feeds % 20 == 0) {
                    System.out.println("  [feed #" + feeds + "] 消息 "
                            + feed.messages.size() + " 条, 轨道级 "
                            + feed.track.globalLevel() + ", 车手 "
                            + feed.cars().size() + ", 圈 " + feed.currentLap());
                }
            }

            public void onError(String message) {
                System.out.println("  [ERR] " + message);
            }

            public void onClose() {
                System.out.println("  [close]");
            }
        });

        Thread t = new Thread(new Runnable() {
            public void run() {
                client.runForever();
            }
        });
        t.setDaemon(true);
        t.start();

        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
        }
        client.stop();
        Thread.sleep(500);

        F1Feed f = client.feed();
        System.out.println();
        System.out.println("================ 结果 ================");
        System.out.println("  消息条数   : " + f.messages.size());
        System.out.println("  会议 / 赛道: " + f.meetingName() + " / " + f.circuitName());
        System.out.println("  环节 / 状态: " + f.sessionName() + " / " + f.sessionStatus());
        System.out.println("  圈数       : " + f.currentLap() + " / " + f.totalLaps());
        System.out.println("  轨道级状态 : " + f.track.globalLevel()
                + "（状态码 " + f.trackStatusCode() + "）");
        System.out.println("  双黄区段   : " + f.track.doubleYellowSectors());
        System.out.println("  黄旗区段   : " + f.track.yellowSectors());
        System.out.println("  车手数     : " + f.cars().size());
        java.util.List<F1Feed.Car> cars = f.cars();
        for (int i = 0; i < Math.min(3, cars.size()); i++) {
            F1Feed.Car c = cars.get(i);
            System.out.println("    P" + c.position + " " + c.label()
                    + " " + c.team + " 胎=" + F1Feed.compoundCn(c.compound)
                    + "(" + c.tyreLaps + "圈) 进站=" + c.pitStops
                    + (c.inPit ? " 在维修区" : ""));
        }
        java.util.List<RaceMessage> msgs = f.messages.sortedDesc();
        if (!msgs.isEmpty()) {
            System.out.println("  最新一条   : " + msgs.get(0).text());
            System.out.println("  中文简述   : " + Translator.gloss(msgs.get(0).text()));
        }
        System.out.println("  最后错误   : " + client.lastError());
        System.out.println(f.messages.size() > 0 ? "SMOKE_OK" : "SMOKE_FAIL");
    }
}
