package com.haf1.racecontrol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * `.rctrans` 翻译库文件：**纯文本**，一行一条「英文短语 = 中文」。
 *
 * 为什么要这个格式（用户原话："用类似配置导入导出的方法，做成 .rctrans 格式文件，
 * 内部仍为纯文本"）：翻译词条散在 Java 代码里时，改一句中文要动代码、重新编译、
 * 两端各发一次包。抽成文件之后：桌面直接把文件放在程序目录里，改完重启就生效；
 * 手机端用系统的"导入文件"选一个 .rctrans 就换掉，不用等新版 APK。
 *
 * 文件长这样（UTF-8）：
 * <pre>
 * !rctrans 1
 * # 井号开头是注释，空行忽略
 * LEAVING THE TRACK AND GAINING AN ADVANTAGE = 离开赛道并获得优势
 * CAUSING A COLLISION = 造成碰撞
 * </pre>
 *
 * 匹配规则（{@link #match}）：
 * <ol>
 *   <li>**整条完全一致**优先；</li>
 *   <li>否则取**最长的、被包含在文本里**的词条（长的先匹配，短的不会抢先吃掉长串）；</li>
 *   <li>**大小写不敏感** —— 官方消息全大写，但用户手写词条时经常小写。</li>
 * </ol>
 *
 * 这个类是纯逻辑（不碰文件、不碰 android.*），两端共用、可离线单测。
 */
public final class RcTrans {

    /** 文件扩展名（导出的文件名默认带它）。 */
    public static final String EXT = ".rctrans";
    /** 文件头：带个版本号，将来格式变了能认出来。 */
    public static final String HEADER = "!rctrans 1";

    private RcTrans() {
    }

    /**
     * 解析文件内容。坏行（没有等号、等号前后为空）**跳过而不报错** ——
     * 用户手改文件时容易多打一个等号或忘了写中文，
     * 为一个手滑把整份翻译库丢掉，比"少一条词"糟得多。
     */
    public static LinkedHashMap<String, String> parse(String text) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (text == null) {
            return out;
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0 || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();
            if (key.length() == 0 || val.length() == 0) {
                continue;
            }
            out.put(key, val);
        }
        return out;
    }

    /** 把词表写成 .rctrans 文本（带文件头和说明注释）。 */
    public static String format(Map<String, String> pairs) {
        StringBuilder b = new StringBuilder(4096);
        b.append(HEADER).append('\n');
        b.append("# F1-Race Control 翻译库（纯文本，UTF-8）\n");
        b.append("# 一行一条：英文短语 = 中文。井号开头是注释，空行忽略。\n");
        b.append("# 改完保存，重启程序生效；手机端用「设置 → 导入翻译」选这个文件。\n");
        b.append("# 匹配：整条完全一致优先，否则取最长的被包含短语；大小写不敏感。\n");
        b.append("#\n");
        if (pairs != null) {
            for (Map.Entry<String, String> e : pairs.entrySet()) {
                b.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            }
        }
        return b.toString();
    }

    /**
     * 在词表里给一段文本找译文。找不到返回 null（调用方回落到内置规则）。
     *
     * 完全一致优先，然后是最长包含匹配 —— 顺序很重要：
     * "FAILING TO FOLLOW RACE DIRECTORS INSTRUCTIONS" 和它后面带限定的长句
     * 同时存在时，必须先命中长句，否则短句会把长句的前半截吃掉。
     */
    public static String match(Map<String, String> pairs, String text) {
        if (pairs == null || pairs.isEmpty() || text == null) {
            return null;
        }
        String t = text.trim().toUpperCase(Locale.US);
        if (t.length() == 0) {
            return null;
        }
        // 1) 整条完全一致
        for (Map.Entry<String, String> e : pairs.entrySet()) {
            if (e.getKey().trim().toUpperCase(Locale.US).equals(t)) {
                return e.getValue();
            }
        }
        // 2) 最长包含匹配
        String bestKey = null;
        String bestVal = null;
        for (Map.Entry<String, String> e : pairs.entrySet()) {
            String k = e.getKey().trim().toUpperCase(Locale.US);
            if (k.length() == 0 || !t.contains(k)) {
                continue;
            }
            if (bestKey == null || k.length() > bestKey.length()) {
                bestKey = k;
                bestVal = e.getValue();
            }
        }
        return bestVal;
    }

    /**
     * 合并：用户表优先，缺的用内置默认补上。
     *
     * 为什么不是"用户表直接替换"：用户往往只想改两三条，
     * 导入一份只有三条的文件不该把其它几百条翻译全丢掉。
     */
    public static LinkedHashMap<String, String> merge(Map<String, String> defaults,
                                                      Map<String, String> user) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (defaults != null) {
            out.putAll(defaults);
        }
        if (user != null) {
            out.putAll(user);
        }
        return out;
    }

    /** 词表的键（给单测/界面显示条数用）。 */
    public static List<String> keys(Map<String, String> pairs) {
        if (pairs == null) {
            return Collections.emptyList();
        }
        return new ArrayList<String>(pairs.keySet());
    }
}
