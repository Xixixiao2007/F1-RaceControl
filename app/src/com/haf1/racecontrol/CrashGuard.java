package com.haf1.racecontrol;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;

/**
 * 把未捕获异常的栈写到文件，下次启动时取出来给用户看。
 *
 * ## 为什么需要
 * App 在手机上闪退时，用户看不到任何东西 —— 没有 logcat、没有开发者选项，
 * 只能说一句"闪退了"。有了这个，崩溃栈会落盘，下次打开 App 直接显示出来，
 * 用户截个图就能发过来。
 *
 * 只在**上一次**崩溃时保留一份（启动时读走即删），不做历史堆积。
 */
public final class CrashGuard {

    private static final String NAME = "last_crash.txt";

    private CrashGuard() {
    }

    /** 装到默认未捕获异常处理器上。可以在 onCreate 里反复调，幂等。 */
    public static void install(final Context ctx) {
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(
                new Thread.UncaughtExceptionHandler() {
                    public void uncaughtException(Thread t, Throwable e) {
                        try {
                            save(ctx, t, e);
                        } catch (Throwable ignored) {
                            // 存不下来也不能再抛：这时进程已经在死了
                        }
                        if (prev != null) {
                            prev.uncaughtException(t, e);
                        }
                    }
                });
    }

    /** 全部吞掉异常：这个方法是在进程将死时跑的，自己绝不能再抛。 */
    private static void save(Context ctx, Thread t, Throwable e) {
        try {
            saveOrThrow(ctx, t, e);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static void saveOrThrow(Context ctx, Thread t, Throwable e)
            throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        PrintWriter w = new PrintWriter(bos);
        w.println("线程: " + (t == null ? "?" : t.getName()));
        w.println("时间: " + new java.util.Date().toString());
        w.println("版本: " + versionText(ctx));
        w.println();
        e.printStackTrace(w);
        w.flush();

        FileOutputStream out = null;
        try {
            out = ctx.openFileOutput(NAME, Context.MODE_PRIVATE);
            out.write(bos.toString().getBytes("UTF-8"));
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }

    /** 读走上一次的崩溃栈（读完删除）。没有就返回空串。 */
    public static String take(Context ctx) {
        File f = ctx.getFileStreamPath(NAME);
        if (f == null || !f.exists()) {
            return "";
        }
        String s = "";
        try {
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int off = 0;
                while (off < b.length) {
                    int n = in.read(b, off, b.length - off);
                    if (n <= 0) {
                        break;
                    }
                    off += n;
                }
            } finally {
                in.close();
            }
            s = new String(b, "UTF-8");
        } catch (Throwable ignored) {
            s = "";
        }
        f.delete();
        return s;
    }

    private static String versionText(Context ctx) {
        try {
            android.content.pm.PackageInfo pi =
                    ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable ignored) {
            return "?";
        }
    }
}
