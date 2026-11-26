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
 * 开屏广告跳过。
 * 换应用后：20 秒内每秒扫 10 次，扫到「跳过/关闭」就点，最多三发；
 * 三发打完或广告消失 → 进入 5 秒巡视期（只观察不点）；
 * 巡视期内你一动，或 5 秒到 → 彻底终止，等下一次换应用。
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

    private static final long WINDOW_MS = 20000; // 第一阶段
    private static final long SCAN_MS = 100;     // 每秒 10 次
    private static final int MAX_CLICKS = 3;     // 三发
    private static final long WATCH_MS = 5000;   // 第二阶段

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final List<String> TEXTS = new ArrayList<String>();

    private String huntPkg;
    private long startedAt;
    private int clicks;
    private int okClicks;
    private boolean scanning;
    private boolean watching;
    private long watchUntil;
    private int blankTaps;
    private long lastBlankTap;
    private boolean cornerFallback;
    private String lastTarget;
    private long lastClickAt;
    private String lastTouchPkg;
    private boolean loggedNoCand;
    private boolean windowLogged;
    private static volatile SkipService INSTANCE;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!Store.enabled || !Store.internalOn) return;
            long now = SystemClock.uptimeMillis();

            if (watching) {
                if (now > watchUntil) {
                    endHunt("巡视 5 秒结束");
                } else {
                    h.postDelayed(this, 200);
                }
                return;
            }
            if (huntPkg == null) return;
            if (now - startedAt > WINDOW_MS) {
                endHunt("第一阶段 20 秒到");
                return;
            }
            if (scanning) {
                h.postDelayed(this, SCAN_MS);
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
                    h.postDelayed(tick, SCAN_MS);
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
        int type = e.getEventType();

        boolean touch = type == AccessibilityEvent.TYPE_VIEW_CLICKED
                || type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                || (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    && (e.getScrollDeltaY() != 0 || e.getScrollDeltaX() != 0));

        long now = SystemClock.uptimeMillis();

        // 自己点的那一下不算
        if (touch && now - lastClickAt > 900 && huntPkg != null && pkg.equals(huntPkg)) {
            if (watching) {
                endHunt("巡视期内检测到你开始操作");
                return;
            }
            // 前 3 秒是“点图标”的手势，放过
            if (now - startedAt > 3000) {
                endHunt("检测到你开始操作");
                return;
            }
        }

        if (!Store.enabled) {
            endHunt("总开关关闭");
            return;
        }
        if (pkg.equals(getPackageName())) {
            endHunt("回到本应用");
            return;
        }
        if (IGNORE.contains(pkg)) {
            if (huntPkg != null && now - startedAt > 1000) {
                endHunt("前台是桌面/系统界面");
            }
            return;
        }

        if (!pkg.equals(huntPkg)) {
            huntPkg = pkg;
            startedAt = now;
            clicks = 0;
            okClicks = 0;
            blankTaps = 0;
            lastBlankTap = 0;
            cornerFallback = getSharedPreferences("cfg", MODE_PRIVATE)
                    .getBoolean("corner_v5", false);
            lastTarget = null;
            watching = false;
            watchUntil = 0;
            loggedNoCand = false;
            windowLogged = false;
            Store.internalOn = true;
            Store.phase = "扫描中";
            Dbg.note("开始扫描 " + pkg + " 内部开关已开" + (cornerFallback ? " (兜底开)" : " (兜底关)"));
            h.removeCallbacks(tick);
            h.post(tick);
        }
    }

    private void scan() {
        long now = SystemClock.uptimeMillis();
        if (!Store.enabled || !Store.internalOn) return;
        if (watching || huntPkg == null) return;
        if (now - startedAt > WINDOW_MS) return;
        if (clicks >= MAX_CLICKS) return;

        TEXTS.clear();
        List<Candidate> list = new ArrayList<Candidate>();
        int[] total = new int[]{0};
        int winCount = 0;
        StringBuilder per = new StringBuilder();

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
                String p = rp == null ? "?" : rp.toString();
                if (p.equals(getPackageName()) || IGNORE.contains(p)) continue;
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
            String p = rp == null ? "?" : rp.toString();
            if (p.equals(getPackageName()) || IGNORE.contains(p)) return;
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
            // 点过之后 1 秒还扫不到关键字：广告已关掉，进巡视
            if (clicks > 0 && now - lastClickAt > 1000) {
                startWatch("广告已关闭");
                return;
            }
            // 一次都没点过又读不到：兜底盲点（默认关）
            if (cornerFallback && blankTaps < 3 && now - lastBlankTap > 900) {
                blankTaps++;
                lastBlankTap = now;
                tapFixedCorner();
                Dbg.note("无候选，盲点右上角 第 " + blankTaps + " 次 " + huntPkg);
                if (blankTaps >= 3) endHunt("三档盲点做完");
            }
            return;
        }

        Candidate best = null;
        for (Candidate c : list) if (best == null || c.score > best.score) best = c;
        if (best == null) return;

        final Candidate target = best;
        final String pkg = huntPkg;
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
                lastTarget = target.text;
                lastClickAt = SystemClock.uptimeMillis();
                clicks++;
                okClicks++;
                Dbg.note("点击 " + pkg + " → " + target.text + where
                        + " 第 " + clicks + "/" + MAX_CLICKS + " 发");
                if (perform(target)) {
                    Store.hitCount++;
                    Store.log(pkg, target.text);
                }
                if (clicks >= MAX_CLICKS) {
                    startWatch("三发打完");
                }
            }
        });
    }

    private void startWatch(String why) {
        if (watching) return;
        watching = true;
        watchUntil = SystemClock.uptimeMillis() + WATCH_MS;
        Store.phase = "巡视中";
        Dbg.note("进入巡视期 5 秒（" + why + "）");
    }

    private void endHunt(String why) {
        boolean had = hunting();
        watching = false;
        watchUntil = 0;
        huntPkg = null;
        clicks = 0;
        Store.internalOn = false;
        Store.phase = "空闲";
        h.removeCallbacks(tick);
        if (had) Dbg.note("终止：" + why);
    }

    private boolean hunting() {
        return huntPkg != null || watching;
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
        Store.internalOn = false;
        Store.phase = "空闲";
        Store.serviceOn = false;
        super.onDestroy();
    }
}
