package xyz.valeh.vaxodpi;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.*;
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
    static final int HOME = 0, TRAF = 1, SET = 2, L_LIST = 0, L_APP = 1, L_DET = 2;
    static final int CARD = 0xFF0E0E0E, LINE = 0xFF242424, DIM = 0xFF8A8A8A;

    static class Ic extends View {
        final int type; boolean on;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Ic(Context c, int t) {
            super(c); type = t;
            p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h), cx = w / 2, cy = h / 2;
            p.setColor(on ? Color.WHITE : 0xFF8A8A8A);
            p.setStrokeWidth(s * 0.085f);
            if (type == 0) {
                Path q = new Path();
                q.moveTo(cx - s * 0.42f, cy - s * 0.02f); q.lineTo(cx, cy - s * 0.4f); q.lineTo(cx + s * 0.42f, cy - s * 0.02f);
                q.moveTo(cx - s * 0.3f, cy - s * 0.12f); q.lineTo(cx - s * 0.3f, cy + s * 0.38f);
                q.lineTo(cx + s * 0.3f, cy + s * 0.38f); q.lineTo(cx + s * 0.3f, cy - s * 0.12f);
                c.drawPath(q, p);
            } else if (type == 1) {
                for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) {
                    float l = cx - s * 0.38f + i * s * 0.44f, t = cy - s * 0.38f + j * s * 0.44f;
                    c.drawRoundRect(new RectF(l, t, l + s * 0.32f, t + s * 0.32f), s * 0.09f, s * 0.09f, p);
                }
            } else {
                c.drawCircle(cx, cy, s * 0.14f, p);
                c.drawCircle(cx, cy, s * 0.3f, p);
                for (int k = 0; k < 8; k++) {
                    double a = k * Math.PI / 4;
                    c.drawLine(cx + (float) Math.cos(a) * s * 0.3f, cy + (float) Math.sin(a) * s * 0.3f,
                            cx + (float) Math.cos(a) * s * 0.44f, cy + (float) Math.sin(a) * s * 0.44f, p);
                }
            }
        }
    }

    static class Ring extends View {
        boolean on;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Ring(Context c) { super(c); p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), cx = w / 2, cy = getHeight() / 2, R = w / 2 - w * 0.06f;
            p.setStrokeWidth(w * 0.04f);
            p.setColor(on ? Color.WHITE : 0xFF2A2A2A);
            c.drawCircle(cx, cy, R, p);
            float r = R * 0.31f;
            cy += R * 0.04f;
            p.setStrokeWidth(w * 0.025f);
            p.setColor(on ? Color.WHITE : 0xFF8A8A8A);
            c.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), 290, 320, false, p);
            c.drawLine(cx, cy - r * 1.15f, cx, cy - r * 0.1f, p);
        }
    }

    static class Logo extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Logo(Context c) {
            super(c); p.setStyle(Paint.Style.STROKE); p.setColor(Color.WHITE);
            p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setStrokeWidth(w * 0.12f);
            Path q = new Path();
            q.moveTo(w * 0.1f, h * 0.25f); q.lineTo(w * 0.42f, h * 0.78f); q.lineTo(w * 0.92f, h * 0.18f);
            c.drawPath(q, p);
        }
    }

    int tab = HOME, lvl = L_LIST, selUid, goState = -1;
    PacketStats.Conn selConn;
    final Handler h = new Handler(Looper.getMainLooper());
    final HashMap<Integer, String> labels = new HashMap<>();
    final HashMap<Integer, Drawable> icons = new HashMap<>();
    final List<Object[]> apps = new ArrayList<>();
    final List<PacketStats.Conn> conns = new ArrayList<>();
    float d;
    long pendingUntil, maxBytes = 1, startT, lastT, lastUp, lastDown;
    double spUp, spDown;
    String crash = "";
    SharedPreferences sp;
    View homeSv, trafPage, setSv;
    TextView hStatus, hSub, hDur, trTitle, trSub, detail, empty, vpnState, btnGo;
    final TextView[] sDown = new TextView[2], sUp = new TextView[2], sConn = new TextView[2], sData = new TextView[2];
    Ring ring;
    ListView lv;
    ScrollView detSv;
    BaseAdapter ad;
    LinearLayout setBox;
    final Ic[] ics = new Ic[3];
    final TextView[] navT = new TextView[3];
    final LinearLayout[] navB = new LinearLayout[3];

    int dp(float v) { return (int) (v * d); }

    GradientDrawable rr(int fill, float r, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(r));
        if (stroke != 0) g.setStroke(Math.max(2, dp(1.2f)), stroke);
        return g;
    }

    TextView tv(String t, int sz, int col) {
        TextView x = new TextView(this);
        x.setText(t); x.setTextSize(sz); x.setTextColor(col);
        return x;
    }

    TextView bold(String t, int sz, int col) {
        TextView x = tv(t, sz, col);
        x.setTypeface(Typeface.DEFAULT_BOLD);
        return x;
    }

    LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rr(CARD, 26, LINE));
        c.setPadding(dp(20), dp(18), dp(20), dp(18));
        return c;
    }

    LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(20), dp(top), dp(20), 0);
        return p;
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

    static String[] sz(long b) {
        if (b < 1024) return new String[]{"" + b, "B"};
        if (b < 1048576) return new String[]{"" + (b / 1024), "KB"};
        if (b < 1073741824L) return new String[]{String.format(Locale.US, "%.1f", b / 1048576.0), "MB"};
        return new String[]{String.format(Locale.US, "%.2f", b / 1073741824.0), "GB"};
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

    void toggleVpn() { if (running()) stopVpn(); else startVpn(); }

    View row(Drawable ic, String t1, String t2, String right, float frac) {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(5), dp(20), dp(5));
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(rr(CARD, 22, LINE));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        if (ic != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(ic);
            c.addView(iv, new LinearLayout.LayoutParams(dp(42), dp(42)));
        }
        LinearLayout m = new LinearLayout(this);
        m.setOrientation(LinearLayout.VERTICAL);
        m.setPadding(ic != null ? dp(14) : 0, 0, dp(10), 0);
        TextView a = bold(t1, 16, Color.WHITE);
        a.setSingleLine(true);
        a.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView b = tv(t2, 12, DIM);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setPadding(0, dp(2), 0, dp(8));
        m.addView(a);
        m.addView(b);
        float f = Math.max(0.03f, Math.min(1f, frac));
        LinearLayout bar = new LinearLayout(this);
        bar.setBackground(rr(0xFF1C1C1C, 2, 0));
        View on = new View(this);
        on.setBackground(rr(Color.WHITE, 2, 0));
        bar.addView(on, new LinearLayout.LayoutParams(0, dp(3), f));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, dp(3), 1f - f));
        m.addView(bar, new LinearLayout.LayoutParams(-1, dp(3)));
        c.addView(m, new LinearLayout.LayoutParams(0, -2, 1f));
        c.addView(bold(right, 13, Color.WHITE));
        wrap.addView(c);
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

    LinearLayout stat(String label, TextView[] o) {
        LinearLayout c = card();
        TextView l = bold(label, 12, DIM);
        l.setLetterSpacing(0.12f);
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.BOTTOM);
        r.setPadding(0, dp(16), 0, 0);
        o[0] = bold("--", 28, Color.WHITE);
        o[1] = tv("", 15, DIM);
        o[1].setPadding(dp(6), 0, 0, dp(4));
        r.addView(o[0]);
        r.addView(o[1]);
        c.addView(l);
        c.addView(r);
        return c;
    }

    LinearLayout pair(View a, View b) {
        LinearLayout r = new LinearLayout(this);
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, -2, 1f);
        p1.setMargins(0, 0, dp(7), 0);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, -2, 1f);
        p2.setMargins(dp(7), 0, 0, 0);
        r.addView(a, p1);
        r.addView(b, p2);
        return r;
    }

    TextView chip(String t, boolean on, View.OnClickListener l) {
        TextView c = bold(t, 13, on ? Color.BLACK : DIM);
        c.setLetterSpacing(0.06f);
        c.setGravity(Gravity.CENTER);
        c.setAllCaps(true);
        c.setBackground(on ? rr(Color.WHITE, 24, 0) : rr(0xFF1A1A1A, 24, 0xFF333333));
        c.setPadding(dp(8), dp(14), dp(8), dp(14));
        c.setOnClickListener(l);
        return c;
    }

    LinearLayout chips(TextView... cs) {
        LinearLayout r = new LinearLayout(this);
        for (TextView c : cs) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(dp(4), 0, dp(4), 0);
            r.addView(c, p);
        }
        return r;
    }

    TextView dnsChip(final String[] dn, final int i, int cur) {
        return chip(dn[i], cur == i, v -> { sp.edit().putInt("dns", i).apply(); buildSettings(); });
    }

    LinearLayout toggle(String title, String desc, final String key) {
        final boolean on = sp.getBoolean(key, false);
        LinearLayout c = card();
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        TextView b = tv(desc, 14, DIM);
        b.setPadding(0, dp(4), dp(10), 0);
        l.addView(bold(title, 18, Color.WHITE));
        l.addView(b);
        c.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView ch = chip(on ? "Açık" : "Kapalı", on, v -> { sp.edit().putBoolean(key, !on).apply(); buildSettings(); });
        ch.setPadding(dp(18), dp(12), dp(18), dp(12));
        c.addView(ch);
        return c;
    }

    LinearLayout kv(String k, String v, TextView[] hold) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(9), 0, dp(9));
        TextView b = bold(v, 17, Color.WHITE);
        if (hold != null) hold[0] = b;
        r.addView(tv(k, 17, Color.WHITE), new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(b);
        return r;
    }

    void buildSettings() {
        setBox.removeAllViews();
        int di = sp.getInt("dns", 0);
        String[] dn = {"Cloudflare", "Google", "Quad9", "AdGuard"};
        LinearLayout c1 = card();
        TextView t2 = tv("Değişiklik bir sonraki bağlantıda geçerli olur.", 14, DIM);
        t2.setPadding(0, dp(4), 0, dp(14));
        c1.addView(bold("DNS sunucusu", 18, Color.WHITE));
        c1.addView(t2);
        c1.addView(chips(dnsChip(dn, 0, di), dnsChip(dn, 1, di)));
        LinearLayout r2 = chips(dnsChip(dn, 2, di), dnsChip(dn, 3, di));
        r2.setPadding(0, dp(8), 0, 0);
        c1.addView(r2);
        setBox.addView(c1, lp(14));
        setBox.addView(toggle("Yalnızca aktif bağlantılar", "Son 5 saniyede trafiği olanları göster.", "only_active"), lp(14));
        setBox.addView(toggle("Log kaydı", "Hata ayıklama için olayları dosyaya yazar.", "log_on"), lp(14));

        LinearLayout c4 = card();
        TextView hd = bold("Altyapı durumu", 17, Color.WHITE);
        hd.setPadding(0, 0, 0, dp(6));
        c4.addView(hd);
        TextView[] hv = new TextView[1];
        c4.addView(kv("Trafik yakalama", running() ? "Aktif" : "Kapalı", hv));
        vpnState = hv[0];
        c4.addView(kv("Uygulama eşleştirme", android.os.Build.VERSION.SDK_INT >= 29 ? "Aktif" : "Yok", null));
        c4.addView(kv("HTTPS içeriği", "Okunmaz", null));
        c4.addView(kv("Sürüm", "v0.1", null));
        setBox.addView(c4, lp(14));

        LinearLayout c5 = card();
        TextView pv = tv("VaxoScope yalnızca bu telefonun kendi trafiğini izler. Veri telefondan çıkmaz, internete hiçbir şey gönderilmez. HTTPS içeriği okunmaz; yalnızca site adı, adres, port ve bayt sayıları görünür.\n\nPrivate DNS açıksa DNS sorguları şifreli gider ve bazı site adları eksik kalır. Ayarlar > Bağlantılar > Private DNS > Kapalı yaparsan daha çok ad görünür.", 14, DIM);
        pv.setLineSpacing(0, 1.15f);
        TextView ph = bold("Gizlilik", 18, Color.WHITE);
        ph.setPadding(0, 0, 0, dp(8));
        c5.addView(ph);
        c5.addView(pv);
        setBox.addView(c5, lp(14));

        TextView clr = bold("Listeyi temizle", 15, Color.WHITE);
        clr.setGravity(Gravity.CENTER);
        clr.setBackground(rr(Color.BLACK, 30, 0xFF444444));
        clr.setPadding(0, dp(16), 0, dp(16));
        clr.setOnClickListener(v -> {
            PacketStats.TABLE.clear();
            Toast.makeText(this, "Liste temizlendi", Toast.LENGTH_SHORT).show();
        });
        setBox.addView(clr, lp(14));

        if (crash.length() > 0) {
            LinearLayout c6 = card();
            c6.addView(tv(crash, 12, DIM));
            setBox.addView(c6, lp(14));
        }
        View sp2 = new View(this);
        setBox.addView(sp2, new LinearLayout.LayoutParams(-1, dp(20)));
    }

    void refresh() {
        long now = System.currentTimeMillis();
        boolean run = running(), pend = !run && now < pendingUntil;
        boolean onlyAct = sp.getBoolean("only_active", false);
        long up = 0, down = 0;
        HashMap<Integer, long[]> m = new HashMap<>();
        conns.clear();
        for (PacketStats.Conn c : PacketStats.TABLE.values()) {
            up += c.bOut;
            down += c.bIn;
            if (onlyAct && now - c.last > 5000) continue;
            int u = c.uid < 0 ? -1 : c.uid;
            long[] a = m.get(u);
            if (a == null) { a = new long[4]; m.put(u, a); }
            a[0]++; a[1] += c.bOut; a[2] += c.bIn;
            if (c.last > a[3]) a[3] = c.last;
            if (lvl != L_LIST && u == selUid) conns.add(c);
        }
        if (lastT > 0 && now > lastT) {
            long dt = now - lastT;
            spUp = spUp * 0.5 + Math.max(0, up - lastUp) * 8.0 / dt / 1000.0 * 0.5;
            spDown = spDown * 0.5 + Math.max(0, down - lastDown) * 8.0 / dt / 1000.0 * 0.5;
        }
        lastT = now; lastUp = up; lastDown = down;

        // Ana sayfa
        ring.on = run;
        ring.invalidate();
        hStatus.setText(run ? "İZLENİYOR" : pend ? "BAĞLANIYOR" : "BAĞLI DEĞİL");
        hSub.setText(run ? "Trafik yakalanıyor" : "VPN bağlantısı etkin değil");
        int gs = run ? 1 : 0;
        if (gs != goState) {
            goState = gs;
            if (run) {
                btnGo.setBackground(rr(Color.BLACK, 40, Color.WHITE));
                btnGo.setTextColor(Color.WHITE);
            } else {
                GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFFB5B5B5, 0xFFFFFFFF});
                g.setCornerRadius(dp(40));
                btnGo.setBackground(g);
                btnGo.setTextColor(Color.BLACK);
            }
        }
        btnGo.setText(run ? "DURDUR" : pend ? "BAĞLANIYOR…" : "BAĞLAN");
        if (run && startT == 0) startT = now;
        if (!run) startT = 0;
        long sec = run ? (now - startT) / 1000 : 0;
        hDur.setText(String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60));
        hDur.setTextColor(run ? Color.WHITE : 0xFF8A8A8A);
        sDown[0].setText(run ? String.format(Locale.US, "%.2f", spDown) : "--"); sDown[1].setText("Mbps");
        sUp[0].setText(run ? String.format(Locale.US, "%.2f", spUp) : "--"); sUp[1].setText("Mbps");
        sConn[0].setText("" + PacketStats.TABLE.size()); sConn[1].setText("adet");
        String[] dz = sz(up + down);
        sData[0].setText(dz[0]); sData[1].setText(dz[1]);
        if (vpnState != null) vpnState.setText(run ? "Aktif" : "Kapalı");

        // Trafik
        apps.clear();
        for (Map.Entry<Integer, long[]> e : m.entrySet()) apps.add(new Object[]{e.getKey(), e.getValue()});
        Collections.sort(apps, (x, y) -> {
            long[] a = (long[]) x[1], b = (long[]) y[1];
            return Long.compare(b[1] + b[2], a[1] + a[2]);
        });
        Collections.sort(conns, (a, b) -> Long.compare(b.first, a.first));
        maxBytes = 1;
        if (lvl == L_LIST) {
            if (!apps.isEmpty()) { long[] a = (long[]) apps.get(0)[1]; maxBytes = Math.max(1, a[1] + a[2]); }
            trTitle.setText("Trafik");
            trSub.setText(apps.size() + " uygulama · ↑ " + fmt(up) + "  ↓ " + fmt(down));
        } else if (lvl == L_APP) {
            for (PacketStats.Conn c : conns) maxBytes = Math.max(maxBytes, c.bOut + c.bIn);
            trTitle.setText(label(selUid));
            trSub.setText("‹  Geri  ·  " + conns.size() + " bağlantı");
        } else if (selConn != null) {
            trTitle.setText(dest(selConn));
            trSub.setText("‹  Geri");
            detail.setText(detailText(selConn));
        }
        boolean t = tab == TRAF;
        lv.setVisibility(t && lvl != L_DET ? View.VISIBLE : View.GONE);
        detSv.setVisibility(t && lvl == L_DET ? View.VISIBLE : View.GONE);
        boolean emp = lvl == L_LIST ? apps.isEmpty() : conns.isEmpty();
        empty.setVisibility(t && lvl != L_DET && emp ? View.VISIBLE : View.GONE);
        ad.notifyDataSetChanged();
    }

    final Runnable tick = new Runnable() {
        @Override public void run() {
            try { refresh(); } catch (Throwable ignored) { }
            h.postDelayed(this, 1000);
        }
    };

    void nav(int t) {
        if (t == TRAF && tab == TRAF) lvl = L_LIST;
        tab = t;
        if (t == SET) buildSettings();
        homeSv.setVisibility(t == HOME ? View.VISIBLE : View.GONE);
        trafPage.setVisibility(t == TRAF ? View.VISIBLE : View.GONE);
        setSv.setVisibility(t == SET ? View.VISIBLE : View.GONE);
        for (int i = 0; i < 3; i++) {
            ics[i].on = i == t;
            ics[i].invalidate();
            navT[i].setTextColor(i == t ? Color.WHITE : 0xFF8A8A8A);
            navB[i].setBackground(i == t ? rr(0xFF131313, 28, 0) : null);
        }
        try { refresh(); } catch (Throwable ignored) { }
    }

    LinearLayout navItem(final int i, String name) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        b.setPadding(0, dp(10), 0, dp(10));
        ics[i] = new Ic(this, i);
        navT[i] = bold(name, 13, 0xFF8A8A8A);
        navT[i].setPadding(0, dp(4), 0, 0);
        b.addView(ics[i], new LinearLayout.LayoutParams(dp(30), dp(30)));
        b.addView(navT[i]);
        b.setOnClickListener(v -> nav(i));
        navB[i] = b;
        return b;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("vaxodpi", MODE_PRIVATE);
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
        Thread.setDefaultUncaughtExceptionHandler((th, e) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                java.io.FileWriter w = new java.io.FileWriter(new java.io.File(getFilesDir(), "crash.txt"));
                w.write(sw.toString());
                w.close();
            } catch (Throwable ignored) { }
            android.os.Process.killProcess(android.os.Process.myPid());
        });
        d = getResources().getDisplayMetrics().density;
        try {
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
        } catch (Throwable ignored) { }
        PacketStats.init(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(0, dp(30), 0, 0);
        FrameLayout pages = new FrameLayout(this);

        // ANA SAYFA
        ScrollView hs = new ScrollView(this);
        hs.setVerticalScrollBarEnabled(false);
        LinearLayout hb = new LinearLayout(this);
        hb.setOrientation(LinearLayout.VERTICAL);
        hb.setPadding(0, dp(18), 0, dp(24));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(24), 0, dp(24), 0);
        head.addView(new Logo(this), new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.setPadding(dp(14), 0, 0, 0);
        tt.addView(bold("VaxoScope", 30, Color.WHITE));
        tt.addView(tv("Ağ trafiği izleyici", 15, DIM));
        head.addView(tt, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView ver = bold("v0.1", 15, Color.WHITE);
        ver.setBackground(rr(0xFF111111, 20, 0xFF333333));
        ver.setPadding(dp(16), dp(8), dp(16), dp(8));
        head.addView(ver);
        hb.addView(head);
        ring = new Ring(this);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(250), dp(250));
        rp.gravity = Gravity.CENTER_HORIZONTAL;
        rp.setMargins(0, dp(22), 0, dp(10));
        ring.setOnClickListener(v -> toggleVpn());
        hb.addView(ring, rp);
        hStatus = bold("BAĞLI DEĞİL", 28, Color.WHITE);
        hStatus.setLetterSpacing(0.12f);
        hStatus.setGravity(Gravity.CENTER);
        hb.addView(hStatus, new LinearLayout.LayoutParams(-1, -2));
        hSub = tv("VPN bağlantısı etkin değil", 16, DIM);
        hSub.setGravity(Gravity.CENTER);
        hSub.setPadding(0, dp(6), 0, dp(20));
        hb.addView(hSub, new LinearLayout.LayoutParams(-1, -2));
        btnGo = bold("BAĞLAN", 18, Color.BLACK);
        btnGo.setLetterSpacing(0.15f);
        btnGo.setGravity(Gravity.CENTER);
        btnGo.setPadding(0, dp(20), 0, dp(20));
        btnGo.setOnClickListener(v -> toggleVpn());
        hb.addView(btnGo, lp(0));
        LinearLayout dc = card();
        dc.setOrientation(LinearLayout.HORIZONTAL);
        dc.setGravity(Gravity.CENTER_VERTICAL);
        dc.addView(tv("Bağlantı süresi", 16, DIM), new LinearLayout.LayoutParams(0, -2, 1f));
        hDur = bold("00:00:00", 30, Color.WHITE);
        hDur.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        dc.addView(hDur);
        hb.addView(dc, lp(16));
        hb.addView(pair(stat("İNDİRME", sDown), stat("YÜKLEME", sUp)), lp(14));
        hb.addView(pair(stat("BAĞLANTI", sConn), stat("KULLANILAN VERİ", sData)), lp(14));
        hs.addView(hb);
        homeSv = hs;
        pages.addView(hs);

        // TRAFİK
        LinearLayout tp = new LinearLayout(this);
        tp.setOrientation(LinearLayout.VERTICAL);
        LinearLayout th = new LinearLayout(this);
        th.setOrientation(LinearLayout.VERTICAL);
        th.setPadding(dp(24), dp(18), dp(24), dp(14));
        trTitle = bold("Trafik", 34, Color.WHITE);
        trTitle.setSingleLine(true);
        trTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        trSub = tv("", 16, DIM);
        trSub.setPadding(0, dp(4), 0, 0);
        trSub.setOnClickListener(v -> onBackPressed());
        th.addView(trTitle);
        th.addView(trSub);
        tp.addView(th);
        FrameLayout tf = new FrameLayout(this);
        lv = new ListView(this);
        lv.setDividerHeight(0);
        lv.setDivider(null);
        lv.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        lv.setVerticalScrollBarEnabled(false);
        ad = new BaseAdapter() {
            @Override public int getCount() { return lvl == L_LIST ? apps.size() : lvl == L_APP ? conns.size() : 0; }
            @Override public Object getItem(int i) { return null; }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup p) {
                if (lvl == L_LIST) {
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
        lv.setOnItemClickListener((a, v, i, id) -> {
            if (lvl == L_LIST && i < apps.size()) { selUid = (Integer) apps.get(i)[0]; lvl = L_APP; refresh(); }
            else if (lvl == L_APP && i < conns.size()) { selConn = conns.get(i); lvl = L_DET; refresh(); }
        });
        tf.addView(lv);
        detSv = new ScrollView(this);
        detSv.setVerticalScrollBarEnabled(false);
        LinearLayout dbox = new LinearLayout(this);
        dbox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout dcard = card();
        detail = tv("", 14, Color.WHITE);
        detail.setTypeface(Typeface.MONOSPACE);
        detail.setLineSpacing(0, 1.2f);
        dcard.addView(detail);
        dbox.addView(dcard, lp(0));
        dbox.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(20)));
        detSv.addView(dbox);
        tf.addView(detSv);
        empty = tv("Henüz trafik yok.\nAna sayfadan BAĞLAN'a bas.", 16, DIM);
        empty.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(-2, -2);
        ep.gravity = Gravity.CENTER;
        tf.addView(empty, ep);
        tp.addView(tf, new LinearLayout.LayoutParams(-1, 0, 1f));
        trafPage = tp;
        pages.addView(tp);

        // AYARLAR
        ScrollView ss = new ScrollView(this);
        ss.setVerticalScrollBarEnabled(false);
        LinearLayout sb = new LinearLayout(this);
        sb.setOrientation(LinearLayout.VERTICAL);
        LinearLayout sh = new LinearLayout(this);
        sh.setOrientation(LinearLayout.VERTICAL);
        sh.setPadding(dp(24), dp(18), dp(24), dp(4));
        sh.addView(bold("Ayarlar", 34, Color.WHITE));
        TextView ssub = tv("Uygulama ve izleme seçenekleri", 16, DIM);
        ssub.setPadding(0, dp(4), 0, 0);
        sh.addView(ssub);
        sb.addView(sh);
        setBox = new LinearLayout(this);
        setBox.setOrientation(LinearLayout.VERTICAL);
        sb.addView(setBox);
        ss.addView(sb);
        setSv = ss;
        pages.addView(ss);

        root.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1f));
        View line = new View(this);
        line.setBackgroundColor(0xFF1A1A1A);
        root.addView(line, new LinearLayout.LayoutParams(-1, 2));
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(14), dp(6), dp(14), dp(8));
        String[] nn = {"Ana Sayfa", "Trafik", "Ayarlar"};
        for (int i = 0; i < 3; i++) {
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1f);
            np.setMargins(dp(4), 0, dp(4), 0);
            bar.addView(navItem(i, nn[i]), np);
        }
        root.addView(bar);

        setContentView(root);
        nav(HOME);
    }

    @Override public void onBackPressed() {
        if (tab == TRAF && lvl > 0) { lvl--; refresh(); }
        else if (tab != HOME) nav(HOME);
        else super.onBackPressed();
    }

    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }
}
