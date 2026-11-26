package com.dsh.splashskip;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 打开应用后的 10 秒内，每秒扫 10 次屏幕；见到「跳过 / 关闭」就点，
 * 最多连续点 3 次，然后彻底停手，等下一次切应用。
 */
public class SkipService extends AccessibilityService {

    private static final Set<String> IGNORE = new HashSet<String>(Arrays.asList(
            "com.android.systemui", "android", "com.android.settings",
            "com.android.permissioncontroller", "com.google.android.packageinstaller",
            "com.android.packageinstaller", "com.android.launcher3",
            "com.miui.home", "com.miui.securitycenter", "com.miui.personalassistant",
            "com.miui.globalassistant", "com.miui.voiceassist", "com.miui.screenshot",
            "com.android.quicksearchbox", "com.google.android.googlequicksearchbox",
            "com.huawei.android.launcher", "com.oppo.launcher", "com.vivo.launcher",
            "com.bbk.launcher2", "com.sec.android.app.launcher",
            "com.android.inputmethod.latin", "com.sohu.inputmethod.sogou",
            "com.iflytek.inputmethod", "com.baidu.input", "com.tencent.qqpinyin",
            "io.github.ccch1mneyyy.dsh_app",
            "com.google.android.documentsui", "com.android.documentsui",
            "com.android.intentresolver", "com.android.providers.media.module",
            "com.miui.filemanager", "com.android.mtp"));

    private static final long WINDOW_MS = 20000;  // 前 20 秒密集扫
    private static final long SCAN_MS = 100;      // 每秒 10 次
    private static final long SCAN_SLOW_MS = 200; // 之后每秒 5 次
    private static final long EXTRA_MS = 5000;    // 三发用完后再续 5 秒
    private static final int MAX_CLICKS = 3;      // 头三发
    private static final int MAX_EXTRA_CLICKS = 3;// 额外的三发（每次只点一下）

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final List<String> TEXTS = new ArrayList<String>();

