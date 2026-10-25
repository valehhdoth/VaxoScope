package xyz.valeh.vaxodpi;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
    static final int BG = 0xFF000000, CARD = 0xFF0C0C0C, LINE = 0xFF1E1E1E, DIM = 0xFF8A8A8A, OFF = 0xFF5E5E5E;
    int screen = LIST, selUid;
    PacketStats.Conn selConn;
    final Handler h = new Handler(Looper.getMainLooper());
    final HashMap<Integer, String> labels = new HashMap<>();
    final HashMap<Integer, Drawable> icons = new HashMap<>();
    final List<Object[]> apps = new ArrayList<>();
    final List<PacketStats.Conn> conns = new ArrayList<>();
    TextView sub, status, sum, btnList, btnGo, btnSet, detail, clear;
    ListView lv;
    ScrollView sv;
    BaseAdapter ad;
    float d;
    long pendingUntil, maxBytes = 1;
    String crash = "";

    int dp(float v) { return (int) (v * d); }

    GradientDrawable rr(int fill, float r, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(r));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

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

    View row(Drawable ic, String t1, String t2, String right, float frac) {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(14), dp(5), dp(14), dp(5));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(rr(CARD, 18, LINE));
        card.setPadding(dp(14), dp(13), dp(14), dp(13));
        if (ic != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(ic);
            card.addView(iv, new LinearLayout.LayoutParams(dp(42), dp(42)));
        }
        LinearLayout m = new LinearLayout(this);
        m.setOrientation(LinearLayout.VERTICAL);
        m.setPadding(ic != null ? dp(14) : 0, 0, dp(10), 0);
        TextView a = tv(t1, 16, Color.WHITE);
        a.setTypeface(Typeface.DEFAULT_BOLD);
        a.setSingleLine(true);
        a.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView b = tv(t2, 12, DIM);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setPadding(0, dp(2), 0, dp(7));
        m.addView(a);
        m.addView(b);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(rr(0xFF1A1A1A, 2, 0));
        float f = Math.max(0.03f, Math.min(1f, frac));
        View on = new View(this);
        on.setBackground(rr(Color.WHITE, 2, 0));
        bar.addView(on, new LinearLayout.LayoutParams(0, dp(3), f));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, dp(3), 1f - f));
        m.addView(bar, new LinearLayout.LayoutParams(-1, dp(3)));
        card.addView(m, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView r = tv(right, 13, Color.WHITE);
        r.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(r);
        wrap.addView(card);
        return wrap;
    }

    String detailText(PacketStats.Conn c) {
        long now = System.currentTimeMillis();
        String ver = c.raddr.indexOf(':') >= 0 ? "IPv6" : "IPv4";
        long sec = Math.max(1, (c.last - c.first) / 1000);
        return "UYGULAMA\n" + label(c.uid) + "\n" + pkg(c.uid)
                + "\n\nSİTE ADI (SNI/DNS)\n" + (c.name != null ? c.name : "-")
                + "\n\nPROTOKOL\n" + proto(c.proto) + " · " + ver
                + "\n\nADRESLER\nYerel   " + c.laddr + ":" + c.lport
                + "\nUzak    " + c.raddr + ":" + c.rport
                + "\n\nTRAFİK\nGiden   " + c.pOut + " paket · " + fmt(c.bOut)
                + "\nGelen   " + c.pIn + " paket · " + fmt(c.bIn)
                + "\nToplam  " + fmt(c.bOut + c.bIn)
                + "\nHız     " + fmt((c.bOut + c.bIn) / sec) + "/sn"
                + "\n\nZAMAN\nİlk     " + clock(c.first)
                + "\nSon     " + clock(c.last)
                + "\nSüre    " + dur(c.last - c.first)
                + "\nDurum   " + (now - c.last < 3000 ? "Aktif" : "Boşta");
    }

    void refresh() {
        long now = System.currentTimeMillis();
        boolean run = running();
        boolean pend = !run && now < pendingUntil;
        btnGo.setText(run ? "Durdur" : (pend ? "Bağlanıyor…" : "Aç"));
        btnGo.setTextColor(run ? Color.WHITE : Color.BLACK);
        btnGo.setBackground(run ? rr(0xFF000000, 24, Color.WHITE) : rr(Color.WHITE, 24, 0));
        status.setText(run ? "● Açık" : (pend ? "● Bağlanıyor" : "○ Kapalı"));
        status.setTextColor(run ? Color.WHITE : OFF);
        btnList.setTextColor(screen <= DETAIL ? Color.WHITE : OFF);
        btnSet.setTextColor(screen == SET ? Color.WHITE : OFF);
        lv.setVisibility(screen == LIST || screen == APP ? View.VISIBLE : View.GONE);
        sv.setVisibility(screen == DETAIL || screen == SET ? View.VISIBLE : View.GONE);
        clear.setVisibility(screen == SET ? View.VISIBLE : View.GONE);
        sum.setVisibility(screen == LIST ? View.VISIBLE : View.GONE);

        if (screen == LIST) {
            HashMap<Integer, long[]> m = new HashMap<>();
            long up = 0, down = 0;
            for (PacketStats.Conn c : PacketStats.TABLE.values()) {
                int u = c.uid < 0 ? -1 : c.uid;
                long[] a = m.get(u);
                if (a == null) { a = new long[4]; m.put(u, a); }
                a[0]++;
                a[1] += c.bOut;
                a[2] += c.bIn;
                if (c.last > a[3]) a[3] = c.last;
                up += c.bOut;
                down += c.bIn;
            }
            apps.clear();
            for (Map.Entry<Integer, long[]> e : m.entrySet()) apps.add(new Object[]{e.getKey(), e.getValue()});
            Collections.sort(apps, new Comparator<Object[]>() {
                @Override public int compare(Object[] x, Object[] y) {
                    long[] a = (long[]) x[1], b = (long[]) y[1];
                    return Long.compare(b[1] + b[2], a[1] + a[2]);
                }
            });
            maxBytes = 1;
            if (!apps.isEmpty()) { long[] a = (long[]) apps.get(0)[1]; maxBytes = Math.max(1, a[1] + a[2]); }
            sub.setText("Uygulamalar · " + apps.size());
            sum.setText("↑ " + fmt(up) + "     ↓ " + fmt(down) + "     " + PacketStats.TABLE.size() + " bağlantı");
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
            maxBytes = 1;
            for (PacketStats.Conn c : conns) maxBytes = Math.max(maxBytes, c.bOut + c.bIn);
            sub.setText("‹  " + label(selUid) + " · " + conns.size());
        } else if (screen == DETAIL) {
            sub.setText("‹  " + dest(selConn));
            detail.setText(detailText(selConn));
        } else {
            sub.setText("Ayarlar");
            detail.setText("GİZLİLİK\nVaxoScope yalnızca bu telefonun kendi trafiğini izler. Veri telefondan çıkmaz, internete hiçbir şey gönderilmez."
                    + "\n\nSINIRLAR\nHTTPS içeriği okunmaz. Yalnızca site adı, adres, port ve bayt sayıları görünür."
                    + "\n\nİPUCU\nPrivate DNS açıksa DNS sorguları şifreli gider ve site adları eksik kalabilir. "
                    + "Ayarlar > Bağlantılar > Private DNS > Kapalı yaparsan daha çok ad görünür."
                    + "\n\nSÜRÜM\n0.1" + (crash.length() > 0 ? "\n\n" + crash : ""));
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
                crash = "ÖNCEKİ ÇÖKME\n" + new String(bb, 0, Math.max(n, 0));
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
        try {
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
        } catch (Throwable ignored) { }
        PacketStats.init(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(0, dp(34), 0, 0);

        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        brandRow.setPadding(dp(20), dp(10), dp(20), 0);
        TextView brand = tv("VaxoScope", 28, Color.WHITE);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setLetterSpacing(0.02f);
        status = tv("○ Kapalı", 13, OFF);
        brandRow.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));
        brandRow.addView(status);
        root.addView(brandRow);

        sub = tv("", 15, DIM);
        sub.setPadding(dp(20), dp(4), dp(20), dp(10));
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sub.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (screen == APP || screen == DETAIL) onBackPressed(); }
        });
        root.addView(sub);

        sum = tv("", 14, Color.WHITE);
        sum.setTypeface(Typeface.DEFAULT_BOLD);
        sum.setGravity(Gravity.CENTER);
        sum.setBackground(rr(CARD, 16, LINE));
        sum.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(dp(14), 0, dp(14), dp(8));
        root.addView(sum, sp);

        FrameLayout fr = new FrameLayout(this);
        lv = new ListView(this);
        lv.setDividerHeight(0);
        lv.setDivider(null);
        lv.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        lv.setVerticalScrollBarEnabled(false);
        lv.setClipToPadding(false);
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
                    return row(icon(u), dot + label(u), a[0] + " bağlantı · ↑" + fmt(a[1]) + " ↓" + fmt(a[2]),
                            fmt(a[1] + a[2]), (float) (a[1] + a[2]) / maxBytes);
                }
                PacketStats.Conn c = conns.get(i);
                return row(null, dest(c), proto(c.proto) + " · " + c.raddr + ":" + c.rport + " · ↑" + fmt(c.bOut) + " ↓" + fmt(c.bIn),
                        fmt(c.bOut + c.bIn), (float) (c.bOut + c.bIn) / maxBytes);
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
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), 0, dp(14), dp(14));
        LinearLayout dcard = new LinearLayout(this);
        dcard.setOrientation(LinearLayout.VERTICAL);
        dcard.setBackground(rr(CARD, 18, LINE));
        dcard.setPadding(dp(18), dp(16), dp(18), dp(16));
        detail = tv("", 14, Color.WHITE);
        detail.setTypeface(Typeface.MONOSPACE);
        detail.setLineSpacing(0, 1.2f);
        dcard.addView(detail);
        box.addView(dcard);
        clear = tv("Listeyi temizle", 15, Color.WHITE);
        clear.setGravity(Gravity.CENTER);
        clear.setBackground(rr(0xFF000000, 24, Color.WHITE));
        clear.setPadding(0, dp(14), 0, dp(14));
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PacketStats.TABLE.clear();
                Toast.makeText(ConnectionsActivity.this, "Liste temizlendi", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, dp(14), 0, 0);
        box.addView(clear, cp);
        sv.addView(box);
        fr.addView(sv);
        root.addView(fr, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(rr(0xFF0E0E0E, 34, 0xFF242424));
        bar.setPadding(dp(10), dp(10), dp(10), dp(10));
        btnList = tv("Liste", 15, Color.WHITE);
        btnSet = tv("Ayarlar", 15, OFF);
        btnGo = tv("Aç", 16, Color.BLACK);
        btnList.setTypeface(Typeface.DEFAULT_BOLD);
        btnSet.setTypeface(Typeface.DEFAULT_BOLD);
        btnGo.setTypeface(Typeface.DEFAULT_BOLD);
        btnList.setGravity(Gravity.CENTER);
        btnSet.setGravity(Gravity.CENTER);
        btnGo.setGravity(Gravity.CENTER);
        btnGo.setBackground(rr(Color.WHITE, 24, 0));
        btnGo.setPadding(0, dp(14), 0, dp(14));
        btnList.setPadding(0, dp(12), 0, dp(12));
        btnSet.setPadding(0, dp(12), 0, dp(12));
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
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(0, -2, 1.5f);
        gp.setMargins(dp(8), 0, dp(8), 0);
        bar.addView(btnGo, gp);
        bar.addView(btnSet, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(dp(16), dp(8), dp(16), dp(18));
        root.addView(bar, bp);

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
