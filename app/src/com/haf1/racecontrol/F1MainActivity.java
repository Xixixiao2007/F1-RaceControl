package com.haf1.racecontrol;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A 方案主界面：**直连 F1 官方公开流，不带任何令牌**。
 *
 * ## 布局
 *   竖屏   [ 顶部旗语栏 ]
 *          [ 消息列表 ]
 *
 *   横屏   [ 顶部旗语栏 ]
 *          [ 消息列表 | 右侧面板 | 窄框图标条 ]
 *
 * 横屏分栏是用户明确要求的：左边看赛事控制消息，右边用窄框图标在
 * 「赛道图 / 轮胎进站 / 成绩 / 天气 / 最快圈 / 环节」之间切换。
 *
 * ## 数据
 * 全部来自 {@link F1Client}（官方公开流），**没有 HA、没有令牌**。
 * 公开流包含 RaceControlMessages / TrackStatus / TimingData / TimingAppData /
 * DriverList / WeatherData 等，足够把右栏 6 屏填满；需要 F1TV 的
 * （遥测、赛道位置、车队无线电）本 App 一个都不用。
 */
public class F1MainActivity extends Activity {

    private static final long FLASH_MS = 900L;
    private static final long BEEP_MIN_GAP_MS = 1500L;

    private Prefs prefs;
    private Notifier notifier;
    private final AlertGate gate = new AlertGate();

    private F1Client client;
    private F1Feed feed;

    private TopFlagBarView flagBar;
    private RightPanelView panel;
    private LinearLayout iconStrip;
    private TextView statusView;
    private TextView filterToggle;
    /** 状态行右端的「设置」入口。 */
    private TextView settingsView;
    private ListView listView;
    private RowAdapter adapter;

    private final List<RaceMessage> shown = new ArrayList<RaceMessage>();
    private final List<TextView> icons = new ArrayList<TextView>();
    private final Set<String> flashing = new HashSet<String>();

    private final Handler ui = new Handler();
    private final SimpleDateFormat fmt =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private boolean onlyImportant = true;
    /** 用户是否在主界面手动切过「精简 / 全部」。切过就不再被设置页的默认值覆盖。 */
    private boolean userToggledFilter = false;
    private long lastBeepAt = 0L;
    /** 刷新是否已经排进消息队列（去抖用：一批快照只刷一次）。 */
    private boolean refreshScheduled = false;

    /** 连接阶段，直接显示在状态行里——用户看完能告诉我卡在哪。 */
    private volatile String stage = "未启动";
    private volatile boolean opened = false;
    private volatile String lastErr = "";

    /**
     * 多旧的消息就不该再唤醒了。
     *
     * ★ 订阅时官方会推一份**历史全量快照**（巴林站 327 条）。不过滤的话，
     *   开机瞬间就会弹 23 次全屏强提醒
     *   （3 条红旗 + 1 条 VSC + 19 条双黄），
     *   全是几小时前就结束的事。
     *   实时比赛不受影响：实时消息就是几秒前的。
     */
    private static final long ALERT_MAX_AGE_MS = 3 * 60 * 1000L;
    private int lastCount = -1;
    private long startedAt = 0L;

    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.load(this);
        prefs.applyTo(gate);
        notifier = new Notifier(this);
        onlyImportant = prefs.noiseFilterEnabled;

        // ★ 装崩溃兜底：手机上闪退时用户什么也看不到，
        //   把栈落盘、下次启动弹出来，他截图发我即可。
        CrashGuard.install(this);

        buildLayout();
        startClient();
        ui.post(tickTask);

