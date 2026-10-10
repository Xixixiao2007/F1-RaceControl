package com.haf1.racecontrol;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * 设置页。
 *
 * 分组：数据源 / 显示与过滤 / 提醒 / 试听 / 关于。
 *
 * ★ A 方案下已经**没有任何连接参数要填**：不需要服务器地址、
 *   不需要令牌、不需要实体 ID。联网那一步完全由 App 自己完成。
 */
public class SettingsActivity extends Activity {

    private Prefs p;

    private EditText carBox;
    private EditText excludeBox;
    private EditText cooldownBox;
    private EditText dySecondsBox;
    private EditText autoStopBox;
    /** 消息延时（秒）—— 整个显示滞后 N 秒对齐直播画面。 */
    private EditText delayBox;

    private CheckBox flashBox;
    private CheckBox keepScreenBox;
    private CheckBox noiseBox;
    private CheckBox hideBlueBox;
    private CheckBox hideClearBox;
    private CheckBox hideDeletedBox;
    private CheckBox soundBox;
    private CheckBox vibrateBox;
    private CheckBox wakeBox;
    private CheckBox silentBox;
    private CheckBox attentionBox;

    private Spinner dySpinner;
    private TextView testResult;

    /** 回放文件状态那一行，以及「用户刚选的文件」这两个暂存值。 */
    private TextView replayDetail;
    private String pendingUri = "";
    private String pendingName = "";
    private Spinner speedSpinner;

    /** 文件选择器的请求码。 */
    private static final int REQ_PICK_RCLOG = 0x5243;   // "RC"

    private static final int[] SPEEDS = {10, 30, 60, 120, 300};
    private static final String[] SPEED_LABELS = {
            "10 倍速（4 小时要看 24 分钟）",
            "30 倍速（4 小时看 8 分钟）",
            "60 倍速（4 小时看 4 分钟，推荐）",
            "120 倍速（4 小时看 2 分钟）",
            "300 倍速（4 小时看 48 秒，只适合看会不会崩）",
    };

