package com.haf1.racecontrol;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 安卓端的 {@code .rctrans} 翻译库：存在应用私有目录，导入/导出走系统文件选择器。
 *
 * 用户要的："用类似配置导入导出的方法，做成 .rctrans 格式文件，内部仍为纯文本"。
 * 好处很具体：**改译文不用等新版 APK** —— 在电脑上改好一个 .rctrans，
 * 传到手机上用「设置 → 导入翻译」选一下就行。
 *
 * 为什么存在私有目录而不是 assets：assets 是只读的、必须重新打包；
 * 私有目录里的文件可以随时被"导入"覆盖，而且**原样保存**（用户写的注释都留着，
 * 下次导出/再看还在）。
 *
 * 导入失败（选错文件、编码不对）不崩：{@link RcTrans#parse} 逐行跳坏行，
 * 一条有效词条都没有就当没导入，走内置译文。
 */
final class TransStore {

    private TransStore() {
    }

    /** 导入的翻译文件放哪。 */
    static File file(Context c) {
        return new File(c.getFilesDir(), "translations" + RcTrans.EXT);
    }

    /**
     * 启动、以及从设置页回到主界面时调用：有导入文件就挂上，没有就走内置。
     *
     * @return 当前生效的用户词条数（0 = 用内置）
     */
    static int load(Context c) {
        try {
            File f = file(c);
            if (!f.isFile()) {
                Translator.setUserTable(null);
                return 0;
            }
            java.util.LinkedHashMap<String, String> m = RcTrans.parse(readText(f));
            Translator.setUserTable(m.isEmpty() ? null : m);
            return m.size();
        } catch (Exception e) {
            Translator.setUserTable(null);
            return 0;
        }
    }

    /**
     * 从用户选的文件导入。
     *
     * @return 生效的词条数；0 表示文件里没有一条能用的（没写入，保持原状）
     */
    static int importFrom(Context c, Uri uri) throws IOException {
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("打不开这个文件");
        }
        String text;
        try {
            text = readAll(in);
        } finally {
            in.close();
        }
        java.util.LinkedHashMap<String, String> m = RcTrans.parse(text);
        if (m.isEmpty()) {
            return 0;
        }
        // 原样存下来：用户写的注释/顺序都留着，下次导出还能看到自己的备注
        writeText(file(c), text);
        Translator.setUserTable(m);
        return m.size();
    }

    /** 把**当前生效的完整词表**（内置 + 已导入的覆盖）导出到用户选的位置。 */
    static void exportTo(Context c, Uri uri) throws IOException {
        OutputStream out = c.getContentResolver().openOutputStream(uri);
        if (out == null) {
            throw new IOException("写不进这个位置");
        }
        try {
            out.write(Translator.currentTrans().getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    /** 恢复默认：删掉导入的文件，回到内置译文。 */
    static void reset(Context c) {
        File f = file(c);
        if (f.isFile()) {
            // 删不掉也不要紧：下面这句已经把内存里的覆盖清掉了
            f.delete();
        }
        Translator.setUserTable(null);
    }

    // ------------------------------------------------------------------

    private static String readText(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static void writeText(File f, String text) throws IOException {
        OutputStream out = new FileOutputStream(f);
        try {
            out.write(text.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }
}