        String crash = CrashGuard.take(this);
        if (crash.length() > 0) {
            showCrash(crash);
        }
    }

    /** 把上一次的崩溃栈显示出来（可滚动）。用户截图就能发过来。 */
    private void showCrash(String text) {
        try {
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            android.widget.TextView tv = new android.widget.TextView(this);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            tv.setTextIsSelectable(true);
            tv.setPadding(dp(10), dp(10), dp(10), dp(10));
            tv.setText("上次运行崩了。请把下面内容截图发给我：\n\n" + text);
            sv.addView(tv);
            new android.app.AlertDialog.Builder(this)
                    .setTitle("崩溃日志（" + text.length() + " 字符）")
                    .setView(sv)
                    .setPositiveButton("知道了", null)
                    .show();
        } catch (Throwable ignored) {
            // 弹不出来也不能再崩一次
        }
    }

    /**
     * 布局单独抽出来，因为转屏时要重建一次。
     *
     * ★ manifest 里声明了 configChanges，所以转屏**不会**重建 Activity。
     *   否则一转屏就新建 F1Client、重新连一次、已收的消息全没了。
     */
    private void buildLayout() {
        // ★ 转屏会再调一次这个方法。icons 里存的是"当前这批图标 TextView"，
        //   不清空的话会累加上一批已经不在界面上的旧 View，
        //   highlightIcons() 就白刷一半。
        icons.clear();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F5F5);

        flagBar = new TopFlagBarView(this);
        root.addView(flagBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        root.addView(buildBody(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        refreshList();
        // ★ 必须调：否则在收到任何回调之前，
        //   状态行是**空白**的，用户看不到"连接中"。
        updateStatus();
        refreshUi(true);
    }

    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        buildLayout();   // 横竖屏切换：只重建布局，不动连接
    }

    private View buildBody() {
        boolean land = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);

        // ---- 左：消息列表 ----
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(buildStatusRow());

        listView = new ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setCacheColorHint(0);
        adapter = new RowAdapter();
        listView.setAdapter(adapter);
        left.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        body.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        if (land) {
            // ---- 右：面板 + 窄框图标条 ----
            panel = new RightPanelView(this);
            body.addView(panel, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            body.addView(buildIconStrip());
        }
        return body;
    }

    private View buildStatusRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(0xFFECEFF1);
        row.setPadding(dp(8), dp(4), dp(8), dp(4));

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusView.setTextColor(0xFF37474F);
        row.addView(statusView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        filterToggle = new TextView(this);
        filterToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        filterToggle.setTextColor(0xFF00695C);
        filterToggle.setPadding(dp(10), dp(4), dp(4), dp(4));
        filterToggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onlyImportant = !onlyImportant;
                // 用户在主界面手动切过之后，就别再被设置页里的默认值覆盖了
                userToggledFilter = true;
                refreshList();
            }
        });
        row.addView(filterToggle);

        // ★ 设置入口。A 方案重写主界面时**漏掉了这个** —— 设置页的代码一直
        //   都在（数据源 / 显示与过滤 / 提醒 / 试听 / 关于，v3.0.0 时已改成
        //   A 方案版），但新主界面没有任何地方能打开它，用户点不到，
        //   看起来就像"设置被删了"。
        //   旧 MainActivity 是一直有的（右上角"设置"），照它补回来。
        settingsView = new TextView(this);
        settingsView.setText("设置");
        settingsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        settingsView.setTextColor(0xFF00695C);
        settingsView.setPadding(dp(12), dp(4), dp(2), dp(4));
        settingsView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(F1MainActivity.this, SettingsActivity.class));
            }
        });
        row.addView(settingsView);

        refreshToggleText();
        return row;
    }

    /** 右侧那条窄框图标：一屏一个，点谁切谁。 */
    private View buildIconStrip() {
        iconStrip = new LinearLayout(this);
        iconStrip.setOrientation(LinearLayout.VERTICAL);
        iconStrip.setBackgroundColor(0xFF263238);
        for (int i = 0; i < RightPanelView.MODE_COUNT; i++) {
            final int mode = i;
            TextView t = new TextView(this);
            t.setText(RightPanelView.MODE_ICONS[i]);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(8), dp(10), dp(8), dp(10));
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (panel != null) {
                        panel.setMode(mode);
                    }
                    highlightIcons(mode);
                }
            });
            icons.add(t);
            iconStrip.addView(t, new LinearLayout.LayoutParams(dp(38),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        highlightIcons(RightPanelView.MODE_TRACK);
        return iconStrip;
    }

    private void highlightIcons(int mode) {
        for (int i = 0; i < icons.size(); i++) {
            boolean on = i == mode;
            icons.get(i).setTextColor(on ? Color.WHITE : 0xFF90A4AE);
            icons.get(i).setBackgroundColor(on ? 0xFF00897B : 0x00000000);
        }
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------------
    // 数据
    // ------------------------------------------------------------------

    private void startClient() {
        client = new F1Client(new F1Client.Listener() {
            public void onOpen() {
                opened = true;
                stage = "已连上官方流，等数据…";
                ui.post(new Runnable() {
                    public void run() {
                        startedAt = System.currentTimeMillis();
                        updateStatus();
                    }
                });
            }

            public void onFeed(F1Feed f) {
                ui.post(new Runnable() {
                    public void run() {
                        refreshUi(true);
                    }
                });
            }

            public void onError(final String message) {
                lastErr = message == null ? "" : message;
                stage = "连接出错，即将重试";
                ui.post(new Runnable() {
                    public void run() {
                        updateStatus();
                    }
                });
            }

            public void onClose() {
                opened = false;
                if (!stage.startsWith("连接出错")) {
                    stage = "连接已断开，即将重试";
                }
                ui.post(new Runnable() {
                    public void run() {
                        updateStatus();
                    }
                });
            }
        });
        feed = client.feed();
        feed.setListener(new F1Feed.Listener() {
            public void onRaceMessage(final RaceMessage m) {
                ui.post(new Runnable() {
                    public void run() {
                        onNewMessage(m);
                    }
                });
            }

            public void onStatusChanged() {
                ui.post(new Runnable() {
                    public void run() {
                        scheduleRefresh();
                    }
                });
            }
        });
        Thread t = new Thread(new Runnable() {
            public void run() {
                client.runForever();
            }
        }, "f1-live");
        t.setDaemon(true);
        t.start();
    }

    /** 新消息：告警 + 列表 + 旗语栏。 */
    private void onNewMessage(RaceMessage m) {
        // ★ 只对**新鲜**消息告警。
        //   订阅时推来的历史快照不能响：否则开机就被
        //   23 次全屏强提醒活埋，而且全是几小时前的事。
        long now = System.currentTimeMillis();
        boolean fresh = m.time <= 0 || now - m.time <= ALERT_MAX_AGE_MS;
        if (fresh) {
            AlertGate.Action a = gate.onMessage(m, now);
            runAction(a);
        }
        if (prefs.flashEnabled) {
            startFlash(m.key());
        }
        scheduleRefresh();
    }

    /**
     * 合并成一次刷新。
     *
     * 客户端线程推快照时会**逐条**回调（巴林站 327 次），每次都全表过滤
     * 等于跑 327 遍 O(n^2)。合并之后一批只刷一次，顺带缩短了与后台线程
     * 重叠的窗口。
     */
    private void scheduleRefresh() {
        if (refreshScheduled) {
            return;
        }
        refreshScheduled = true;
        ui.post(new Runnable() {
            public void run() {
                refreshScheduled = false;
                refreshUi(true);
            }
        });
    }

    private void runAction(AlertGate.Action a) {
        if (a == null || a.msg == null) {
            return;
        }
        if (a.severity >= Classifier.ALARM) {
            if (notifier != null) {
                notifier.stopAlarm();      // 声音交给 AlertActivity 播，避免两路叠加
                notifier.pushNotification(a.kind, a.msg.text(), a.msg);
            }
            AlertActivity.show(this, a.kind, a.msg.text(), a.msg.time,
                    a.msg.sector, a.msg.carNumber, a.escalated, prefs);
            return;
        }
        if (a.severity == Classifier.ATTENTION) {
            long now = System.currentTimeMillis();
            if (now - lastBeepAt < BEEP_MIN_GAP_MS) {
                return;
            }
            lastBeepAt = now;
            if (notifier != null) {
                notifier.attention(prefs.soundEnabled, prefs.vibrateEnabled);
            }
        }
    }

    private void refreshUi(boolean listToo) {
        if (feed == null) {
            return;
        }
        if (flagBar != null) {
            flagBar.setTrack(feed.track);
        }
        if (panel != null) {
            panel.setFeed(feed);
        }
        if (listToo || feed.messages.size() != lastCount) {
            lastCount = feed.messages.size();
            refreshList();
        }
        updateStatus();
    }

    private void refreshList() {
        shown.clear();
        List<RaceMessage> all = feed == null ? new ArrayList<RaceMessage>()
                : feed.messages.sortedDesc();
        for (int i = 0; i < all.size(); i++) {
            RaceMessage m = all.get(i);
            if (onlyImportant && !prefs.accept(m)) {
                continue;
            }
            shown.add(m);
            if (shown.size() >= Math.max(50, prefs.maxStored)) {
                break;
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        refreshToggleText();
    }

    private void refreshToggleText() {
        if (filterToggle != null) {
            filterToggle.setText(onlyImportant ? "精简 ▸ 全部" : "全部 ▸ 精简");
        }
    }

    private void updateStatus() {
        if (statusView == null) {
            return;
        }
        // ★ 状态行要能回答“卡在哪”。
        //   之前只写"连接中"，用户看完也没法反馈；
        //   现在写清楚：阶段 + 已收条数 + 最后一条错误。
        StringBuilder b = new StringBuilder();
        int n = feed == null ? 0 : feed.messages.size();
        if (n > 0) {
            b.append("⏱ 实时");
        } else {
            b.append("… ").append(stage);
        }
        if (feed != null && n > 0) {
            if (feed.meetingName().length() > 0) {
                b.append("  ").append(feed.meetingName());
            }
            if (feed.sessionName().length() > 0) {
                b.append(' ').append(feed.sessionName());
            }
            b.append("  ").append(n).append(" 条");
            int lap = feed.currentLap();
            if (lap > 0) {
                b.append("  第 ").append(lap).append('/')
                        .append(feed.totalLaps()).append(" 圈");
            }
        }
        String e = lastErr;
        if (e.length() == 0 && client != null) {
            e = client.lastError();
        }
        if (e.length() > 0 && n == 0) {
            b.append("  |  ").append(e.length() > 60
                    ? e.substring(0, 60) + "…" : e);
        }
        statusView.setText(b.toString());
    }

    private void startFlash(final String key) {
        if (key == null) {
            return;
        }
        flashing.add(key);
        ui.postDelayed(new Runnable() {
            public void run() {
                flashing.remove(key);
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            }
        }, FLASH_MS + 120L);
    }

    private final Runnable tickTask = new Runnable() {
        public void run() {
            long now = System.currentTimeMillis();
            List<AlertGate.Action> ups = gate.onTick(now);
            for (int i = 0; i < ups.size(); i++) {
                runAction(ups.get(i));
            }
            updateStatus();
            ui.postDelayed(this, 1000L);
        }
    };

    // ------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        prefs = Prefs.load(this);
        prefs.applyTo(gate);
        // 从设置页回来时把「精简」默认值同步过来（用户没在主界面手动切过的话）。
        // 否则改了设置回到主界面看不到变化，会以为设置没生效。
        if (!userToggledFilter) {
            onlyImportant = prefs.noiseFilterEnabled;
        }
        applyKeepScreenOn();
        refreshList();
        refreshUi(false);
    }

    /**
     * 用户要的「看消息时不熄屏」。
     *
     * ★ 用窗口标志实现，**不需要任何权限**、也不占 WakeLock。
     *   （网页版做不到这件事：navigator.wakeLock 要求 HTTPS。）
     */
    private void applyKeepScreenOn() {
        if (prefs.keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(tickTask);
        if (client != null) {
            client.stop();
        }
    }

    // ------------------------------------------------------------------
    // 行渲染
    // ------------------------------------------------------------------

    private class RowAdapter extends BaseAdapter {

        public int getCount() {
            return shown.size();
        }

        public Object getItem(int i) {
            return shown.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row;
            TextView time;
            TextView badge;
            TextView body;
            TextView orig;
            if (convert == null) {
                row = new LinearLayout(F1MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(dp(6), dp(5), dp(6), dp(5));

                LinearLayout col = new LinearLayout(F1MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);

                LinearLayout head = new LinearLayout(F1MainActivity.this);
                head.setOrientation(LinearLayout.HORIZONTAL);
                time = new TextView(F1MainActivity.this);
                time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                time.setTextColor(0xCCFFFFFF);
                badge = new TextView(F1MainActivity.this);
                badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                // 徽标有独立底色（见 getView），四边都要留白才像标签
                badge.setPadding(dp(5), dp(1), dp(5), dp(1));
                badge.setTextColor(Color.WHITE);
                head.addView(time);
                head.addView(badge);
                col.addView(head);

                body = new TextView(F1MainActivity.this);
                body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                body.setTextColor(Color.WHITE);
                col.addView(body);

                orig = new TextView(F1MainActivity.this);
                orig.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                orig.setTextColor(0x99FFFFFF);
                col.addView(orig);

                row.addView(col, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.setTag(new TextView[]{time, badge, body, orig});
            } else {
                row = (LinearLayout) convert;
                TextView[] t = (TextView[]) row.getTag();
                time = t[0];
                badge = t[1];
                body = t[2];
                orig = t[3];
            }

            RaceMessage m = shown.get(pos);
            String kind = Classifier.kind(m);
            int bg = Classifier.color(kind);
            boolean flash = flashing.contains(m.key());
            if (flash) {
                bg = Classifier.barColor(kind);
            }
            row.setBackgroundColor(bg);

            // ★ 文字颜色必须跟着背景走。
            //   Classifier.color() 给的是**极浅**的底色（红 FFFFCDD2、黄 FFFFF9C4、
            //   灰 FFEEEEEE、白 FFFFFFFF…）。第一版这里把文字全写成白色，
            //   结果是**白字白底，整列消息看起来是空的** —— 装上 App 后
            //   "完全没东西"就是这个原因，不是没连上。
            //   亮底用深字，闪动时底色换成 barColor（深色）才用白字。
            int fgTime = flash ? 0xCCFFFFFF : 0xFF78909C;
            int fgBody = flash ? Color.WHITE : 0xFF212121;
            int fgSub = flash ? 0x99FFFFFF : 0xFF00695C;
            time.setTextColor(fgTime);
            badge.setTextColor(Color.WHITE);   // 徽标有独立底色，始终白字
            body.setTextColor(fgBody);
            orig.setTextColor(fgSub);
            // 徽标给个实心底色，否则"白字 + 浅底"同样看不清
            badge.setBackgroundColor(Classifier.barColor(kind));

            time.setText(fmt.format(new Date(m.time)));
            badge.setText(Classifier.label(kind));

            String g = Translator.gloss(m.text());
            if (g != null && g.length() > 0) {
                body.setText(g);
                orig.setText(m.text());
                orig.setVisibility(View.VISIBLE);
            } else {
                body.setText(m.text());
                orig.setText("");
                orig.setVisibility(View.GONE);
            }
            return row;
        }
    }
}
