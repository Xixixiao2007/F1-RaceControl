package com.haf1.racecontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 右侧面板 —— 用右侧窄框图标切换 6 屏（用户定稿的方案）。
 *
 *   0 赛道图   圆环 + 各区段旗语颜色（拿不到真形状时的降级方案）
 *   1 轮胎进站 22 个位置、三列每列 8 行、按赛道位置
 *   2 成绩榜   名次 + 车手 + 差距/间隔 + 轮胎 + 进站
 *   3 天气     气温/赛道温/湿度/风/雨 + 全赛道状态
 *   4 最快圈   个人最快圈排行
 *   5 环节     会议/赛道/环节/状态/剩余/圈数 + 前三名
 *
 * ## 关于圆环的段数
 * 段数取「本场出现过的最大区段号」（{@link F1Feed#sectorCount()}），
 * **不依赖任何外部数据表**。原因：官方没有任何文件写出这个数（FIA 每站
 * 55 份文档都查过，编号只画在赛道图上），而硬编码 24 站的表既难维护、
 * 又容易错。区段号只会随消息出现，所以它出现多少就是多少。
 * 开场时环上段数少，随着更多区段被出示自动补齐。
 */
public class RightPanelView extends View {

    public static final int MODE_TRACK = 0;
    public static final int MODE_TYRES = 1;
    public static final int MODE_STANDINGS = 2;
    public static final int MODE_WEATHER = 3;
    public static final int MODE_FASTEST = 4;
    public static final int MODE_SESSION = 5;
    public static final int MODE_COUNT = 6;

    public static final String[] MODE_NAMES = {
            "赛道图", "轮胎", "成绩", "天气", "最快圈", "环节",
    };
    /** 窄框图标用的单字符（不依赖任何字体资源）。 */
    public static final String[] MODE_ICONS = {
            "◎", "◍", "≡", "☂", "⏱", "ⓘ",
    };

    /** 用户要求预留的席位：2026 赛季是 22 辆车。 */
    public static final int SEATS = 22;
    public static final int TYRE_COLUMNS = 3;
    public static final int TYRE_ROWS = 8;

    private F1Feed feed;
    private int mode = MODE_TRACK;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 圆心那个色块（全赛道旗语的底色）。 */
    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);

    public RightPanelView(Context c) {
        super(c);
        arc.setStyle(Paint.Style.STROKE);
        line.setColor(0x22000000);
        line.setStrokeWidth(dp(1));
    }

    public void setFeed(F1Feed f) {
        this.feed = f;
        invalidate();
    }

    public void setMode(int m) {
        if (m < 0 || m >= MODE_COUNT || m == mode) {
            return;
        }
        mode = m;
        invalidate();
    }

    public int getMode() {
        return mode;
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void txt(Canvas c, String s, float x, float y, float sizeDp,
                     int color, Paint.Align align, boolean bold) {
        p.setColor(color);
        p.setTextSize(dp(sizeDp));
        p.setTextAlign(align);
        p.setFakeBoldText(bold);
        c.drawText(s, x, y, p);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        c.drawColor(0xFFFAFAFA);
        if (feed == null) {
            txt(c, "等待数据…", getWidth() / 2f, getHeight() / 2f,
                    14, 0xFF90A4AE, Paint.Align.CENTER, false);
            return;
        }
        switch (mode) {
            case MODE_TYRES:
                drawTyres(c);
                break;
            case MODE_STANDINGS:
                drawStandings(c);
                break;
            case MODE_WEATHER:
                drawWeather(c);
                break;
            case MODE_FASTEST:
                drawFastest(c);
                break;
            case MODE_SESSION:
                drawSession(c);
                break;
            default:
                drawTrack(c);
        }
    }

    // ------------------------------------------------------------------
    // 0 赛道图：圆环
    // ------------------------------------------------------------------

    private void drawTrack(Canvas c) {
        int n = feed.sectorCount();
        float w = getWidth();
        float h = getHeight();
        if (n <= 0) {
            txt(c, "本场还没有区段消息", w / 2f, h / 2f, 13,
                    0xFF90A4AE, Paint.Align.CENTER, false);
            return;
        }
        float cx = w / 2f;
        float cy = h / 2f;
        float rOuter = Math.min(w, h) / 2f - dp(26);
        if (rOuter < dp(30)) {
            return;
        }
        float band = Math.max(dp(10), rOuter * 0.30f);
        float rMid = rOuter - band / 2f;
        float rIn = rMid - band / 2f;          // 圈内可用半径（放全赛道旗语）
        RectF oval = new RectF(cx - rMid, cy - rMid, cx + rMid, cy + rMid);
        arc.setStrokeWidth(band);

        float sweep = F1Layout.arcSweep(n);
        List<Integer> dys = feed.track.doubleYellowSectors();
        List<Integer> ys = feed.track.yellowSectors();
        boolean numbered = F1Layout.labelFits(rMid, n, dp(15));

        for (int i = 0; i < n; i++) {
            int sec = i + 1;
            // ★ 圈上**只画区段级**旗语（单黄 / 双黄）。
            //   老代码还按 global 整圈染色，而官方 TrackStatus 的 "2"(黄旗) 在
            //   只有单个区段黄旗时也会发 → global=YELLOW → 整圈黄，看起来就是
            //   全赛道黄旗（用户报的"单黄旗会全赛道显示"）。
            //   规则：全赛道旗语放到**圈内**（下面圆心那块），圈上只表达区段。
            arc.setColor(Classifier.ringSectorColour(sec, dys, ys));
            // ★ Canvas 的 0 度在 3 点方向，本类的 0 度在 12 点 —— 要减 90。
            c.drawArc(oval, F1Layout.ringStarts(n)[i] - 90f, sweep - 0.8f,
                    false, arc);
            if (numbered) {
                float[] pt = F1Layout.labelPoint(cx, cy, rMid - band / 2f,
                        rMid + band / 2f, i, n);
                txt(c, String.valueOf(sec), pt[0], pt[1] + dp(4), 10,
                        Color.WHITE, Paint.Align.CENTER, true);
            }
        }

        // 圆心：**全赛道**旗语（红旗 / 安全车 / VSC / 全赛道黄 / 绿旗）。
        // 和桌面版一致：填一块底色 + 一行大字 + 一行小字。
        disc.setColor(feed.track.color());
        c.drawCircle(cx, cy, rIn * 0.92f, disc);
        int fg = feed.track.textColor();
        txt(c, feed.track.label(), cx, cy - dp(2), 16, fg, Paint.Align.CENTER, true);
        String sub = feed.track.detail();
        if (sub.length() > 0 && sub.length() <= 18) {
            // 副标题用同色但半透明，免得在浅底上分不清主次
            txt(c, sub, cx, cy + dp(14), 11, (fg & 0x00FFFFFF) | 0xB0000000,
                    Paint.Align.CENTER, false);
        }
        txt(c, n + " 个区段", w / 2f, h - dp(6), 10, 0xFF90A4AE,
                Paint.Align.CENTER, false);
    }

    // 区段配色统一走 Classifier.ringSectorColour（纯函数，能桌面单测；
    // 而且它**不接受**全赛道状态参数 —— 见那里的注释，整圈染色就是这么错的）。

    // ------------------------------------------------------------------
    // 1 轮胎 / 进站：22 位、三列每列 8 行、按赛道位置
    // ------------------------------------------------------------------

    private void drawTyres(Canvas c) {
        List<F1Feed.Car> cars = feed.cars();
        int[] slots = F1Layout.boardSlots(SEATS, TYRE_COLUMNS, TYRE_ROWS);
        float cw = getWidth() / (float) TYRE_COLUMNS;
        float ch = getHeight() / (float) TYRE_ROWS;
        float pad = dp(2);

        for (int i = 0; i < slots.length; i++) {
            int col = F1Layout.boardColumn(i, TYRE_ROWS);
            int row = F1Layout.boardRow(i, TYRE_ROWS);
            float x = col * cw;
            float y = row * ch;
            RectF r = new RectF(x + pad, y + pad, x + cw - pad, y + ch - pad);

            int idx = slots[i];
            F1Feed.Car car = (idx >= 0 && idx < cars.size()) ? cars.get(idx) : null;
            p.setStyle(Paint.Style.FILL);
            if (car == null) {
                p.setColor(0xFFF0F0F0);
                c.drawRect(r, p);
                continue;
            }
            p.setColor(car.inPit ? 0xFFFFF3E0 : Color.WHITE);
            c.drawRect(r, p);
            // 左侧车队色条
            if (car.teamColour != null && car.teamColour.length() >= 6) {
                try {
                    p.setColor(0xFF000000 | (int) Long.parseLong(car.teamColour, 16));
                    c.drawRect(r.left, r.top, r.left + dp(3), r.bottom, p);
                } catch (Exception ignored) {
                    // 颜色串异常就跳过色条，不值得中断绘制
                }
            }
            c.drawLine(r.left, r.bottom, r.right, r.bottom, line);

            float tx = r.left + dp(6);
            txt(c, String.valueOf(car.position > 0 ? car.position : idx + 1),
                    tx, r.top + dp(12), 11, 0xFF37474F, Paint.Align.LEFT, true);
            txt(c, car.label(), tx + dp(14), r.top + dp(12), 11,
                    0xFF212121, Paint.Align.LEFT, true);
            // 轮胎：配方首字 + 圈数
            String tyre = F1Feed.compoundCn(car.compound);
            if (tyre.length() == 0) {
                tyre = "?";
            }
            txt(c, tyre + " " + car.tyreLaps, tx, r.top + dp(24), 9,
                    0xFF607D8B, Paint.Align.LEFT, false);
            if (car.pitStops > 0) {
                txt(c, "进" + car.pitStops, r.right - dp(4), r.top + dp(24), 9,
                        0xFF8D6E63, Paint.Align.RIGHT, false);
            }
            if (car.retired) {
                txt(c, "退赛", r.right - dp(4), r.top + dp(12), 9,
                        0xFFD32F2F, Paint.Align.RIGHT, true);
            } else if (car.inPit) {
                txt(c, "PIT", r.right - dp(4), r.top + dp(12), 9,
                        0xFFF57C00, Paint.Align.RIGHT, true);
            }
            p.setStyle(Paint.Style.STROKE);
        }
    }

    // ------------------------------------------------------------------
    // 2 成绩榜
    // ------------------------------------------------------------------

    private void drawStandings(Canvas c) {
        // ★ 必须显式设回 FILL：这个 p 是**共享**的，drawTyres 结尾把它留成了
        //   STROKE（画轮胎圆圈要用），于是"先进轮胎、再切名次"时下面的车队色条
        //   和整行琥珀闪烁会退化成 1px 空心框 —— 用户会以为"根本没加上颜色"。
        //   验证者读代码抓到的（真机静态推理，未上机）。
        p.setStyle(Paint.Style.FILL);
        List<F1Feed.Car> cars = feed.cars();
        float y = dp(16);
        float step = Math.min(dp(20), (getHeight() - dp(20)) / Math.max(1, cars.size()));
        txt(c, "名次 车手   差距 / 间隔", dp(8), y, 11, 0xFF37474F,
                Paint.Align.LEFT, true);
        y += step;
        boolean anyFlash = false;
        // 闪动节奏：400ms 心跳，亮一半 —— 够看出在"闪"
        final boolean blinkOn = (System.currentTimeMillis() / 400L) % 2L == 0L;
        for (int i = 0; i < cars.size() && y < getHeight(); i++) {
            F1Feed.Car car = cars.get(i);

            // ★ 名次急剧后退（5 秒 ≥2 位 / 10 秒 ≥3 位）——整条闪动提醒。
            //   规则在共享的 PositionFlash 里，和电脑版是同一份。
            if (car.flash) {
                anyFlash = true;
                if (blinkOn) {
                    p.setColor(0xFFFFE082);          // 琥珀：比红旗轻、比白底显眼
                    c.drawRect(0f, y - step * 0.74f, getWidth(), y + dp(4), p);
                }
            }

            // 车队颜色条（用户要的："差距间隔页面没有车手对应车队颜色"）
            teamBar(c, car, dp(3), y, step);

            txt(c, String.valueOf(car.position > 0 ? car.position : i + 1),
                    dp(10), y, 11, 0xFF90A4AE, Paint.Align.LEFT, false);
            txt(c, car.label(), dp(30), y, 11, 0xFF212121, Paint.Align.LEFT, true);

            // 掉了多少位：写在车手名右边，只在闪的时候出现
            if (car.flash) {
                p.setTextSize(dp(11));
                p.setFakeBoldText(true);
                float tw = p.measureText(car.label());
                int n = car.drop5 >= PositionFlash.SHORT_DROP ? car.drop5 : car.drop10;
                txt(c, "\u25bc" + n, dp(30) + tw + dp(5), y, 10, 0xFFD84315,
                        Paint.Align.LEFT, false);
            }

            String d = car.gap;
            if (d == null || d.length() == 0) {
                d = car.interval;
            }
            txt(c, d == null ? "" : d, dp(80), y, 10, 0xFF607D8B,
                    Paint.Align.LEFT, false);
            String tyre = F1Feed.compoundCn(car.compound);
            txt(c, tyre + (car.tyreLaps > 0 ? " " + car.tyreLaps : ""),
                    getWidth() - dp(8), y, 10,
                    car.inPit ? 0xFFF57C00 : 0xFF607D8B, Paint.Align.RIGHT, false);
            y += step;
        }
        if (cars.isEmpty()) {
            txt(c, "还没有计时数据", getWidth() / 2f, getHeight() / 2f, 13,
                    0xFF90A4AE, Paint.Align.CENTER, false);
        }
        // 还在闪就继续定时刷新 —— 否则一秒才重绘一次，根本看不出"闪"
        if (anyFlash) {
            postInvalidateDelayed(400L);
        }
    }

    /**
     * 一行左侧的车队颜色条。
     *
     * 用户连着提了两次："成绩榜（差距/间隔）和最快圈页面没有车手对应车队颜色" ——
     * 所以这两处都用它，别各画各的。高度跟着行高走；没有颜色数据就给中灰，
     * 保持每行对齐（少一条会显得错位）。
     */
    private void teamBar(Canvas c, F1Feed.Car car, float x, float y, float rowH) {
        p.setColor(teamColourOf(car));
        float top = y - rowH * 0.62f;
        c.drawRect(x, top, x + dp(3), top + rowH * 0.76f, p);
    }

    /**
     * 车队颜色条用的颜色。{@code TeamColour} 是 6 位十六进制（有时带 #），
     * 解析不了就给一条中灰 —— 宁可不显眼，也不要因为一个脏字符串中断整块绘制。
     */
    static int teamColourOf(F1Feed.Car car) {
        String s = car == null ? null : car.teamColour;
        if (s != null) {
            s = s.trim();
            if (s.startsWith("#")) {
                s = s.substring(1);
            }
            if (s.length() >= 6) {
                try {
                    return 0xFF000000 | (int) Long.parseLong(s.substring(0, 6), 16);
                } catch (NumberFormatException ignored) {
                    // 落到下面的中灰
                }
            }
        }
        return 0xFF607D8B;
    }

    // ------------------------------------------------------------------
    // 3 天气
    // ------------------------------------------------------------------

    private void drawWeather(Canvas c) {
        float x = dp(10);
        float y = dp(24);
        txt(c, "天气与赛道", x, y, 13, 0xFF37474F, Paint.Align.LEFT, true);
        y += dp(20);
        txt(c, feed.weatherText(), x, y, 11, 0xFF455A64, Paint.Align.LEFT, false);
        y += dp(24);
        txt(c, "全赛道状态", x, y, 13, 0xFF37474F, Paint.Align.LEFT, true);
        y += dp(20);
        txt(c, feed.track.label() + "（状态码 " + feed.trackStatusCode() + "）",
                x, y, 11, 0xFF455A64, Paint.Align.LEFT, false);
        y += dp(20);
        txt(c, "双黄区段：" + F1Layout.sectorList(
                feed.track.doubleYellowSectors(), 12), x, y, 11,
                0xFF455A64, Paint.Align.LEFT, false);
        y += dp(18);
        txt(c, "黄旗区段：" + F1Layout.sectorList(
                feed.track.yellowSectors(), 12), x, y, 11,
                0xFF455A64, Paint.Align.LEFT, false);
    }

    // ------------------------------------------------------------------
    // 4 最快圈
    // ------------------------------------------------------------------

    private void drawFastest(Canvas c) {
        // 同上：先确保 FILL，别被 drawTyres 结尾留下的 STROKE 影响车队色条
        p.setStyle(Paint.Style.FILL);
        List<F1Feed.Car> cars = new ArrayList<F1Feed.Car>(feed.cars());
        final List<F1Feed.Car> withLap = new ArrayList<F1Feed.Car>();
        for (int i = 0; i < cars.size(); i++) {
            if (cars.get(i).bestLap != null && cars.get(i).bestLap.length() > 0) {
                withLap.add(cars.get(i));
            }
        }
        Collections.sort(withLap, new Comparator<F1Feed.Car>() {
            public int compare(F1Feed.Car a, F1Feed.Car b) {
                double d = lapSeconds(a.bestLap) - lapSeconds(b.bestLap);
                return d < 0 ? -1 : (d > 0 ? 1 : 0);
            }
        });
        float y = dp(24);
        txt(c, "个人最快圈", dp(10), y, 13, 0xFF37474F, Paint.Align.LEFT, true);
        y += dp(22);
        for (int i = 0; i < withLap.size() && y < getHeight(); i++) {
            F1Feed.Car car = withLap.get(i);
            // 车队颜色条（用户要的："最快圈页面没有车手对应车队颜色"）
            teamBar(c, car, dp(10), y, dp(19));
            txt(c, String.valueOf(i + 1), dp(18), y, 11, 0xFF90A4AE,
                    Paint.Align.LEFT, false);
            txt(c, car.label(), dp(36), y, 11, 0xFF212121, Paint.Align.LEFT, true);
            txt(c, car.bestLap, getWidth() - dp(10), y, 11, 0xFF455A64,
                    Paint.Align.RIGHT, false);
            y += dp(19);
        }
        if (withLap.isEmpty()) {
            txt(c, "还没有最快圈数据", getWidth() / 2f, getHeight() / 2f, 13,
                    0xFF90A4AE, Paint.Align.CENTER, false);
        }
    }

    /** "1:38.220" -> 98.22 秒；解析不出来给一个很大的值排到最后。 */
    static double lapSeconds(String lap) {
        if (lap == null || lap.length() == 0) {
            return Double.MAX_VALUE;
        }
        try {
            String s = lap.trim();
            int colon = s.indexOf(':');
            if (colon < 0) {
                return Double.parseDouble(s);
            }
            double min = Double.parseDouble(s.substring(0, colon));
            double sec = Double.parseDouble(s.substring(colon + 1));
            return min * 60 + sec;
        } catch (Exception e) {
            return Double.MAX_VALUE;
        }
    }

    // ------------------------------------------------------------------
    // 5 环节
    // ------------------------------------------------------------------

    private void drawSession(Canvas c) {
        float x = dp(10);
        float y = dp(24);
        txt(c, feed.meetingName(), x, y, 13, 0xFF212121, Paint.Align.LEFT, true);
        y += dp(20);
        txt(c, feed.circuitName() + "  ·  " + feed.sessionName(), x, y, 11,
                0xFF455A64, Paint.Align.LEFT, false);
        y += dp(18);
        txt(c, "状态 " + feed.sessionStatus() + "   剩余 " + feed.remaining(),
                x, y, 11, 0xFF455A64, Paint.Align.LEFT, false);
        y += dp(18);
        txt(c, "圈数 " + feed.currentLap() + " / " + feed.totalLaps(), x, y, 11,
                0xFF455A64, Paint.Align.LEFT, false);
        y += dp(22);

        List<String> top = feed.topThree();
        if (!top.isEmpty()) {
            txt(c, "前三名", x, y, 12, 0xFF37474F, Paint.Align.LEFT, true);
            y += dp(18);
            for (int i = 0; i < top.size() && y < getHeight(); i++) {
                txt(c, top.get(i), x, y, 11, 0xFF455A64, Paint.Align.LEFT, false);
                y += dp(18);
            }
        }
    }
}
