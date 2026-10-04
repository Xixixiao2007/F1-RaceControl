package com.haf1.racecontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 顶部旗语栏 —— **多种旗语并存时纵向分隔，每段显示该旗语涉及的区段**。
 *
 * 用户要求：「顶部旗语栏在出现多种旗语时纵向分隔，分别显示各种旗语出现的区段」。
 *
 * 所以它不是"显示最高优先级的那一个"，而是把**当前同时存在**的旗语全列出来，
 * 用竖线隔开（{@link F1Layout#barDividers}），每段两行：
 *
 *     ┌──────────┬──────────┬─────────┐
 *     │ 黄旗     │ 双黄旗   │ 安全车  │
 *     │ 3,7      │ 12       │ 全场    │
 *     └──────────┴──────────┴─────────┘
 *
 * 轨道级的（红旗/安全车/VSC/全赛道黄旗）第二行写「全场」；区段级的写区段号。
 * 这个区分不是猜的：轨道级来自官方 `TrackStatus` 流，区段级来自消息的
 * `Scope=Sector`（详见 {@link TrackState#presentKinds()}）。
 */
public class TopFlagBarView extends View {

    private TrackState track;

    private final Paint fill = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint divider = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TopFlagBarView(Context c) {
        super(c);
        init();
    }

    private void init() {
        text.setAntiAlias(true);
        text.setFakeBoldText(true);
        divider.setColor(0x66FFFFFF);
        divider.setStrokeWidth(dp(1));
    }

    public void setTrack(TrackState t) {
        this.track = t;
        invalidate();
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        // 固定两行高度：一行旗语名，一行区段
        int h = (int) dp(40);
        setMeasuredDimension(resolveSize(Integer.MAX_VALUE, widthSpec), h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        List<String> kinds = track == null ? new ArrayList<String>()
                : track.presentKinds();
        if (kinds.isEmpty()) {
            fill.setColor(0xFF37474F);
            canvas.drawRect(0, 0, w, h, fill);
            text.setColor(Color.WHITE);
            text.setTextSize(dp(14));
            text.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(track == null ? "等待数据" : "无旗语", w / 2f, h / 2f + dp(5), text);
            return;
        }

        float[] xs = F1Layout.barDividers(kinds.size(), w);
        for (int i = 0; i < kinds.size(); i++) {
            String kind = kinds.get(i);
            RectF r = new RectF(xs[i], 0, xs[i + 1], h);
            fill.setColor(Classifier.barColor(kind));
            canvas.drawRect(r, fill);

            if (i > 0) {
                canvas.drawLine(xs[i], 0, xs[i], h, divider);
            }

            boolean dark = needsDarkText(kind);
            int fg = dark ? 0xFF212121 : Color.WHITE;
            float cx = (xs[i] + xs[i + 1]) / 2f;

            text.setColor(fg);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(dp(14));
            canvas.drawText(Classifier.label(kind), cx, h * 0.40f, text);

            List<Integer> secs = track.sectorsOf(kind);
            String sub = secs.isEmpty() ? "全场" : F1Layout.sectorList(secs, 12);
            text.setTextSize(dp(11));
            text.setFakeBoldText(false);
            text.setColor(dark ? 0xCC212121 : 0xE6FFFFFF);
            canvas.drawText(sub, cx, h * 0.78f, text);
            text.setFakeBoldText(true);
        }
    }

    /** 浅底用深字。 */
    private static boolean needsDarkText(String kind) {
        return Classifier.K_DY.equals(kind) || Classifier.K_YELLOW.equals(kind)
                || Classifier.K_VSC.equals(kind);
    }
}
