package com.haf1.racecontrol;

import android.app.Activity;
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
    private ListView listView;
    private RowAdapter adapter;

    private final List<RaceMessage> shown = new ArrayList<RaceMessage>();
    private final List<TextView> icons = new ArrayList<TextView>();
    private final Set<String> flashing = new HashSet<String>();

    private final Handler ui = new Handler();
    private final SimpleDateFormat fmt =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private boolean onlyImportant = true;
    private long lastBeepAt = 0L;
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

        buildLayout();
        startClient();
        ui.post(tickTask);
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
                refreshList();
            }
        });
        row.addView(filterToggle);
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
                ui.post(new Runnable() {
                    public void run() {
                        updateStatus();
                    }
                });
            }

            public void onClose() {
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
                        refreshUi(false);
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
        AlertGate.Action a = gate.onMessage(m, System.currentTimeMillis());
        runAction(a);
        if (prefs.flashEnabled) {
            startFlash(m.key());
        }
        refreshUi(false);
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
        String conn;
        if (client == null) {
            conn = "未启动";
        } else if (lastCount >= 0 && startedAt > 0
                && System.currentTimeMillis() - startedAt < 3000) {
            conn = "连接中";
        } else {
            conn = feed != null && feed.messages.size() > 0 ? "⏱ 实时" : "⏱ 连接中";
        }
        StringBuilder b = new StringBuilder();
        b.append(conn);
        if (feed != null) {
            b.append("  ").append(feed.meetingName());
            if (feed.sessionName().length() > 0) {
                b.append(' ').append(feed.sessionName());
            }
            b.append("  ").append(feed.messages.size()).append(" 条");
            int lap = feed.currentLap();
            if (lap > 0) {
                b.append("  第 ").append(lap).append('/')
                        .append(feed.totalLaps()).append(" 圈");
            }
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
                badge.setPadding(dp(6), 0, 0, 0);
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
            if (flashing.contains(m.key())) {
                bg = Classifier.barColor(kind);
            }
            row.setBackgroundColor(bg);
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
