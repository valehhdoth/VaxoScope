package xyz.valeh.vaxodpi;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.*;

public class ConnectionsActivity extends Activity {
    static final int LIST = 0, APP = 1, DETAIL = 2, SET = 3;
    int screen = LIST, selUid;
    PacketStats.Conn selConn;
    final Handler h = new Handler(Looper.getMainLooper());
    final HashMap<Integer, String> labels = new HashMap<>();
    final HashMap<Integer, Drawable> icons = new HashMap<>();
    final List<Object[]> apps = new ArrayList<>();
    final List<PacketStats.Conn> conns = new ArrayList<>();
    TextView title, btnList, btnGo, btnSet, detail;
    ListView lv;
    ScrollView sv;
    Button clear;
    BaseAdapter ad;
    float d;
    long pendingUntil;
    String crash = "";

    String label(int uid) {
        if (uid < 0) return "Bilinmeyen";
        if (uid == 0) return "Sistem (root)";
        if (uid == 1000) return "Android sistemi";
        if (uid == 1051) return "Sistem DNS";
        String s = labels.get(uid);
        if (s != null) return s;
        try {
            PackageManager pm = getPackageManager();
            String[] p = pm.getPackagesForUid(uid);
            if (p != null && p.length > 0) s = pm.getApplicationLabel(pm.getApplicationInfo(p[0], 0)).toString();
        } catch (Throwable ignored) { }
        if (s == null) s = "uid " + uid;
        labels.put(uid, s);
        return s;
    }

    String pkg(int uid) {
        try {
            String[] p = getPackageManager().getPackagesForUid(uid);
            if (p != null && p.length > 0) return p[0];
        } catch (Throwable ignored) { }
        return "-";
    }

    Drawable icon(int uid) {
        Drawable x = icons.get(uid);
        if (x != null) return x;
        try {
            String p = pkg(uid);
            x = p.equals("-") ? getPackageManager().getDefaultActivityIcon() : getPackageManager().getApplicationIcon(p);
        } catch (Throwable e) {
            x = getPackageManager().getDefaultActivityIcon();
        }
        icons.put(uid, x);
        return x;
    }

    static String fmt(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return (b / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", b / 1048576.0);
    }

    static String dur(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + " sn";
        if (s < 3600) return (s / 60) + " dk " + (s % 60) + " sn";
        return (s / 3600) + " sa " + ((s % 3600) / 60) + " dk";
    }

    static String clock(long t) {
        return new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(t));
    }

    static String proto(int p) {
        return p == 6 ? "TCP" : p == 17 ? "UDP" : p == 1 ? "ICMP" : "IP/" + p;
    }

    static String dest(PacketStats.Conn c) {
        return c.name != null ? c.name : c.raddr + ":" + c.rport;
    }

    boolean running() {
        return System.currentTimeMillis() - PacketStats.lastPkt < 8000;
    }