    private String huntPkg;
    private long startedAt;
    private int clicks;
    private boolean finished;
    private boolean scanning;
    private long extraUntil;
    private int blankTaps;
    private int okClicks;
    private long lastBlankTap;
    private boolean cornerFallback;
    private boolean cornerTried;
    private String lastTarget;
    private long lastClickAt;
    private boolean loggedNoCand;
    private boolean windowLogged;
    private static volatile SkipService INSTANCE;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (finished || huntPkg == null) return;
            long now = SystemClock.uptimeMillis();
            if (extraUntil > 0) {
                if (now > extraUntil) return;
            } else if (now - startedAt > WINDOW_MS) {
                return;
            }
            if (scanning) {
                h.postDelayed(this, clicks >= MAX_CLICKS ? SCAN_SLOW_MS : SCAN_MS);
                return;
            }
            scanning = true;
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        scan();
                    } catch (Throwable t) {
                        Dbg.note("扫描异常 " + t);
                    } finally {
                        scanning = false;
                    }
                    h.postDelayed(tick, clicks >= MAX_CLICKS ? SCAN_SLOW_MS : SCAN_MS);
                }
            });
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
        Store.serviceOn = true;
        SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
        cornerFallback = sp.getBoolean("corner_v4", false);
        Store.enabled = sp.getBoolean("master", true);
        Dbg.init(this);
        Dbg.note("服务已连接" + (Store.enabled ? "" : "（总开关关闭）"));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null) return;
        Store.eventCount++;
        CharSequence cs = e.getPackageName();
        if (cs == null) return;
        String pkg = cs.toString();
        Store.lastPkg = pkg;
        if (!Store.enabled) {
            cancel("总开关关闭");
            return;
        }
        if (pkg.equals(getPackageName())) {
            cancel("回到本应用");
            return;
        }
        if (IGNORE.contains(pkg)) {
            // 桌面、最近任务、状态栏、系统界面：直接终断，不再等时间到
            if (huntPkg != null && SystemClock.uptimeMillis() - startedAt > 1000) {
                cancel("前台是桌面/系统界面");
            }
            return;
        }

        if (!pkg.equals(huntPkg)) {
            huntPkg = pkg;
            startedAt = SystemClock.uptimeMillis();
            clicks = 0;
            okClicks = 0;
            extraUntil = 0;
            blankTaps = 0;
            lastBlankTap = 0;
            finished = false;
            cornerTried = false;
            loggedNoCand = false;
            windowLogged = false;
            lastTarget = null;
            SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
                cornerFallback = sp.getBoolean("corner_v4", false);
            Dbg.note("开始扫描 " + pkg + (cornerFallback ? " (兜底开)" : " (兜底关)"));
            h.removeCallbacks(tick);
            h.post(tick);
        }
    }

    /** 立刻终止当前这场扫描，直到下一次切应用。 */
    private void cancel(String why) {
        if (huntPkg != null) Dbg.note("终止扫描：" + why);
        huntPkg = null;
        finished = true;
        extraUntil = 0;
        h.removeCallbacks(tick);
    }

    private void scan() {
        if (finished || huntPkg == null) return;
        long now = SystemClock.uptimeMillis();

        TEXTS.clear();
        List<Candidate> list = new ArrayList<Candidate>();
        int[] total = new int[]{0};
        int winCount = 0;
        StringBuilder per = new StringBuilder();

        // A 方案：把所有窗口都扫一遍（菜鸟那种广告在独立小窗里）
        List<AccessibilityWindowInfo> wins = null;
        try {
            wins = getWindows();
        } catch (Throwable ignored) {
        }
        if (wins != null) {
            for (AccessibilityWindowInfo w : wins) {
                AccessibilityNodeInfo r = null;
                try {
                    r = w.getRoot();
                } catch (Throwable ignored) {
                }
                if (r == null) continue;
                CharSequence rp = r.getPackageName();
                String pkg = rp == null ? "?" : rp.toString();
                if (pkg.equals(getPackageName()) || IGNORE.contains(pkg)) continue;
                int[] c = new int[]{0};
                try {
                    collect(r, list, 0, c);
                } catch (Throwable ignored) {
                }
                winCount++;
                total[0] += c[0];
                per.append(c[0]).append(',');
            }
        }
        if (winCount == 0) {
            AccessibilityNodeInfo root = null;
            try {
                root = getRootInActiveWindow();
            } catch (Throwable ignored) {
            }
            if (root == null) return;
            CharSequence rp = root.getPackageName();
            String pkg = rp == null ? "?" : rp.toString();
            if (pkg.equals(getPackageName()) || IGNORE.contains(pkg)) return;
            try {
                collect(root, list, 0, total);
            } catch (Throwable ignored) {
            }
            winCount = 1;
        }

        Store.scanCount++;
        Store.nodeCount = total[0];
        if (!windowLogged) {
            windowLogged = true;
            Dbg.note("窗口 " + winCount + " 个 节点[" + per + "] 包 " + huntPkg);
        }

        if (list.isEmpty()) {
            if (!loggedNoCand) {
                loggedNoCand = true;
                Dbg.note("无候选 " + huntPkg + " 窗口 " + winCount + " 节点 " + total[0]
                        + " 文本 " + joinTexts());
            }
            // 什么都读不到：无脑点右上角（最多 3 次，间隔 900ms）
            if (cornerFallback && okClicks == 0 && blankTaps < 3 && now - lastBlankTap > 900) {
                blankTaps++;
                lastBlankTap = now;
                tapFixedCorner();
                Dbg.note("无候选，盲点右上角 第 " + blankTaps + " 次 " + huntPkg);
                if (blankTaps >= 3) {
                    // 三档盲点做完：立刻收手，不再扫描也不再点
                    finished = true;
                    extraUntil = 0;
                    h.removeCallbacks(tick);
                    Dbg.note("三档盲点完成，终止全部操作");
                }
            }

            return;
        }

        Candidate best = null;
        for (Candidate c : list) if (best == null || c.score > best.score) best = c;
        if (best == null) return;

        final Candidate target = best;
        final String pkg = huntPkg;
        final boolean sameAsLast = target.text.equals(lastTarget) && now - lastClickAt < 900;
        if (clicks >= MAX_CLICKS && target.text.equals(lastTarget)
                && now - lastClickAt < 600) {
            return;   // 补充阶段：同一个按钮别连点
        }

        h.post(new Runnable() {
            @Override
            public void run() {
                Rect r = new Rect();
                String where = "";
                try {
                    target.node.getBoundsInScreen(r);
                    where = " @(" + r.centerX() + "," + r.centerY() + ")";
                } catch (Throwable ignored) {
                }
                Dbg.note((sameAsLast ? "补点 " : "点击 ") + pkg + " → " + target.text + where
                        + " 第 " + (clicks + 1) + " 次");
                lastTarget = target.text;
                lastClickAt = SystemClock.uptimeMillis();
                clicks++;
                if (perform(target)) {
                    Store.hitCount++;
                    okClicks++;
                    Store.log(pkg, target.text);
                }
                if (clicks == MAX_CLICKS && extraUntil == 0) {
                    extraUntil = SystemClock.uptimeMillis() + EXTRA_MS;
                    Dbg.note("头三发用完，进入补充阶段：每秒 5 次，再续 5 秒");
                }
                if (clicks >= MAX_CLICKS + MAX_EXTRA_CLICKS) {
                    finished = true;
                    Dbg.note("补充三发也用完，停止扫描");
                    extraUntil = 0;
                }
            }
        });
    }

    private static final class Candidate {
        AccessibilityNodeInfo node;
        String text;
        int score;
        boolean gesture;
    }

    private void collect(AccessibilityNodeInfo n, List<Candidate> out, int depth, int[] count) {
        if (n == null || depth > 40 || count[0] > 3000) return;
        count[0]++;

        CharSequence t0 = n.getText();
        CharSequence d0 = n.getContentDescription();
        String tt = (t0 == null ? "" : t0.toString()).trim();
        if (!tt.isEmpty() && tt.length() <= 20 && TEXTS.size() < 40 && !TEXTS.contains(tt)) {
            TEXTS.add(tt);
        }
        String txt = (tt + " " + (d0 == null ? "" : d0.toString())).trim();
        if (!txt.isEmpty() && txt.length() <= 40) {
            int sc = score(txt);
            if (sc > 0 && (!inScrollable(n) || txt.length() <= 6)) {
                AccessibilityNodeInfo clickable = clickableSelf(n);
                Candidate c = new Candidate();
                c.node = clickable != null ? clickable : n;
                c.text = txt.length() > 20 ? txt.substring(0, 20) : txt;
                c.score = sc + (clickable != null ? 20 : 0);
                c.gesture = clickable == null && hasArea(n);
                out.add(c);
            }
        }
        int kids = n.getChildCount();
        for (int i = 0; i < kids; i++) {
            try {
                collect(n.getChild(i), out, depth + 1, count);
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean inScrollable(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 3 && cur != null; i++) {
            try {
                if (cur.isScrollable()) return true;
                cur = cur.getParent();
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    private static AccessibilityNodeInfo clickableSelf(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 5 && cur != null; i++) {
            try {
                if (cur.isClickable()) return cur;
                cur = cur.getParent();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static boolean hasArea(AccessibilityNodeInfo n) {
        try {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            return r.width() > 8 && r.height() > 8 && r.width() < 1400 && r.height() < 700;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 见到「跳过 / 关闭广告 / 关闭」就点；不用 x、×、单字母（误伤太大）。 */
    private int score(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replace(" ", "").replace("\u00a0", "");
        if (s.isEmpty()) return 0;

        if (s.contains("跳过")) {
            if (s.contains("开屏")) return 0;
            if (s.length() > 16) return 0;
            return s.contains("广告") ? 140 : 130;
        }
        if (s.contains("关闭广告") || s.contains("关闭开屏")) return 135;
        if (s.startsWith("skip") || s.endsWith("skip")) return 120;
        if (s.length() <= 4 && s.contains("关闭")) return 80;
        return 0;
    }

    private boolean tapCornerNode(AccessibilityNodeInfo root) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels, hh = dm.heightPixels;
        List<AccessibilityNodeInfo> hits = new ArrayList<AccessibilityNodeInfo>();
        collectCorner(root, hits, 0, w, hh);
        if (hits.isEmpty()) return false;
        AccessibilityNodeInfo best = null;
        Rect br = new Rect(), r = new Rect();
        for (AccessibilityNodeInfo n : hits) {
            n.getBoundsInScreen(r);
            if (best == null || r.width() * (long) r.height() < br.width() * (long) br.height()) {
                best = n;
                best.getBoundsInScreen(br);
            }
        }
        try {
            if (best.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            best.getBoundsInScreen(r);
            return tap(r.centerX(), r.centerY());
        } catch (Throwable t) {
            return false;
        }
    }

    private void collectCorner(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out,
                               int depth, int w, int hh) {
        if (n == null || depth > 40 || out.size() > 8) return;
        try {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            boolean inCorner = r.left > w * 0.62f && r.top < hh * 0.16f
                    && r.width() > 12 && r.width() < w * 0.35f
                    && r.height() > 12 && r.height() < hh * 0.12f;
            boolean blank = (n.getText() == null || n.getText().length() == 0)
                    && (n.getContentDescription() == null || n.getContentDescription().length() == 0);
            if (inCorner && blank && n.isClickable() && n.isVisibleToUser()) out.add(n);
        } catch (Throwable ignored) {
        }
        int kids = n.getChildCount();
        for (int i = 0; i < kids; i++) {
            try {
                collectCorner(n.getChild(i), out, depth + 1, w, hh);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 三档盲点：菜鸟（贴顶）、百度网盘（状态栏下）、带标题栏的样式。 */
    private static final float[][] BLIND = {
            {0.87f, 0.065f}, {0.86f, 0.10f}, {0.87f, 0.135f}
    };

    private void tapFixedCorner() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int i = Math.min(blankTaps, BLIND.length) - 1;
        if (i < 0) i = 0;
        tap((int) (dm.widthPixels * BLIND[i][0]), (int) (dm.heightPixels * BLIND[i][1]));
    }

    private boolean tap(int x, int y) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(new GestureDescription.StrokeDescription(p, 0, 50));
            return dispatchGesture(b.build(), null, null);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean perform(Candidate c) {
        AccessibilityNodeInfo node = c.node;
        if (node == null) return false;
        try {
            if (node.isClickable()) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        } catch (Throwable ignored) {
        }
        if (c.gesture) {
            try {
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                if (r.width() <= 0 || r.height() <= 0) return false;
                return tap(r.centerX(), r.centerY());
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static String joinTexts() {
        StringBuilder sb = new StringBuilder("[");
        for (String t : TEXTS) {
            if (sb.length() > 300) { sb.append("…"); break; }
            sb.append(t).append('|');
        }
        return sb.append(']').toString();
    }

    public static String selfTest() {
        SkipService s = INSTANCE;
        if (s == null) return "服务未运行";
        AccessibilityNodeInfo r = null;
        try {
            r = s.getRootInActiveWindow();
        } catch (Throwable ignored) {
        }
        if (r == null) return "读不到窗口（root=null）";
        List<Candidate> l = new ArrayList<Candidate>();
        int[] c = new int[]{0};
        try {
            s.collect(r, l, 0, c);
        } catch (Throwable ignored) {
        }
        return "读到 " + c[0] + " 个节点，其中候选 " + l.size() + " 个";
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        h.removeCallbacks(tick);
        worker.shutdownNow();
        Dbg.note("服务销毁");
        Dbg.flushNow();
        INSTANCE = null;
        Store.serviceOn = false;
        super.onDestroy();
    }
}