    private static final String[] DY_LABELS = {
            "双黄旗：只闪动，不提醒",
            "双黄旗：只轻提醒（不升级）",
            "双黄旗：轻提醒 + 持续超时升级（推荐，实测约 23 次/周末）",
            "双黄旗：轻提醒 + 仅大面积（≥3 区段）升级（实测约 10 次/周末）",
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        p = Prefs.load(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(24));
        root.setBackgroundColor(0xFFFAFAFA);

        header(root, "数据源");
        label(root, "F1 官方公开流（直连，不需要任何账号或令牌）",
                "A 方案：App 直接连 Formula 1 官方的实时计时服务。"
                        + "数据和 Home Assistant、和中继服务器都没有关系。"
                        + "公开流包含赛事控制消息、赛道状态、计时、轮胎、天气；"
                        + "需要 F1TV 订阅的那几路（遥测、赛道位置、车队无线电）"
                        + "本 App 一个都不用。");
        label(root, "livetiming.formula1.com",
                "连接时只用一个**一次性**令牌，每次重连都会重新申请；"
                        + "App 里不保存任何凭据，你也不需要填任何东西。");

        Button test = new Button(this);
        test.setText("测试能否连上官方流");
        test.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                testConnection();
            }
        });
        root.addView(test);

        testResult = new TextView(this);
        testResult.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        testResult.setPadding(0, dp(6), 0, dp(10));
        root.addView(testResult);

        header(root, "回放测试（假数据）");
        // 存在的意义：真机上的崩溃只在真实比赛数据下出现，而一年只有二十几场，
        // 崩了就得再等一周，还不能复现。回放文件是官方归档里的一场真实比赛
        // （快照 + 每条增量都在），格式和线上收到的一模一样 —— 于是
        // "等下一场"就变成"随时重放昨天那场"。
        label(root, "用一个 .rclog 文件跑界面，不需要比赛、不需要网络。",
                "文件里是一条完整时间线：接上时先给一份完整快照"
                        + "（和真的连上去时一样，几百条历史消息 + 22 辆车），"
                        + "之后逐条放增量。走的解析和渲染代码与实时完全一致。"
                        + "放出来的增量消息时间会被改成「现在」，所以提醒、闪动、"
                        + "全屏横幅都会真的触发；快照里的历史消息仍然是旧的，"
                        + "不会被当成新消息炸你一屏。");

        // ★ 不用申请任何存储权限：系统的文件选择器（SAF）把文件交给 App，
        //   所以文件放哪儿都行 —— 下载目录、网盘、U 盘、甚至别人传给你的。
        //   用「应用私有目录 + 要你拿数据线拷进去」那种做法，在
        //   Android 4.4 以后第三方文件管理器根本写不进去，只会让人以为 App 坏了。
        Button pick = new Button(this);
        pick.setText("选择 .rclog 文件…");
        pick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickReplayFile();
            }
        });
        root.addView(pick);

        Button clearReplay = new Button(this);
        clearReplay.setText("关闭回放（改回官方流）");
        clearReplay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pendingUri = "";
                pendingName = "";
                p.replayUri = "";
                p.replayName = "";
                // 立刻落盘：用户可能不点「保存」就返回，那样就白关了
                p.save(SettingsActivity.this);
                showReplayState("已关闭回放，返回后连官方流。");
            }
        });
        root.addView(clearReplay);

        replayDetail = new TextView(this);
        replayDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        replayDetail.setTextColor(0xFF78909C);
        replayDetail.setPadding(0, dp(6), 0, dp(8));
        root.addView(replayDetail);

        // 进来先把当前状态写出来（已选的文件名 + 它的底细）
        pendingUri = p.replayUri == null ? "" : p.replayUri;
        pendingName = p.replayName == null ? "" : p.replayName;
        showReplayState(null);

        TextView speedLabel = new TextView(this);
        speedLabel.setText("回放倍速");
        speedLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        speedLabel.setPadding(0, dp(8), 0, dp(4));
        root.addView(speedLabel);

        speedSpinner = new Spinner(this);
        ArrayAdapter<String> speedAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, SPEED_LABELS);
        speedAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        speedSpinner.setAdapter(speedAdapter);
        speedSpinner.setSelection(speedIndex(p.replaySpeed));
        root.addView(speedSpinner);

        label(root, "回放只放一遍就停，不会循环，也不会去连网络。",
                "示例文件挂在项目的 Releases 页面上（.rclog），下载到手机后"
                        + "用上面的按钮选它就行。APK 里**不再内置**任何示例数据 ——"
                        + "v3.1.x 把两个包打进去，体积从 85 KB 涨到 845 KB，"
                        + "其中 751 KB 全是示例数据，不划算。");

        header(root, "显示与过滤");

        flashBox = check(root, "新消息闪动", p.flashEnabled,
                "到达的新消息先闪两下再定格配色。历史回填不会闪。");
        keepScreenBox = check(root, "看消息时不熄屏（一直亮屏）", p.keepScreenOn,
                "主列表一直亮着，不会自动熄屏。窗口标志实现，不需要额外权限；"
                        + "费电，要看比赛时再开。（和「提醒」里那条"
                        + "「超强提醒时点亮屏幕」是两回事：那个是告警横幅，"
                        + "这个是消息列表。）");
        noiseBox = check(root, "默认隐藏噪音（精简）", p.noiseFilterEnabled,
                "总开关。关掉后下面三项都不生效。实测隐藏 253/697 = 36.3%。");
        label(root, "精简模式要隐藏哪几类：", null);
        hideBlueBox = check(root, "隐藏蓝旗", p.noiseHideBlue,
                "被套圈的蓝旗。量最大 —— 正赛里占 42.9%。");
        hideClearBox = check(root, "隐藏解除信号（CLEAR / TRACK CLEAR）",
                p.noiseHideClear,
                "默认**不隐藏**：藏掉它会让黄旗「莫名其妙就结束」，"
                        + "看不到是哪个区段被解除。想让精简更狠可以打开。");
        hideDeletedBox = check(root, "隐藏删圈速通报", p.noiseHideDeleted,
                "以 CAR 开头且含 DELETED 的那种。");
        carBox = field(root, "只看某辆车（车号）", p.carFilter, "留空 = 不筛选。例如 44。");
        excludeBox = multiline(root, "排除关键词（每行一个）", Prefs.joinLines(p.excludeKeywords),
                "对消息全文做不区分大小写的子串匹配。");

        header(root, "提醒");

        soundBox = check(root, "声音", p.soundEnabled, "关掉后只震动不发声。");
        vibrateBox = check(root, "震动", p.vibrateEnabled, "不同类型给不同节奏，闭着眼也能分辨。");
        wakeBox = check(root, "超强提醒时点亮屏幕", p.screenWakeEnabled,
                "用窗口标志实现，不需要额外的系统权限。");
        silentBox = check(root, "超强提醒突破静音模式", p.silentOverride,
                "走闹钟通道。现场看比赛时手机多半是静音，不突破就可能漏掉红旗。");
        attentionBox = check(root, "黄旗 / 黑白旗给轻提醒", p.attentionEnabled,
                "关掉后这两类只闪动、不发声。");
        autoStopBox = number(root, "超强提醒自动停止（秒，0=必须手动确认）",
                String.valueOf(p.alarmAutoStopSec), "默认 15 秒。");
        cooldownBox = number(root, "同类型提醒冷却（秒）", String.valueOf(p.cooldownSec),
                "避免连环炸响。红旗不受此限制。");

        // ---- 试听：不用连 HA 也能当场验证声音和震动 ----
        // 光看设置项没法知道"到底响不响、震不震得出来"，
        // 尤其是静音模式下走闹钟通道这件事，必须真听一次。
        header(root, "试听");
        TextView tryHint = new TextView(this);
        tryHint.setText("先按上面的开关调好，再点下面两下听听看。"
                + "注意「超强提醒」走的是**闹钟音量**，不是通知音量 —— "
                + "如果没声，先把手机闹钟音量调起来。");
        tryHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tryHint.setTextColor(0xFF90A4AE);
        root.addView(tryHint);

        Button tryAttention = new Button(this);
        tryAttention.setText("试听：轻提醒（黄旗那种）");
        tryAttention.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                collect();
                Notifier n = new Notifier(SettingsActivity.this);
                n.attention(p.soundEnabled, p.vibrateEnabled);
            }
        });
        root.addView(tryAttention);

        Button tryAlarm = new Button(this);
        tryAlarm.setText("试听：超强提醒（红旗那种，响 4 秒）");
        tryAlarm.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                collect();
                final Notifier n = new Notifier(SettingsActivity.this);
                n.startAlarm(Classifier.K_RED, p.soundEnabled, p.vibrateEnabled, p.silentOverride);
                new android.os.Handler().postDelayed(new Runnable() {
                    public void run() {
                        n.release();
                    }
                }, 4000L);
            }
        });
        root.addView(tryAlarm);

        Button tryFull = new Button(this);
        tryFull.setText("试听：全屏横幅（超强提醒的样子）");
        tryFull.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                collect();
                AlertActivity.show(SettingsActivity.this, Classifier.K_RED,
                        "RED FLAG", System.currentTimeMillis(), "", "", false, p);
            }
        });
        root.addView(tryFull);

        TextView dyLabel = new TextView(this);
        dyLabel.setText("双黄旗策略");
        dyLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        dyLabel.setPadding(0, dp(10), 0, dp(4));
        root.addView(dyLabel);

        dySpinner = new Spinner(this);
        ArrayAdapter<String> dyAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, DY_LABELS);
        dyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        dySpinner.setAdapter(dyAdapter);
        dySpinner.setSelection(Prefs.clamp(p.dyMode, 0, 3));
        root.addView(dySpinner);

        TextView dyHint = new TextView(this);
        dyHint.setText("实测：一个周末有 142 条双黄消息，但只对应 24 个真实事件；"
                + "其中 29 次存活不到 15 秒（系统测试/抖动，最短 1 秒）。"
                + "所以双黄先给轻提醒，持续超过下面这个秒数才升级为超强提醒。");
        dyHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        dyHint.setTextColor(0xFF78909C);
        dyHint.setPadding(0, dp(4), 0, dp(8));
        root.addView(dyHint);

        dySecondsBox = number(root, "双黄升级阈值（秒）", String.valueOf(p.dyEscalateSec),
                "存活超过它就认为不是测试。默认 15。");

        // ---- 显示延时（对齐有延迟的直播画面）----
        header(root, "显示延时");
        label(root, "把整个显示滞后 N 秒，用来对齐有延迟的电视 / 直播画面。",
                "电视比官方计时源晚几秒，不延时的话 App 会「剧透」电视上还没发生的画面"
                        + "（成绩、圈速、圆环、旗语、通报、提醒都一起滞后，互相之间不会打架）。"
                        + "0 = 关闭。调大之后画面会先停住 N 秒再继续 —— 因为它要开始显示"
                        + "「N 秒之前」；调小立即生效。");
        delayBox = number(root, "消息延时（秒）", String.valueOf(p.delaySec),
                "0 - " + DelayGate.MAX_SECONDS + "，默认 0。");

        Button save = new Button(this);
        save.setText("保存");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(18);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                collect();
                p.save(SettingsActivity.this);
                finish();
            }
        });
        root.addView(save, sp);

        Button defaults = new Button(this);
        defaults.setText("恢复默认");
        defaults.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                p = new Prefs();
                fill();
            }
        });
        root.addView(defaults);

        // ----------------------------------------------------------
        // 关于
        // ----------------------------------------------------------
        // 用户要的：设置页里能直接看出手机装的是哪一版。
        // 这比「看 APK 文件名」可靠 —— 文件名是下载时定的，
        // 装上去的到底是哪一版，只有包自己知道。
        header(root, "关于");
        label(root, versionText(),
                "装机核对用。APK 每构建一次 versionCode 就 +1，"
                        + "同一个版本号不会对应两个不同的包。");

        scroll.addView(root);
        setContentView(scroll);
    }

    // ------------------------------------------------------------------
    // 控件工厂
    // ------------------------------------------------------------------

    private void header(LinearLayout root, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(0xFF00695C);
        t.setPadding(0, dp(16), 0, dp(6));
        root.addView(t);
    }

    private EditText field(LinearLayout root, String label, String value, String hint) {
        label(root, label, hint);
        EditText e = new EditText(this);
        e.setText(value == null ? "" : value);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setSingleLine(true);
        root.addView(e);
        return e;
    }

    private EditText multiline(LinearLayout root, String label, String value, String hint) {
        label(root, label, hint);
        EditText e = new EditText(this);
        e.setText(value == null ? "" : value);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setMinLines(2);
        e.setGravity(Gravity.TOP | Gravity.START);
        root.addView(e);
        return e;
    }

    private EditText number(LinearLayout root, String label, String value, String hint) {
        label(root, label, hint);
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(e);
        return e;
    }

    private CheckBox check(LinearLayout root, String text, boolean value, String hint) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        c.setChecked(value);
        root.addView(c);
        if (hint != null) {
            label(root, null, hint);
        }
        return c;
    }

    private void label(LinearLayout root, String text, String hint) {
        if (text != null) {
            TextView t = new TextView(this);
            t.setText(text);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            t.setPadding(0, dp(8), 0, dp(2));
            root.addView(t);
        }
        if (hint != null) {
            TextView h = new TextView(this);
            h.setText(hint);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            h.setTextColor(0xFF90A4AE);
            h.setPadding(0, 0, 0, dp(2));
            root.addView(h);
        }
    }

    /**
     * 「版本 2.0.9（versionCode 10）」。
     *
     * ⚠️ 不能用 BuildConfig.VERSION_NAME —— 这工程不走 Gradle，没有 BuildConfig。
     * ⚠️ 也不能用 PackageInfo.getLongVersionCode() —— 那是 API 28 才有的方法，
     *    而这台设备是 Android 6.0、编译平台是 android-23，只能读 versionCode 字段。
     *
     * 外面套 try 不是洁癖：PackageManager 这个方法名义上会抛
     * NameNotFoundException，读自己的包名当然不会，
     * 但设置页没必要为这种理论情况崩掉。
     */
    private String versionText() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return "版本 " + info.versionName
                    + "（versionCode " + info.versionCode + "）";
        } catch (Exception e) {
            return "版本信息读取失败：" + e.getClass().getSimpleName();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int parse(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    // ------------------------------------------------------------------

    private void collect() {
        p.flashEnabled = flashBox.isChecked();
        p.keepScreenOn = keepScreenBox.isChecked();
        p.noiseFilterEnabled = noiseBox.isChecked();
        p.noiseHideBlue = hideBlueBox.isChecked();
        p.noiseHideClear = hideClearBox.isChecked();
        p.noiseHideDeleted = hideDeletedBox.isChecked();
        p.carFilter = carBox.getText().toString().trim();
        p.excludeKeywords = Prefs.splitLines(excludeBox.getText().toString());

        // 显示延时：0 = 关闭；上限用 DelayGate 的口径，别在两处各写一个数
        p.delaySec = Prefs.clamp(
                parse(delayBox.getText().toString(), p.delaySec),
                0, DelayGate.MAX_SECONDS);

        p.soundEnabled = soundBox.isChecked();
        p.vibrateEnabled = vibrateBox.isChecked();
        p.screenWakeEnabled = wakeBox.isChecked();
        p.silentOverride = silentBox.isChecked();
        p.attentionEnabled = attentionBox.isChecked();
        p.alarmAutoStopSec = Prefs.clamp(parse(autoStopBox.getText().toString(), 15), 0, 120);
        p.cooldownSec = Prefs.clamp(parse(cooldownBox.getText().toString(), 60), 0, 600);
        p.dyMode = Prefs.clamp(dySpinner.getSelectedItemPosition(), 0, 3);
        p.dyEscalateSec = Prefs.clamp(parse(dySecondsBox.getText().toString(), 15), 1, 120);

        p.replayUri = pendingUri;
        p.replayName = pendingName;
        p.replaySpeed = chosenSpeed();

        autoStopBox.setText(String.valueOf(p.alarmAutoStopSec));
        cooldownBox.setText(String.valueOf(p.cooldownSec));
        dySecondsBox.setText(String.valueOf(p.dyEscalateSec));
    }

    private void fill() {
        flashBox.setChecked(p.flashEnabled);
        keepScreenBox.setChecked(p.keepScreenOn);
        noiseBox.setChecked(p.noiseFilterEnabled);
        hideBlueBox.setChecked(p.noiseHideBlue);
        hideClearBox.setChecked(p.noiseHideClear);
        hideDeletedBox.setChecked(p.noiseHideDeleted);
        carBox.setText(p.carFilter);
        excludeBox.setText(Prefs.joinLines(p.excludeKeywords));
        soundBox.setChecked(p.soundEnabled);
        vibrateBox.setChecked(p.vibrateEnabled);
        wakeBox.setChecked(p.screenWakeEnabled);
        silentBox.setChecked(p.silentOverride);
        attentionBox.setChecked(p.attentionEnabled);
        autoStopBox.setText(String.valueOf(p.alarmAutoStopSec));
        cooldownBox.setText(String.valueOf(p.cooldownSec));
        dySpinner.setSelection(Prefs.clamp(p.dyMode, 0, 3));
        dySecondsBox.setText(String.valueOf(p.dyEscalateSec));
        if (speedSpinner != null) {
            speedSpinner.setSelection(speedIndex(p.replaySpeed));
        }
        pendingUri = p.replayUri == null ? "" : p.replayUri;
        pendingName = p.replayName == null ? "" : p.replayName;
        showReplayState(null);
    }

    // ------------------------------------------------------------------
    // 回放文件：选一个 .rclog
    // ------------------------------------------------------------------

    /**
     * 让用户挑一个 {@code .rclog}。
     *
     * ★ 用系统的文件选择器（ACTION_OPEN_DOCUMENT），**不申请任何存储权限**。
     *   Android 6 上读 /sdcard/Download 是要运行时权限的（得弹窗、还得处理
     *   "用户拒绝"，为了放一个文件不值）。SAF 把访问权按文件发给 App，
     *   文件在哪儿都行，连网盘里的都能选。
     */
    private void pickReplayFile() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        try {
            startActivityForResult(it, REQ_PICK_RCLOG);
        } catch (Throwable t) {
            showReplayState("这台设备上没有可用的文件选择器：" + t);
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_PICK_RCLOG) {
            return;
        }
        if (result != RESULT_OK || data == null || data.getData() == null) {
            showReplayState("没有选择文件。");
            return;
        }
        android.net.Uri uri = data.getData();
        // 尽量把这个文件的访问权"记住"，否则重启 App 后可能就读不到了
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
            // 有些来源（比如纯 file:// 的）本来就不支持持久授权，不影响本次使用
        }
        pendingUri = uri.toString();
        pendingName = displayName(uri);
        showReplayState("已选择，正在读文件信息…");
        readReplayMeta(uri);
    }

    /** 从选择器给的 URI 里挖出个像样的文件名。挖不到就用 URI 尾巴。 */
    private String displayName(android.net.Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null,
                    null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex(
                            android.provider.OpenableColumns.DISPLAY_NAME);
                    if (c.moveToFirst() && idx >= 0) {
                        String s = c.getString(idx);
                        if (s != null && s.length() > 0) {
                            return s;
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
            // 查不到就退回 URI
        }
        String tail = uri.getLastPathSegment();
        return tail == null ? "回放文件" : tail;
    }

    /**
     * 读文件自带的元信息来显示"这是什么比赛"。
     *
     * ★ 必须在后台线程：文件可能在网盘上，主线程读它一样会抛
     *   NetworkOnMainThreadException —— 这正是 v3.1.1 修的那个坑。
     */
    private void readReplayMeta(final android.net.Uri uri) {
        new Thread(new Runnable() {
            public void run() {
                ReplayClient.Meta m = null;
                java.io.InputStream in = null;
                try {
                    in = getContentResolver().openInputStream(uri);
                    m = ReplayClient.readMeta(in);
                } catch (Throwable ignored) {
                    // 读不出来就走下面的兜底文案
                }
                final String line;
                if (m == null) {
                    line = "已选择：" + pendingName
                            + "\n这个文件没有 RCLOG1 元信息头，"
                            + "读不出是哪场比赛 —— 但照样能放。";
                } else {
                    pendingName = m.label();
                    line = "已选择：" + m.label() + "\n" + m.detail()
                            + (m.note.length() > 0 ? "\n" + m.note : "");
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        showReplayState(line);
                    }
                });
            }
        }, "rclog-meta").start();
    }

    /** 把当前回放状态写到那行小字上。extra 非空就顶掉默认文案。 */
    private void showReplayState(String extra) {
        if (replayDetail == null) {
            return;
        }
        if (extra != null) {
            replayDetail.setText(extra + (pendingUri.length() == 0
                    ? "\n（保存后生效）" : "\n（点「保存」后生效）"));
            return;
        }
        if (pendingUri.length() == 0) {
            replayDetail.setText("当前模式：连 F1 官方公开流（实时）。"
                    + "比赛开始时用这个。");
            return;
        }
        replayDetail.setText("当前模式：回放 " + pendingName
                + "\n点「保存」后返回主界面就会开始放。");
    }

    private static int speedIndex(int speed) {
        for (int i = 0; i < SPEEDS.length; i++) {
            if (SPEEDS[i] == speed) {
                return i;
            }
        }
        return 2;                       // 默认识别不出来的话给 60 倍速
    }

    private int chosenSpeed() {
        int i = speedSpinner == null ? 2 : speedSpinner.getSelectedItemPosition();
        if (i < 0 || i >= SPEEDS.length) {
            i = 2;
        }
        return SPEEDS[i];
    }

    /** 测试官方流是否可达。只做一次握手，不建长连接。 */
    private void testConnection() {
        collect();
        testResult.setTextColor(0xFF546E7A);
        testResult.setText("测试中…");
        new Thread(new Runnable() {
            public void run() {
                // 不能写成 final 再在 try/catch 里各赋一次 —— Java 的
                // "明确赋值"规则不允许（试过，编译报 might already have been
                // assigned）。先算普通局部变量，再收进 final 给匿名类用。
                String m;
                boolean good;
                try {
                    m = F1Client.testConnectivity();
                    good = true;
                } catch (Throwable t) {
                    m = t.getMessage() == null ? t.toString() : t.getMessage();
                    good = false;
                }
                final String msg = m;
                final boolean ok = good;
                runOnUiThread(new Runnable() {
                    public void run() {
                        testResult.setTextColor(ok ? 0xFF2E7D32 : 0xFFC62828);
                        testResult.setText(msg);
                    }
                });
            }
        }, "f1-test").start();
    }
}