    void startVpn() {
        pendingUntil = System.currentTimeMillis() + 7000;
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2); } catch (Throwable ignored) { }
        }
        Intent i = VpnService.prepare(this);
        if (i != null) startActivityForResult(i, 1); else go();
    }

    void go() {
        Intent s = new Intent(this, VaxoVpnService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
    }

    @Override protected void onActivityResult(int rq, int rs, Intent data) {
        if (rq == 1 && rs == RESULT_OK) go(); else if (rq == 1) pendingUntil = 0;
    }

    void stopVpn() {
        Intent s = new Intent(this, VaxoVpnService.class);
        s.setAction("xyz.valeh.vaxodpi.STOP");
        startService(s);
        pendingUntil = 0;
    }

    TextView tv(String t, int sp, int col) {
        TextView x = new TextView(this);
        x.setText(t);
        x.setTextSize(sp);
        x.setTextColor(col);
        return x;
    }

    View row(Drawable ic, String t1, String t2, String right) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int p = (int) (10 * d);
        r.setPadding(p, p, p, p);
        if (ic != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(ic);
            r.addView(iv, new LinearLayout.LayoutParams((int) (40 * d), (int) (40 * d)));
        }
        LinearLayout m = new LinearLayout(this);
        m.setOrientation(LinearLayout.VERTICAL);
        m.setPadding((int) (12 * d), 0, (int) (8 * d), 0);
        TextView a = tv(t1, 16, Color.WHITE);
        a.setSingleLine(true);
        a.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView b = tv(t2, 12, 0xFF999999);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        m.addView(a);
        m.addView(b);
        r.addView(m, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(tv(right, 13, Color.WHITE));
        return r;
    }

    String detailText(PacketStats.Conn c) {
        long now = System.currentTimeMillis();
        String ver = c.raddr.indexOf(':') >= 0 ? "IPv6" : "IPv4";
        long sec = Math.max(1, (c.last - c.first) / 1000);
        return "Uygulama:  " + label(c.uid) + "\nPaket:  " + pkg(c.uid)
                + "\n\nSite adı (SNI/DNS):  " + (c.name != null ? c.name : "-")
                + "\n\nProtokol:  " + proto(c.proto) + "  (" + ver + ")"
                + "\nYerel:  " + c.laddr + ":" + c.lport
                + "\nUzak:  " + c.raddr + ":" + c.rport
                + "\n\nGiden:  " + c.pOut + " paket,  " + fmt(c.bOut)
                + "\nGelen:  " + c.pIn + " paket,  " + fmt(c.bIn)
                + "\nToplam:  " + fmt(c.bOut + c.bIn)
                + "\nOrtalama hız:  " + fmt((c.bOut + c.bIn) / sec) + "/sn"
                + "\n\nİlk görülme:  " + clock(c.first)
                + "\nSon görülme:  " + clock(c.last)
                + "\nSüre:  " + dur(c.last - c.first)
                + "\nDurum:  " + (now - c.last < 3000 ? "Aktif" : "Boşta");
    }

    void refresh() {
        long now = System.currentTimeMillis();
        boolean run = running();
        btnGo.setText(run ? "Durdur" : (now < pendingUntil ? "Bağlanıyor…" : "Aç"));
        btnList.setTextColor(screen <= DETAIL ? Color.WHITE : 0xFF777777);
        btnSet.setTextColor(screen == SET ? Color.WHITE : 0xFF777777);
        lv.setVisibility(screen == LIST || screen == APP ? View.VISIBLE : View.GONE);
        sv.setVisibility(screen == DETAIL || screen == SET ? View.VISIBLE : View.GONE);
        clear.setVisibility(screen == SET ? View.VISIBLE : View.GONE);

        if (screen == LIST) {
            HashMap<Integer, long[]> m = new HashMap<>();
            for (PacketStats.Conn c : PacketStats.TABLE.values()) {
                int u = c.uid < 0 ? -1 : c.uid;
                long[] a = m.get(u);
                if (a == null) { a = new long[4]; m.put(u, a); }
                a[0]++;
                a[1] += c.bOut;
                a[2] += c.bIn;
                if (c.last > a[3]) a[3] = c.last;
            }
            apps.clear();
            for (Map.Entry<Integer, long[]> e : m.entrySet()) apps.add(new Object[]{e.getKey(), e.getValue()});
            Collections.sort(apps, new Comparator<Object[]>() {
                @Override public int compare(Object[] x, Object[] y) {
                    long[] a = (long[]) x[1], b = (long[]) y[1];
                    return Long.compare(b[1] + b[2], a[1] + a[2]);
                }
            });
            title.setText("Uygulamalar (" + apps.size() + ")");
        } else if (screen == APP) {
            conns.clear();
            for (PacketStats.Conn c : PacketStats.TABLE.values()) {
                int u = c.uid < 0 ? -1 : c.uid;
                if (u == selUid) conns.add(c);
            }
            Collections.sort(conns, new Comparator<PacketStats.Conn>() {
                @Override public int compare(PacketStats.Conn a, PacketStats.Conn b) {
                    return Long.compare(b.first, a.first);
                }
            });
            title.setText("‹  " + label(selUid) + " (" + conns.size() + ")");
        } else if (screen == DETAIL) {
            title.setText("‹  " + dest(selConn));
            detail.setText(detailText(selConn));
        } else {
            title.setText("Ayarlar");
            detail.setText("VaxoScope yalnızca bu telefonun kendi trafiğini izler. Veri telefondan çıkmaz, internete hiçbir şey gönderilmez.\n\n"
                    + "HTTPS içeriği okunmaz. Yalnızca site adı, adres, port ve bayt sayıları görünür.\n\n"
                    + "Telefonda Private DNS açıksa DNS sorguları şifreli gider ve site adları eksik kalabilir. "
                    + "Ayarlar > Bağlantılar > Private DNS > Kapalı yaparsan daha çok ad görünür.\n\n"
                    + "Sürüm 0.1\n\n" + crash);
        }
        ad.notifyDataSetChanged();
    }

    final Runnable tick = new Runnable() {
        @Override public void run() {
            try { refresh(); } catch (Throwable ignored) { }
            h.postDelayed(this, 1000);
        }
    };

    void nav(int s) { screen = s; refresh(); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            java.io.File cf = new java.io.File(getFilesDir(), "crash.txt");
            if (cf.exists()) {
                byte[] bb = new byte[(int) Math.min(cf.length(), 900)];
                java.io.FileInputStream in = new java.io.FileInputStream(cf);
                int n = in.read(bb);
                in.close();
                crash = "ÖNCEKİ ÇÖKME:\n" + new String(bb, 0, Math.max(n, 0));
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
        d = getResources().getDisplayMetrics().density;
        PacketStats.init(this);
        int pad = (int) (14 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(0, (int) (30 * d), 0, 0);

        title = tv("", 20, Color.WHITE);
        title.setPadding(pad, pad, pad, pad);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onBackPressed(); }
        });
        root.addView(title);

        FrameLayout fr = new FrameLayout(this);
        lv = new ListView(this);
        lv.setDividerHeight(1);
        lv.setDivider(new android.graphics.drawable.ColorDrawable(0xFF222222));
        ad = new BaseAdapter() {
            @Override public int getCount() { return screen == LIST ? apps.size() : screen == APP ? conns.size() : 0; }
            @Override public Object getItem(int i) { return null; }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup p) {
                if (screen == LIST) {
                    Object[] o = apps.get(i);
                    int u = (Integer) o[0];
                    long[] a = (long[]) o[1];
                    String dot = System.currentTimeMillis() - a[3] < 3000 ? "● " : "";
                    return row(icon(u), dot + label(u), a[0] + " bağlantı   ↑" + fmt(a[1]) + "  ↓" + fmt(a[2]), fmt(a[1] + a[2]));
                }
                PacketStats.Conn c = conns.get(i);
                return row(null, dest(c), proto(c.proto) + "  " + c.raddr + ":" + c.rport + "   ↑" + fmt(c.bOut) + "  ↓" + fmt(c.bIn), fmt(c.bOut + c.bIn));
            }
        };
        lv.setAdapter(ad);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> a, View v, int i, long id) {
                if (screen == LIST && i < apps.size()) { selUid = (Integer) apps.get(i)[0]; nav(APP); }
                else if (screen == APP && i < conns.size()) { selConn = conns.get(i); nav(DETAIL); }
            }
        });
        fr.addView(lv);

        sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, 0, pad, pad);
        detail = tv("", 15, Color.WHITE);
        detail.setLineSpacing(0, 1.15f);
        box.addView(detail);
        clear = new Button(this);
        clear.setText("Listeyi temizle");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PacketStats.TABLE.clear();
                Toast.makeText(ConnectionsActivity.this, "Liste temizlendi", Toast.LENGTH_SHORT).show();
            }
        });
        box.addView(clear);
        sv.addView(box);
        fr.addView(sv);
        root.addView(fr, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF111111);
        int bp = (int) (10 * d);
        bar.setPadding(bp, bp, bp, bp * 2);
        btnList = tv("Liste", 15, Color.WHITE);
        btnSet = tv("Ayarlar", 15, 0xFF777777);
        btnGo = tv("Aç", 16, Color.BLACK);
        btnList.setGravity(Gravity.CENTER);
        btnSet.setGravity(Gravity.CENTER);
        btnGo.setGravity(Gravity.CENTER);
        btnGo.setBackgroundColor(Color.WHITE);
        btnGo.setPadding(0, (int) (12 * d), 0, (int) (12 * d));
        btnList.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { nav(LIST); }
        });
        btnSet.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { nav(SET); }
        });
        btnGo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (running()) stopVpn(); else startVpn();
            }
        });
        bar.addView(btnList, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(0, -2, 1.3f);
        gp.setMargins(bp, 0, bp, 0);
        bar.addView(btnGo, gp);
        bar.addView(btnSet, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(bar);

        setContentView(root);
        refresh();
    }

    @Override public void onBackPressed() {
        if (screen == DETAIL) nav(APP);
        else if (screen == APP || screen == SET) nav(LIST);
        else super.onBackPressed();
    }

    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }
}
