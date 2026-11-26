package com.dsh.splashskip;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 把诊断日志写到 下载/DSH-Go/splash-debug.txt，方便外面看。 */
public final class Dbg {
    private static final Object LOCK = new Object();
    private static final List<String> LINES = new ArrayList<String>();
    private static Context ctx;
    private static long lastWrite;
    private static boolean busy;

    private Dbg() {}

    public static void init(Context c) {
        ctx = c.getApplicationContext();
        note("app 启动，版本 " + version());
    }

    private static String version() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    public static void note(String s) {
        synchronized (LOCK) {
            String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            LINES.add(t + "  " + s);
            while (LINES.size() > 300) LINES.remove(0);
        }
        flush(false);
    }

    /** 强制落盘（重要节点调用）。 */
    public static void flushNow() {
        flush(true);
    }

    private static void flush(boolean force) {
        synchronized (LOCK) {
            if (ctx == null || busy) return;
            long now = System.currentTimeMillis();
            if (!force && now - lastWrite < 1500) return;
            lastWrite = now;
            busy = true;
            final String body = join();
            final Context c = ctx;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        write(c, body);
                    } catch (Throwable ignored) {
                    } finally {
                        synchronized (LOCK) { busy = false; }
                    }
                }
            }).start();
        }
    }

    private static String join() {
        StringBuilder sb = new StringBuilder();
        for (String s : LINES) sb.append(s).append('\n');
        return sb.toString();
    }

    private static void write(Context c, String body) throws Exception {
        if (Build.VERSION.SDK_INT < 29) return;
        ContentResolver r = c.getContentResolver();
        Uri col = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        try {
            r.delete(col, MediaStore.Downloads.DISPLAY_NAME + "=?", new String[]{"splash-debug.txt"});
        } catch (Throwable ignored) {
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, "splash-debug.txt");
        v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DSH-Go");
        v.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = r.insert(col, v);
        if (uri == null) return;
        OutputStream os = r.openOutputStream(uri, "wt");
        if (os != null) {
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        r.update(uri, done, null, null);
    }
}
