package xyz.valeh.vaxodpi;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

public class ConnectionsActivity extends Activity {
    private final List<PacketStats.Conn> rows = new ArrayList<>();
    private final HashMap<Integer, String> labels = new HashMap<>();
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView head;
    private String crash = "";
    private BaseAdapter ad;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            List<PacketStats.Conn> l = new ArrayList<>(PacketStats.TABLE.values());
            Collections.sort(l, new Comparator<PacketStats.Conn>() {
                @Override public int compare(PacketStats.Conn a, PacketStats.Conn b) {
                    return Long.compare(b.first, a.first);
                }
            });
            if (l.size() > 300) l = l.subList(0, 300);
            rows.clear();
            rows.addAll(l);
            head.setText(crash + "Bağlantı: " + PacketStats.TABLE.size() + "   " + PacketStats.summary());
            ad.notifyDataSetChanged();
            h.postDelayed(this, 1000);
        }
    };

    private String label(int uid) {
        if (uid == -2) return "…";
        if (uid < 0) return "?";
        String s = labels.get(uid);
        if (s != null) return s;
        try {
            PackageManager pm = getPackageManager();
            String[] p = pm.getPackagesForUid(uid);
            if (p != null && p.length > 0) {
                s = pm.getApplicationLabel(pm.getApplicationInfo(p[0], 0)).toString();
            }
        } catch (Throwable ignored) { }
        if (s == null) s = "uid " + uid;
        labels.put(uid, s);
        return s;
    }

    private static String fmt(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return (b / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            java.io.File cf = new java.io.File(getFilesDir(), "crash.txt");
            if (cf.exists()) {
                byte[] bb = new byte[(int) Math.min(cf.length(), 700)];
                java.io.FileInputStream in = new java.io.FileInputStream(cf);
                int n = in.read(bb);
                in.close();
                crash = "ÖNCEKİ ÇÖKME:\n" + new String(bb, 0, Math.max(n, 0)) + "\n\n";
                cf.delete();
            }
        } catch (Throwable ignored) { }
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread th, Throwable e) {
                try {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    java.io.FileWriter w = new java.io.FileWriter(new java.io.File(getFilesDir(), "crash.txt"));
                    w.write(sw.toString());
                    w.close();
                } catch (Throwable ignored) { }
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * d);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(pad, (int) (32 * d), pad, pad);
        head = new TextView(this);
        head.setTextColor(Color.WHITE);
        head.setTextSize(13);
        head.setPadding(0, 0, 0, pad);
        root.addView(head);
        ListView lv = new ListView(this);
        lv.setDividerHeight(1);
        lv.setDivider(new android.graphics.drawable.ColorDrawable(0xFF333333));
        ad = new BaseAdapter() {
            @Override public int getCount() { return rows.size(); }
            @Override public Object getItem(int i) { return rows.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup p) {
                TextView t = v instanceof TextView ? (TextView) v : new TextView(ConnectionsActivity.this);
                t.setTextColor(Color.WHITE);
                t.setTextSize(13);
                t.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0,
                        (int) (8 * getResources().getDisplayMetrics().density));
                PacketStats.Conn c = rows.get(i);
                String proto = c.proto == 6 ? "TCP" : c.proto == 17 ? "UDP" : "IP" + c.proto;
                String dst = c.name != null ? c.name : c.raddr + ":" + c.rport;
                t.setText(label(c.uid) + " → " + dst + "\n" + proto + " " + c.raddr + ":" + c.rport
                        + "   ↑" + fmt(c.bOut) + " ↓" + fmt(c.bIn));
                return t;
            }
        };
        lv.setAdapter(ad);
        root.addView(lv, new android.widget.LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }
}
