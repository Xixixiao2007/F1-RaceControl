package com.haf1.racecontrol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * `.rctrans` 翻译库文件：**纯文本**，分三段。
 *
 * <p>用户原话："用类似配置导入导出的方法，做成 .rctrans 格式文件，内部仍为纯文本"，
 * 后来又补了两问："需要正则匹配带变量的内容呢？车手译名呢？" —— 所以格式长这样：</p>
 *
 * <pre>
 * !rctrans 3
 * # 井号开头是注释，空行忽略
 *
 * [phrases]
 * # 短语 -> 中文（消息里包含这个短语时用它）
 * CAUSING A COLLISION = 造成碰撞
 *
 * [drivers]
 * # 三位缩写 -> 中文姓氏（直接生效；只按缩写精确查，不会误伤别的句子）
 * VER = 维斯塔潘
 *
 * [patterns]
 * # 带变量的正则覆盖：取消注释即生效，命中后**整条译文**就是你的模板。
 * # {0} = 整条匹配，{1}{2}… = 第 1/2 个捕获组
 * # FIA STEWARDS:\s*REPRIMAND.*?CAR\s+(\d+)\s*\(([A-Z]{3})\).* = ★ 训诫：{2}（{1} 号车）
 * </pre>
 *
 * <p>三段的作用方式不同，这是有意的：</p>
 * <ul>
 *   <li><b>[phrases]</b> 按"最长包含匹配"生效 —— 消息里带着车号也认；</li>
 *   <li><b>[drivers]</b> 是精确查表（只看三位缩写），所以放进去**不会**误命中；</li>
 *   <li><b>[patterns]</b> 是**覆盖**：正则命中就整条换成你的模板，不再走内置规则。
 *       默认导出时整段被注释掉（★ 否则自动生成的文件会悄悄改掉现有译文），
 *       想接管哪条就把哪行的 {@code #} 去掉。</li>
 * </ul>
 *
 * <p>纯逻辑（不碰文件、不碰 android.*），两端共用、可离线单测。</p>
 */
public final class RcTrans {

    /** 文件扩展名（导出的文件名默认带它）。 */
    public static final String EXT = ".rctrans";
    /** 文件头：格式版本 3 = 三段式（phrases / drivers / patterns）。 */
    public static final String HEADER = "!rctrans 3";

    public static final String SEC_PHRASES = "phrases";
    public static final String SEC_DRIVERS = "drivers";
    /** 任意中文片段的替换表：译文里出现"键"就换成"值"（默认键=值，等于不改）。 */
    public static final String SEC_TEXTS = "texts";
    public static final String SEC_PATTERNS = "patterns";

    private RcTrans() {
    }

    /** 一整份翻译库：短语 + 车手译名 + 中文片段替换 + 正则覆盖。 */
    public static final class Pack {
        public final LinkedHashMap<String, String> phrases =
                new LinkedHashMap<String, String>();
        public final LinkedHashMap<String, String> drivers =
                new LinkedHashMap<String, String>();
        /** 中文片段 -> 替换（键是原文字面量，值是你的写法）。 */
        public final LinkedHashMap<String, String> texts =
                new LinkedHashMap<String, String>();
        public final List<Rule> patterns = new ArrayList<Rule>();

        /** 总条数（界面上报"导入了 N 条"用；注释掉的不算）。 */
        public int size() {
            return phrases.size() + drivers.size() + texts.size() + patterns.size();
        }
    }

    /** 一条"正则 + 中文模板"的覆盖规则。 */
    public static final class Rule {
        /** 原始正则（导回文件时原样写出去）。 */
        public final String regex;
        /** 中文模板，{0}/{1}/… 是捕获组。 */
        public final String template;
        private final Pattern compiled;

        public Rule(String regex, String template) {
            this.regex = regex;
            this.template = template;
            Pattern p = null;
            try {
                p = Pattern.compile(regex);
            } catch (Exception e) {
                // 正则写错不炸整套翻译库：这条就当作废，其它条目照常
                p = null;
            }
            this.compiled = p;
        }

        /** 命中返回替换后的中文，没命中返回 null（调用方继续走内置规则）。 */
        public String apply(String text) {
            if (compiled == null || text == null) {
                return null;
            }
            Matcher m = compiled.matcher(text);
            if (!m.find()) {
                return null;
            }
            return fill(template, m);
        }
    }

    /**
     * 把模板里的 {0}{1}{2}… 换成捕获组；组不存在就留空。
     *
     * 只认一位数以上的 {数字}，其它花括号原样保留 —— 中文模板里偶尔会有
     * 数学花括号，不该被吃掉。
     */
    static String fill(String template, Matcher m) {
        if (template == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(template.length() + 16);
        int i = 0;
        while (i < template.length()) {
            char ch = template.charAt(i);
            if (ch == '{') {
                int close = template.indexOf('}', i + 1);
                if (close > i + 1) {
                    String key = template.substring(i + 1, close);
                    if (isDigits(key)) {
                        int g = Integer.parseInt(key);
                        if (g <= m.groupCount() && g >= 0) {
                            String v = m.group(g);
                            b.append(v == null ? "" : v);
                            i = close + 1;
                            continue;
                        }
                    }
                }
            }
            b.append(ch);
            i++;
        }
        return b.toString();
    }

    private static boolean isDigits(String s) {
        if (s.length() == 0) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 解析整份文件（三段式）。坏行跳过而不报错 —— 手改文件时容易漏个中文
     * 或者正则写坏，为一个手滑把整份词表丢掉比"少一条"糟得多。
     */
    public static Pack parsePack(String text) {
        Pack pack = new Pack();
        if (text == null) {
            return pack;
        }
        String section = SEC_PHRASES;
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i].trim();
            if (raw.length() == 0 || raw.startsWith("#")) {
                continue;
            }
            if (raw.startsWith("[")) {
                int close = raw.indexOf(']');
                if (close > 1) {
                    section = raw.substring(1, close).trim().toLowerCase(Locale.US);
                }
                continue;
            }
            if (raw.startsWith("!")) {
                continue;                    // 文件头
            }
            if (SEC_PATTERNS.equals(section)) {
                // 正则里可能有等号（(?i)= 之类），所以只按「空格等号空格」切
                int eq = raw.indexOf(" = ");
                if (eq <= 0) {
                    continue;
                }
                String re = raw.substring(0, eq).trim();
                String tpl = raw.substring(eq + 3).trim();
                if (re.length() == 0 || tpl.length() == 0) {
                    continue;
                }
                Rule r = new Rule(re, tpl);
                if (r.compiled != null) {
                    pack.patterns.add(r);
                }
                continue;
            }
            int eq = raw.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = raw.substring(0, eq).trim();
            String val = raw.substring(eq + 1).trim();
            if (key.length() == 0 || val.length() == 0) {
                continue;
            }
            if (SEC_DRIVERS.equals(section)) {
                pack.drivers.put(key.toUpperCase(Locale.US), val);
            } else if (SEC_TEXTS.equals(section)) {
                pack.texts.put(key, val);
            } else {
                pack.phrases.put(key, val);
            }
        }
        return pack;
    }

    /** 只取短语段（兼容旧用法/单测）。 */
    public static LinkedHashMap<String, String> parse(String text) {
        return parsePack(text).phrases;
    }

    // ------------------------------------------------------------------
    // 生成
    // ------------------------------------------------------------------

    /**
     * 生成整份文件。
     *
     * @param pack          正文（patterns 按 {@code commentPatterns} 决定是否注释）
     * @param commentPatterns 正文里的 patterns 是否注释掉
     * @param commentedTail  追加在最后、**始终注释掉**的示例规则（导出时给用户看的模板）。
     *                       为什么要分开：导出"当前词表"时，用户启用的规则要**生效**
     *                       （不注释），而内置示例必须保持注释 —— 否则导出文件
     *                       一旦被当作词库加载，示例规则会突然生效、改掉一批译文。
     */
    public static String formatPack(Pack pack, boolean commentPatterns,
                                    List<Rule> commentedTail) {
        StringBuilder b = new StringBuilder(16384);
        b.append(HEADER).append('\n');
        b.append("# F1-Race Control 翻译库（纯文本，UTF-8）—— 改完保存，重启程序生效\n");
        b.append("# 安卓端：设置 → 翻译库 → 导入翻译（选这个文件）\n");
        b.append("# 分四段：\n");
        b.append("#   [phrases]  短语 -> 中文：消息里包含这个短语就用它（最长匹配优先）\n");
        b.append("#   [drivers]  三位缩写 -> 中文姓氏：精确查表，直接生效\n");
        b.append("#   [texts]    译文里的中文片段替换：左边=程序原本说法，右边=你的说法\n");
        b.append("#              默认左右相同（等于没改），改了就在输出里替换\n");
        b.append("#   [patterns] 正则 + 变量模板：取消注释即生效，命中后整条译文换成模板\n");
        b.append("#              模板里 {0}=整条匹配，{1}{2}…=捕获组\n");
        b.append("# 井号开头是注释，空行忽略。\n");
        b.append('\n');
        b.append('[').append(SEC_PHRASES).append("]\n");
        b.append("# 短语 -> 中文\n");
        if (pack != null) {
            for (Map.Entry<String, String> e : pack.phrases.entrySet()) {
                b.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            }
        }
        b.append('\n');
        b.append('[').append(SEC_DRIVERS).append("]\n");
        b.append("# 车手译名：三位缩写 = 中文姓氏\n");
        if (pack != null) {
            for (Map.Entry<String, String> e : pack.drivers.entrySet()) {
                b.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            }
        }
        b.append('\n');
        b.append('[').append(SEC_TEXTS).append("]\n");
        b.append("# 译文里的中文片段替换：左边 = 程序原本的说法，右边 = 你想让它显示的说法。\n");
        b.append("# 默认左边等于右边（等于没改）。只要改右边，程序输出里出现这个片段就会换成你的。\n");
        b.append("# 长片段优先匹配，所以整句和半个词可以同时存在、不会打架。\n");
        if (pack != null) {
            for (Map.Entry<String, String> e : pack.texts.entrySet()) {
                b.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            }
        }
        b.append('\n');
        b.append('[').append(SEC_PATTERNS).append("]\n");
        b.append("# 带变量的正则覆盖：取消注释（去掉行首 # ）即生效，\n");
        b.append("# 命中后**整条译文**就是你的模板，不再走内置规则。\n");
        b.append("# 每行格式：正则 = 中文模板        （{1}=第1个捕获组，{0}=整条匹配）\n");
        if (pack != null) {
            for (int i = 0; i < pack.patterns.size(); i++) {
                Rule r = pack.patterns.get(i);
                if (commentPatterns) {
                    b.append("# ");
                }
                b.append(r.regex).append(" = ").append(r.template).append('\n');
            }
        }
        if (commentedTail != null) {
            b.append("# ---- 下面是内置示例（默认不生效，去掉行首 # 即可接管那条规则）----\n");
            for (int i = 0; i < commentedTail.size(); i++) {
                Rule r = commentedTail.get(i);
                b.append("# ").append(r.regex).append(" = ")
                        .append(r.template).append('\n');
            }
        }
        return b.toString();
    }

    /** 兼容旧签名：没有额外注释示例。 */
    public static String formatPack(Pack pack, boolean commentPatterns) {
        return formatPack(pack, commentPatterns, null);
    }

    /** 生成只含短语段的文件（兼容旧调用）。 */
    public static String format(Map<String, String> phrases) {
        Pack p = new Pack();
        if (phrases != null) {
            p.phrases.putAll(phrases);
        }
        return formatPack(p, false);
    }

    // ------------------------------------------------------------------
    // 匹配（短语段）
    // ------------------------------------------------------------------

    /**
     * 在短语表里给一段文本找译文。找不到返回 null（调用方回落到内置规则）。
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
        for (Map.Entry<String, String> e : pairs.entrySet()) {
            if (e.getKey().trim().toUpperCase(Locale.US).equals(t)) {
                return e.getValue();
            }
        }
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
     * 不是"用户表直接替换"：用户往往只想改两三条，导入一份只有三条的文件
     * 不该把其它几百条翻译全丢掉。
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
