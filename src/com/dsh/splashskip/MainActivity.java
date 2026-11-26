package com.dsh.splashskip;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.Toast;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView status;
    private TextView stats;
    private TextView logView;
    private TextView dbgView;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("跳过开屏广告");
        Dbg.init(this);
        Dbg.note("打开界面");
        Dbg.flushNow();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        root.setBackgroundColor(Color.WHITE);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("跳过开屏广告 v3.3");
        title.setTextSize(24);
        title.setTextColor(Color.rgb(20, 20, 20));
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button fb = new Button(this);
        fb.setText("反馈 · 联系我");
        fb.setTextSize(12);
        fb.setTextColor(Color.WHITE);
        fb.setBackgroundColor(Color.rgb(45, 45, 45));
        fb.setMinWidth(0);
        fb.setMinimumWidth(0);
        fb.setPadding(dp(14), dp(6), dp(14), dp(6));
        fb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFeedback();
            }
        });
        header.addView(fb);
        root.addView(header);

        TextView sub = new TextView(this);
        sub.setText("自动点掉应用启动时的开屏广告\n不联网 · 不收集数据 · 无广告");
        sub.setTextSize(13);
        sub.setTextColor(Color.rgb(110, 110, 110));
        sub.setPadding(0, dp(6), 0, dp(16));
        root.addView(sub);

        SharedPreferences sp0 = getSharedPreferences("cfg", MODE_PRIVATE);
        Switch master = new Switch(this);
        master.setText("总开关（关闭后完全不工作）");
        master.setTextSize(14);
        master.setChecked(sp0.getBoolean("master", true));
        Store.enabled = master.isChecked();
        master.setPadding(0, dp(6), 0, dp(8));
        master.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton v, boolean on) {
                getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("master", on).apply();
                Store.enabled = on;
            }
        });
        root.addView(master);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.addView(status);

        Button open = new Button(this);
        open.setText("1. 打开无障碍设置");
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Throwable t) {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        root.addView(open, lp);

        Button test = new Button(this);
        test.setText("2. 自检（读一次窗口内容）");
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Dbg.note("自检：" + SkipService.selfTest());
                Dbg.flushNow();
                dbgView.setText("自检：" + SkipService.selfTest()
                        + "\n（能看到节点 = 无障碍生效；读不到 = 没开启或被系统限制）");
            }
        });
        root.addView(test, lp);

        SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
        Switch corner = new Switch(this);
        corner.setText("角落兜底（图片广告：盲点右上角）");
        corner.setTextSize(14);
        corner.setPadding(0, dp(10), 0, dp(2));
        corner.setChecked(sp.getBoolean("corner_v4", false));
        corner.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton v, boolean on) {
                getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("corner_v4", on).apply();
            }
        });
        root.addView(corner);

        TextView hint = new TextView(this);
        hint.setText("部分应用的开屏广告是整张图片，系统读不到任何按钮（例如菜鸟）。开启后，一旦发现完全读不到内容，会在右上角三个位置各盲点一次，点完立即停止，不会影响其他页面。");
        hint.setTextSize(12);
        hint.setTextColor(Color.rgb(140, 140, 140));
        hint.setPadding(0, dp(4), 0, dp(10));
        root.addView(hint);

        TextView warn = new TextView(this);
        warn.setText("不建议开启：没有开屏广告的应用进入后，会导致无脑点击右上角");
        warn.setTextSize(12);
        warn.setTextColor(Color.rgb(210, 38, 38));
        warn.setPadding(0, 0, 0, dp(10));
        root.addView(warn);

        TextView note = new TextView(this);
        note.setText("目前有很多不足，光我检测出来的就是它会莫名其妙的乱点。正在想办法解决，如果您有具体的解决思路，可以联系我。以及面对百度系产品，尤其百度网盘实在无能为力，对方会欺骗无障碍。");
        note.setTextSize(11);
        note.setTextColor(Color.rgb(150, 150, 150));
        note.setLineSpacing(dp(2), 1f);
        note.setPadding(0, 0, 0, dp(18));
        root.addView(note);

        stats = new TextView(this);
        stats.setTextSize(13);
        stats.setTextColor(Color.rgb(70, 70, 70));
        stats.setPadding(0, dp(16), 0, dp(4));
        root.addView(stats);

        TextView head = new TextView(this);
        head.setText("诊断（最近 6 次扫描）");
        head.setTextSize(14);
        head.setTextColor(Color.rgb(30, 30, 30));
        head.setPadding(0, dp(14), 0, dp(4));
        root.addView(head);

        dbgView = new TextView(this);
        dbgView.setTextSize(11);
        dbgView.setTextColor(Color.rgb(120, 100, 60));
        dbgView.setText(Store.dumpDbg());
        root.addView(dbgView);

        TextView head2 = new TextView(this);
        head2.setText("跳过记录");
        head2.setTextSize(14);
        head2.setTextColor(Color.rgb(30, 30, 30));
        head2.setPadding(0, dp(16), 0, dp(4));
        root.addView(head2);

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextColor(Color.rgb(90, 90, 90));
        logView.setLineSpacing(dp(3), 1f);
        root.addView(logView);

        TextView sign = new TextView(this);
        sign.setText("作者 Y3c");
        sign.setTextSize(12);
        sign.setTextColor(Color.rgb(170, 170, 170));
        sign.setGravity(Gravity.END);
        sign.setPadding(0, dp(18), 0, 0);
        root.addView(sign);

        ScrollView sc = new ScrollView(this);
        sc.addView(root);
        setContentView(sc);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = isServiceEnabled() && Store.enabled;
        if (!Store.enabled) {
            status.setText("○ 总开关已关闭，当前不会跳过任何广告");
            status.setTextColor(Color.rgb(120, 120, 120));
            status.setBackgroundColor(Color.rgb(240, 240, 240));
        } else if (on) {
            status.setText("● 无障碍已开启" + (Store.serviceOn ? "，服务在运行" : "，但服务没连接（关掉再打开一次）"));
            status.setTextColor(Color.rgb(16, 130, 80));
            status.setBackgroundColor(Color.rgb(232, 248, 240));
        } else {
            status.setText("○ 未开启\n点下面按钮 → 在「已下载的服务」里打开「杀死广告」");
            status.setTextColor(Color.rgb(190, 90, 20));
            status.setBackgroundColor(Color.rgb(255, 245, 232));
        }
        stats.setText("事件 " + Store.eventCount
                + " · 扫描 " + Store.scanCount
                + " · 点击 " + Store.hitCount
                + " · 最近节点数 " + Store.nodeCount
                + "\n最近前台：" + Store.lastPkg
                + "\n累计跳过 " + Store.total + " 次");
        dbgView.setText(Store.dumpDbg());
        logView.setText(Store.dump());
    }

    private void showFeedback() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(20), dp(24), dp(8));

        TextView mail = new TextView(this);
        mail.setText("1853718701@qq.com");
        mail.setTextSize(19);
        mail.setTextColor(Color.rgb(20, 90, 160));
        mail.setTextIsSelectable(true);
        box.addView(mail);

        TextView under = new TextView(this);
        under.setText("联系我");
        under.setTextSize(13);
        under.setTextColor(Color.rgb(130, 130, 130));
        under.setPadding(0, dp(6), 0, 0);
        box.addView(under);

        new AlertDialog.Builder(this)
                .setTitle("反馈")
                .setView(box)
                .setPositiveButton("复制邮箱", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("email", "1853718701@qq.com"));
                            Toast.makeText(MainActivity.this, "已复制邮箱", Toast.LENGTH_SHORT).show();
                        } catch (Throwable ignored) {
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private boolean isServiceEnabled() {
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(s)) return false;
            ComponentName cn = new ComponentName(this, SkipService.class);
            String flat = cn.flattenToString();
            for (String p : s.split(":")) {
                if (p.equalsIgnoreCase(flat)
                        || p.equalsIgnoreCase(cn.flattenToShortString())) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
