package com.dsh.splashskip;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 进程内的简易日志 + 诊断计数，供界面展示。 */
public final class Store {
    public static final int MAX = 30;
    private static final List<String> LOG = new ArrayList<String>();
    private static final List<String> DBG = new ArrayList<String>();

    public static volatile boolean serviceOn = false;
    public static volatile boolean enabled = true;   // 总开关
    public static volatile int total = 0;
    public static volatile int eventCount = 0;
    public static volatile int scanCount = 0;
    public static volatile int hitCount = 0;
    public static volatile int nodeCount = 0;
    public static volatile String lastPkg = "-";

    private Store() {}

    public static synchronized void log(String pkg, String label) {
        total++;
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        LOG.add(0, t + "  " + pkg + "  →  " + label);
        while (LOG.size() > MAX) LOG.remove(LOG.size() - 1);
    }

    public static synchronized void dbg(String s) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        DBG.add(0, t + "  " + s);
        while (DBG.size() > 6) DBG.remove(DBG.size() - 1);
    }

    public static synchronized String dump() {
        if (LOG.isEmpty()) return "还没有跳过记录。";
        StringBuilder sb = new StringBuilder();
        for (String s : LOG) sb.append(s).append('\n');
        return sb.toString().trim();
    }

    public static synchronized String dumpDbg() {
        if (DBG.isEmpty()) return "（暂无扫描记录）";
        StringBuilder sb = new StringBuilder();
        for (String s : DBG) sb.append(s).append('\n');
        return sb.toString().trim();
    }
}
