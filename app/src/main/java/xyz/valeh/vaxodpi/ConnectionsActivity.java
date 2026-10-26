package xyz.valeh.vaxodpi;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class ConnectionsActivity extends Activity {
    static final int LIST = 0, APP = 1, CONN = 2, PKTS = 3, PKT = 4, ALL = -99;
    static final int CARD = 0xFF0C0C0C, LINE = 0xFF222222, DIM = 0xFF8A8A8A, OFF = 0xFF5E5E5E;
    static final String[] FN = {"Tümü", "TCP", "UDP", "DNS", "TLS"};
    static final SimpleDateFormat TF = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    static class Graph extends View {
        final float[] dn = new float[60], up = new float[60];
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Graph(Context c) { super(c); }
        void push(float d, float u) {
            System.arraycopy(dn, 1, dn, 0, 59);
            System.arraycopy(up, 1, up, 0, 59);
            dn[59] = d; up[59] = u;
            invalidate();
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), mx = 1;
            for (int i = 0; i < 60; i++) mx = Math.max(mx, Math.max(dn[i], up[i]));
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(0xFF1E1E1E);
            for (int k = 1; k < 4; k++) c.drawLine(0, h * k / 4, w, h * k / 4, p);
            line(c, dn, mx, w, h, Color.WHITE, true);
            line(c, up, mx, w, h, 0xFF777777, false);
        }
        void line(Canvas c, float[] a, float mx, float w, float h, int col, boolean fill) {
            Path path = new Path();
            for (int i = 0; i < 60; i++) {
                float x = w * i / 59f, y = h - (a[i] / mx) * (h - 8) - 4;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            if (fill) {
                Path f = new Path(path);
                f.lineTo(w, h); f.lineTo(0, h); f.close();
                p.setStyle(Paint.Style.FILL); p.setColor(0x22FFFFFF);
                c.drawPath(f, p);
            }
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4); p.setColor(col);
            c.drawPath(path, p);
        }
    }

    int tab, lvl = LIST, selUid, filter, pktMode, selNum;
    boolean imp;
    PacketStats.Conn selConn;
    PacketStats.Pkt selPkt;
    final Handler h = new Handler(Looper.getMainLooper());
    final HashMap<Integer, String> labels = new HashMap<>();
    final HashMap<Integer, Drawable> icons = new HashMap<>();
    final List<Object[]> apps = new ArrayList<>();
    final List<PacketStats.Conn> conns = new ArrayList<>();
    final List<PacketStats.Pkt> pkts = new ArrayList<>();
    float d, gDown, gUp;
    long pendingUntil, maxBytes = 1, lastT, lastIn, lastOut;
    String crash = "";
    SharedPreferences sp;
    TextView sub, status, gtxt, empty, detail, btnList, btnGo, btnSet;
    Graph graph;
    LinearLayout topBox, filtRow, srcRow, detActions, setBox, listPage;
    ListView lv;
    ScrollView detSv, setSv;
    BaseAdapter ad;

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
        c.setBackground(rr(CARD, 20, LINE));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        return c;
    }

    LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(14), dp(top), dp(14), 0);
        return p;
    }

    TextView chip(String t, boolean on, int padV, View.OnClickListener l) {
        TextView c = bold(t, 12, on ? Color.BLACK : DIM);
        c.setGravity(Gravity.CENTER);
        c.setBackground(on ? rr(Color.WHITE, 22, 0) : rr(0xFF141414, 22, 0xFF333333));
        c.setPadding(dp(6), dp(padV), dp(6), dp(padV));
        c.setOnClickListener(l);
        return c;
    }

    void put(LinearLayout r, View c) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        r.addView(c, p);
    }

    // ---------- yardımcılar ----------
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

    static String clock(long t) { return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(t)); }

    static String proto(int p) { return p == 6 ? "TCP" : p == 17 ? "UDP" : p == 1 ? "ICMP" : "IP/" + p; }

    static String dest(PacketStats.Conn c) { return c.name != null ? c.name : c.raddr + ":" + c.rport; }

    boolean match(PacketStats.Conn c) {
        switch (filter) {
            case 1: return c.proto == 6;
            case 2: return c.proto == 17;
            case 3: return c.rport == 53 || c.rport == 853 || c.lport == 53;
            case 4: return c.proto == 6 && (c.rport == 443 || c.rport == 853 || c.rport == 8443);
            default: return true;
        }
    }

    int minLvl() { return imp ? APP : LIST; }

    boolean running() { return System.currentTimeMillis() - PacketStats.lastPkt < 8000; }

    // ---------- VPN ----------
    void startVpn() {
        pendingUntil = System.currentTimeMillis() + 7000;
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2); } catch (Throwable ignored) { }
        }
        Intent i = VpnService.prepare(this);
        if (i != null) startActivityForResult(i, 1); else launch();
    }

    void launch() {
        Intent s = new Intent(this, VaxoVpnService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
    }

    void stopVpn() {
        Intent s = new Intent(this, VaxoVpnService.class);
        s.setAction("xyz.valeh.vaxodpi.STOP");
        startService(s);
        pendingUntil = 0;
    }

    // ---------- PCAP dışa / içe aktarma ----------
    void exportPcap() {
        if (PacketStats.pcapPkts == 0) {
            Toast.makeText(this, "Kayıtlı paket yok. Önce VPN'i açıp trafik oluştur.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/vnd.tcpdump.pcap");
        i.putExtra(Intent.EXTRA_TITLE, "vaxoscope-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".pcap");
        startActivityForResult(i, 3);
    }

    void importPcap() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, 4);
    }

    @Override protected void onActivityResult(int rq, int rs, Intent data) {
        if (rq == 1) { if (rs == RESULT_OK) launch(); else pendingUntil = 0; return; }
        if (rs != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        if (rq == 3) {
            new Thread(() -> {
                String msg;
                try (OutputStream o = getContentResolver().openOutputStream(u)) {
                    long n = PacketStats.exportTo(o);
                    msg = n < 0 ? "Kayıtlı paket yok" : "Kaydedildi: " + fmt(n);
                } catch (Throwable e) { msg = "Hata: " + e.getMessage(); }
                final String m = msg;
                runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
            }).start();
        } else if (rq == 4) {
            new Thread(() -> {
                int n;
                String msg;
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    n = PacketStats.importFrom(in);
                } catch (Throwable e) { n = -9; }
                if (n >= 0) msg = n + " paket içe aktarıldı";
                else if (n == -3) msg = "pcapng desteklenmiyor, klasik .pcap kullan";
                else if (n == -2) msg = "Bu bağlantı türü desteklenmiyor";
                else if (n == -1) msg = "Geçerli bir pcap dosyası değil";
                else msg = "Dosya okunamadı";
                final String m = msg;
                final boolean ok = n >= 0;
                runOnUiThread(() -> {
                    Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                    if (ok) { imp = true; selUid = ALL; tab = 0; buildFilters(); go(APP); }
                });
            }).start();
        }
    }

    // ---------- görünüm parçaları ----------
    View row(Drawable ic, String t1, String t2, String right, float frac) {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(14), dp(4), dp(14), dp(4));
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(rr(CARD, 18, LINE));
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        if (ic != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(ic);
            c.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }
        LinearLayout m = new LinearLayout(this);
        m.setOrientation(LinearLayout.VERTICAL);
        m.setPadding(ic != null ? dp(12) : 0, 0, dp(8), 0);
        TextView a = bold(t1, 15, Color.WHITE);
        a.setSingleLine(true);
        a.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView b = tv(t2, 12, DIM);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        m.addView(a);
        m.addView(b);
        if (frac >= 0) {
            b.setPadding(0, dp(2), 0, dp(7));
            float f = Math.max(0.03f, Math.min(1f, frac));
            LinearLayout bar = new LinearLayout(this);
            bar.setBackground(rr(0xFF1C1C1C, 2, 0));
            View on = new View(this);
            on.setBackground(rr(Color.WHITE, 2, 0));
            bar.addView(on, new LinearLayout.LayoutParams(0, dp(3), f));
            bar.addView(new View(this), new LinearLayout.LayoutParams(0, dp(3), 1f - f));
            m.addView(bar, new LinearLayout.LayoutParams(-1, dp(3)));
        }
        c.addView(m, new LinearLayout.LayoutParams(0, -2, 1f));
        c.addView(bold(right, 12, Color.WHITE));
        wrap.addView(c);
        return wrap;
    }

    String detailText(PacketStats.Conn c) {
        long now = System.currentTimeMillis();
        String ver = c.raddr.indexOf(':') >= 0 ? "IPv6" : "IPv4";
        long sec = Math.max(1, (c.last - c.first) / 1000);
        return "UYGULAMA\n" + (imp ? "-" : label(c.uid) + "\n" + pkg(c.uid))
                + "\n\nSİTE ADI (SNI/DNS)\n" + (c.name != null ? c.name : "-")
                + "\n\nPROTOKOL\n" + proto(c.proto) + " · " + ver
                + "\n\nADRESLER\nYerel  " + c.laddr + ":" + c.lport + "\nUzak   " + c.raddr + ":" + c.rport
                + "\n\nTRAFİK\nGiden  " + c.pOut + " paket · " + fmt(c.bOut)
                + "\nGelen  " + c.pIn + " paket · " + fmt(c.bIn)
                + "\nToplam " + fmt(c.bOut + c.bIn) + "\nHız    " + fmt((c.bOut + c.bIn) / sec) + "/sn"
                + "\n\nZAMAN\nİlk    " + clock(c.first) + "\nSon    " + clock(c.last) + "\nSüre   " + dur(c.last - c.first)
                + "\nDurum  " + (imp ? "Dosyadan" : now - c.last < 3000 ? "Aktif" : "Boşta");
    }

    void buildFilters() {
        srcRow.removeAllViews();
        filtRow.removeAllViews();
        put(srcRow, chip("Canlı", !imp, 9, v -> { imp = false; buildFilters(); go(LIST); }));
        put(srcRow, chip("Dosya (PCAP)", imp, 9, v -> { imp = true; selUid = ALL; buildFilters(); go(APP); }));
        for (int i = 0; i < FN.length; i++) {
            final int k = i;
            put(filtRow, chip(FN[i], filter == i, 9, v -> { filter = k; buildFilters(); refresh(); }));
        }
    }

    void buildDetActions() {
        detActions.removeAllViews();
        if (selConn == null) return;
        if (lvl == CONN) {
            int n = selConn.snapshot().size();
            put(detActions, chip("Paketleri gör (son " + n + ")", true, 13, v -> go(PKTS)));
        } else if (lvl == PKT) {
            put(detActions, chip("Ayrıntı", pktMode == 0, 13, v -> { pktMode = 0; buildDetActions(); refresh(); }));
            put(detActions, chip("Hex", pktMode == 1, 13, v -> { pktMode = 1; buildDetActions(); refresh(); }));
        }
    }

    void go(int l) {
        lvl = l;
        tab = 0;
        buildDetActions();
        refresh();
    }

    LinearLayout toggle(String title, String desc, final String key, final boolean def, final Runnable after) {
        final boolean on = sp.getBoolean(key, def);
        LinearLayout c = card();
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        TextView b = tv(desc, 13, DIM);
        b.setPadding(0, dp(3), dp(10), 0);
        l.addView(bold(title, 16, Color.WHITE));
        l.addView(b);
        c.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView ch = chip(on ? "Açık" : "Kapalı", on, 11, v -> {
            sp.edit().putBoolean(key, !on).apply();
            if (after != null) after.run();
            buildSettings();
        });
        ch.setPadding(dp(16), dp(11), dp(16), dp(11));
        c.addView(ch);
        return c;
    }

    TextView btn(String t, boolean primary, View.OnClickListener l) {
        TextView b = bold(t, 15, primary ? Color.BLACK : Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setBackground(primary ? rr(Color.WHITE, 26, 0) : rr(Color.BLACK, 26, 0xFF444444));
        b.setPadding(0, dp(14), 0, dp(14));
        b.setOnClickListener(l);
        return b;
    }

    void buildSettings() {
        setBox.removeAllViews();
        setBox.addView(toggle("PCAP kaydı", "Ham paketler capture.pcap dosyasına yazılır (en çok 64 MB).\n" + PacketStats.pcapInfo(),
                "cap_on", true, () -> PacketStats.pcapOn = sp.getBoolean("cap_on", true)), lp(12));
        LinearLayout c2 = card();
        c2.addView(bold("PCAP dosyaları", 16, Color.WHITE));
        TextView t2 = tv("Dışa aktar: Wireshark'ta açılır. İçe aktar: eski kaydı çevrimdışı incele.", 13, DIM);
        t2.setPadding(0, dp(3), 0, dp(12));
        c2.addView(t2);
        c2.addView(btn("PCAP dışa aktar", true, v -> exportPcap()));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(0, dp(10), 0, 0);
        c2.addView(btn("PCAP içe aktar", false, v -> importPcap()), bp);
        setBox.addView(c2, lp(12));
        setBox.addView(toggle("Yalnızca aktif bağlantılar", "Son 5 saniyede trafiği olanları göster.", "only_active", false, null), lp(12));
        setBox.addView(toggle("Log kaydı", "Hata ayıklama olaylarını dosyaya yazar.", "log_on", false, null), lp(12));

        LinearLayout c3 = card();
        c3.addView(bold("DNS sunucusu", 16, Color.WHITE));
        TextView t3 = tv("Değişiklik bir sonraki bağlantıda geçerli olur.", 13, DIM);
        t3.setPadding(0, dp(3), 0, dp(12));
        c3.addView(t3);
        String[] dn = {"Cloudflare", "Google", "Quad9", "AdGuard"};
        int cur = sp.getInt("dns", 0);
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, r == 0 ? 0 : dp(8), 0, 0);
            for (int k = r * 2; k < r * 2 + 2; k++) {
                final int kk = k;
                put(row, chip(dn[k], cur == k, 12, v -> { sp.edit().putInt("dns", kk).apply(); buildSettings(); }));
            }
            c3.addView(row);
        }
        setBox.addView(c3, lp(12));

        LinearLayout c4 = card();
        c4.addView(bold("Gizlilik", 16, Color.WHITE));
        TextView pv = tv("VaxoScope yalnızca bu telefonun kendi trafiğini izler. Veri telefondan çıkmaz, internete hiçbir şey gönderilmez. HTTPS içeriği okunmaz; yalnızca site adı, adres, port ve bayt sayıları görünür.\n\nPrivate DNS açıksa DNS sorguları şifreli gider ve bazı adlar eksik kalır.", 13, DIM);
        pv.setPadding(0, dp(6), 0, 0);
        c4.addView(pv);
        setBox.addView(c4, lp(12));

        setBox.addView(btn("Listeyi ve kaydı temizle", false, v -> {
            PacketStats.clearAll();
            PacketStats.IMPORT.clear();
            Toast.makeText(this, "Temizlendi", Toast.LENGTH_SHORT).show();
            buildSettings();
        }), lp(12));
        if (crash.length() > 0) {
            LinearLayout c5 = card();
            c5.addView(tv(crash, 11, DIM));
            setBox.addView(c5, lp(12));
        }
        setBox.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(20)));
    }

    // ---------- yenileme ----------
    void refresh() {
        long now = System.currentTimeMillis();
        boolean run = running(), pend = !run && now < pendingUntil;
        btnGo.setText(run ? "Durdur" : pend ? "Bağlanıyor…" : "Aç");
        btnGo.setTextColor(run ? Color.WHITE : Color.BLACK);
        btnGo.setBackground(run ? rr(Color.BLACK, 24, Color.WHITE) : rr(Color.WHITE, 24, 0));
        status.setText(run ? "● Açık" : pend ? "● Bağlanıyor" : "○ Kapalı");
        status.setTextColor(run ? Color.WHITE : OFF);
        btnList.setTextColor(tab == 0 ? Color.WHITE : OFF);
        btnSet.setTextColor(tab == 1 ? Color.WHITE : OFF);

        if (lastT > 0 && now > lastT) {
            float dt = (now - lastT) / 1000f;
            gDown = (PacketStats.totIn - lastIn) / 1024f / dt;
            gUp = (PacketStats.totOut - lastOut) / 1024f / dt;
            if (now - lastT > 400) graph.push(gDown, gUp);
            gtxt.setText("↓ " + String.format(Locale.US, "%.1f", gDown) + " KB/s      ↑ " + String.format(Locale.US, "%.1f", gUp) + " KB/s");
        }
        if (now - lastT > 400) { lastT = now; lastIn = PacketStats.totIn; lastOut = PacketStats.totOut; }

        boolean listy = tab == 0 && (lvl == LIST || lvl == APP || lvl == PKTS);
        boolean det = tab == 0 && (lvl == CONN || lvl == PKT);
        listPage.setVisibility(listy ? View.VISIBLE : View.GONE);
        detSv.setVisibility(det ? View.VISIBLE : View.GONE);
        setSv.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        topBox.setVisibility(lvl <= APP ? View.VISIBLE : View.GONE);
        graph.setVisibility(imp ? View.GONE : View.VISIBLE);
        gtxt.setVisibility(imp ? View.GONE : View.VISIBLE);
        if (tab == 1) { sub.setText("Ayarlar"); return; }

        Map<String, PacketStats.Conn> tbl = imp ? PacketStats.IMPORT : PacketStats.TABLE;
        boolean onlyAct = !imp && sp.getBoolean("only_active", false);
        HashMap<Integer, long[]> m = new HashMap<>();
        conns.clear();
        long up = 0, dn = 0;
        for (PacketStats.Conn c : tbl.values()) {
            if (!match(c)) continue;
            if (onlyAct && now - c.last > 5000) continue;
            up += c.bOut; dn += c.bIn;
            int u = c.uid < 0 ? -1 : c.uid;
            long[] a = m.get(u);
            if (a == null) { a = new long[4]; m.put(u, a); }
            a[0]++; a[1] += c.bOut; a[2] += c.bIn;
            if (c.last > a[3]) a[3] = c.last;
            if (lvl == APP && (selUid == ALL || u == selUid)) conns.add(c);
        }
        apps.clear();
        for (Map.Entry<Integer, long[]> e : m.entrySet()) apps.add(new Object[]{e.getKey(), e.getValue()});
        Collections.sort(apps, (x, y) -> {
            long[] a = (long[]) x[1], b = (long[]) y[1];
            return Long.compare(b[1] + b[2], a[1] + a[2]);
        });
        Collections.sort(conns, (a, b) -> Long.compare(b.first, a.first));
        maxBytes = 1;
        if (lvl == LIST && !apps.isEmpty()) { long[] a = (long[]) apps.get(0)[1]; maxBytes = Math.max(1, a[1] + a[2]); }
        for (PacketStats.Conn c : conns) maxBytes = Math.max(maxBytes, c.bOut + c.bIn);

        String back = lvl > minLvl() ? "‹  " : "";
        if (lvl == LIST) {
            sub.setText("Uygulamalar · " + apps.size() + "   ↑" + fmt(up) + " ↓" + fmt(dn));
            empty.setText("Henüz trafik yok.\nAşağıdan Aç'a bas.");
        } else if (lvl == APP) {
            sub.setText(back + (imp ? "Dosya" : label(selUid)) + " · " + conns.size() + " bağlantı");
            empty.setText(imp ? "Dosya boş ya da filtreye uyan yok.\nAyarlar'dan PCAP içe aktar." : "Bu filtreye uyan bağlantı yok.");
        } else if (selConn != null && lvl == CONN) {
            sub.setText(back + dest(selConn));
            detail.setTextSize(13);
            detail.setText(detailText(selConn));
        } else if (selConn != null && lvl == PKTS) {
            pkts.clear();
            pkts.addAll(selConn.snapshot());
            sub.setText(back + "Paketler · " + dest(selConn));
            empty.setText("Bu bağlantı için tutulan paket yok.");
        } else if (selPkt != null && lvl == PKT) {
            sub.setText(back + "Paket #" + selNum);
            String hdr = "#" + selNum + "  " + (selPkt.out ? "↑ giden" : "↓ gelen") + "  " + TF.format(new Date(selPkt.t))
                    + "\n" + selPkt.orig + " B (yakalanan " + selPkt.data.length + " B)\n\n";
            if (pktMode == 0) { detail.setTextSize(12); detail.setText(hdr + PacketStats.decode(selPkt)[1]); }
            else { detail.setTextSize(11); detail.setText(hdr + PacketStats.hexdump(selPkt.data)); }
        }
        ad.notifyDataSetChanged();
    }

    final Runnable tick = new Runnable() {
        @Override public void run() {
            try { refresh(); } catch (Throwable ignored) { }
            h.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("vaxodpi", MODE_PRIVATE);
        try {
            File cf = new File(getFilesDir(), "crash.txt");
            if (cf.exists()) {
                byte[] bb = new byte[(int) Math.min(cf.length(), 900)];
                FileInputStream in = new FileInputStream(cf);
                int n = in.read(bb);
                in.close();
                crash = "ÖNCEKİ ÇÖKME\n" + new String(bb, 0, Math.max(n, 0));
                cf.delete();
            }
        } catch (Throwable ignored) { }
        Thread.setDefaultUncaughtExceptionHandler((th, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                FileWriter w = new FileWriter(new File(getFilesDir(), "crash.txt"));
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
        root.setPadding(0, dp(34), 0, 0);

        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        brandRow.setPadding(dp(20), dp(8), dp(20), 0);
        TextView brand = bold("VaxoScope", 28, Color.WHITE);
        status = tv("○ Kapalı", 13, OFF);
        brandRow.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));
        brandRow.addView(status);
        root.addView(brandRow);

        sub = tv("", 14, DIM);
        sub.setPadding(dp(20), dp(4), dp(20), dp(8));
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sub.setOnClickListener(v -> { if (tab == 0 && lvl > minLvl()) onBackPressed(); });
        root.addView(sub);

        FrameLayout pages = new FrameLayout(this);

        // liste sayfası
        listPage = new LinearLayout(this);
        listPage.setOrientation(LinearLayout.VERTICAL);
        topBox = new LinearLayout(this);
        topBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout gc = card();
        gc.setPadding(dp(14), dp(10), dp(14), dp(10));
        gtxt = bold("↓ 0.0 KB/s      ↑ 0.0 KB/s", 12, Color.WHITE);
        gtxt.setPadding(0, 0, 0, dp(6));
        graph = new Graph(this);
        gc.addView(gtxt);
        gc.addView(graph, new LinearLayout.LayoutParams(-1, dp(64)));
        topBox.addView(gc, lp(0));
        srcRow = new LinearLayout(this);
        srcRow.setPadding(dp(11), 0, dp(11), 0);
        topBox.addView(srcRow, new LinearLayout.LayoutParams(-1, -2));
        ((LinearLayout.LayoutParams) srcRow.getLayoutParams()).topMargin = dp(10);
        filtRow = new LinearLayout(this);
        filtRow.setPadding(dp(11), 0, dp(11), dp(6));
        topBox.addView(filtRow, new LinearLayout.LayoutParams(-1, -2));
        ((LinearLayout.LayoutParams) filtRow.getLayoutParams()).topMargin = dp(8);
        listPage.addView(topBox);
        FrameLayout lf = new FrameLayout(this);
        lv = new ListView(this);
        lv.setDividerHeight(0);
        lv.setDivider(null);
        lv.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        lv.setVerticalScrollBarEnabled(false);
        ad = new BaseAdapter() {
            @Override public int getCount() { return lvl == LIST ? apps.size() : lvl == APP ? conns.size() : lvl == PKTS ? pkts.size() : 0; }
            @Override public Object getItem(int i) { return null; }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup p) {
                if (lvl == LIST) {
                    Object[] o = apps.get(i);
                    int u = (Integer) o[0];
                    long[] a = (long[]) o[1];
                    String dot = System.currentTimeMillis() - a[3] < 3000 ? "● " : "";
                    return row(icon(u), dot + label(u), a[0] + " bağlantı · ↑" + fmt(a[1]) + " ↓" + fmt(a[2]),
                            fmt(a[1] + a[2]), (float) (a[1] + a[2]) / maxBytes);
                }
                if (lvl == APP) {
                    PacketStats.Conn c = conns.get(i);
                    return row(null, dest(c), proto(c.proto) + " · " + c.raddr + ":" + c.rport + " · ↑" + fmt(c.bOut) + " ↓" + fmt(c.bIn),
                            fmt(c.bOut + c.bIn), (float) (c.bOut + c.bIn) / maxBytes);
                }
                PacketStats.Pkt k = pkts.get(i);
                long num = selConn.pOut + selConn.pIn - pkts.size() + i + 1;
                return row(null, "#" + num + "  " + (k.out ? "↑ " : "↓ ") + PacketStats.decode(k)[0],
                        TF.format(new Date(k.t)), k.orig + " B", -1f);
            }
        };
        lv.setAdapter(ad);
        lv.setOnItemClickListener((a, v, i, id) -> {
            if (lvl == LIST && i < apps.size()) { selUid = (Integer) apps.get(i)[0]; go(APP); }
            else if (lvl == APP && i < conns.size()) { selConn = conns.get(i); go(CONN); }
            else if (lvl == PKTS && i < pkts.size()) {
                selPkt = pkts.get(i);
                selNum = (int) (selConn.pOut + selConn.pIn - pkts.size() + i + 1);
                pktMode = 0;
                go(PKT);
            }
        });
        empty = tv("", 15, DIM);
        empty.setGravity(Gravity.CENTER);
        lf.addView(lv);
        FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(-2, -2);
        ep.gravity = Gravity.CENTER;
        lf.addView(empty, ep);
        lv.setEmptyView(empty);
        listPage.addView(lf, new LinearLayout.LayoutParams(-1, 0, 1f));
        pages.addView(listPage);

        // ayrıntı sayfası
        detSv = new ScrollView(this);
        detSv.setVerticalScrollBarEnabled(false);
        LinearLayout dbox = new LinearLayout(this);
        dbox.setOrientation(LinearLayout.VERTICAL);
        detActions = new LinearLayout(this);
        detActions.setPadding(dp(11), 0, dp(11), 0);
        dbox.addView(detActions, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout dcard = card();
        detail = tv("", 13, Color.WHITE);
        detail.setTypeface(Typeface.MONOSPACE);
        detail.setLineSpacing(0, 1.15f);
        dcard.addView(detail);
        dbox.addView(dcard, lp(10));
        dbox.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(20)));
        detSv.addView(dbox);
        pages.addView(detSv);

        // ayarlar sayfası
        setSv = new ScrollView(this);
        setSv.setVerticalScrollBarEnabled(false);
        setBox = new LinearLayout(this);
        setBox.setOrientation(LinearLayout.VERTICAL);
        setSv.addView(setBox);
        pages.addView(setSv);

        root.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(rr(0xFF0E0E0E, 34, 0xFF242424));
        bar.setPadding(dp(10), dp(10), dp(10), dp(10));
        btnList = bold("Liste", 15, Color.WHITE);
        btnSet = bold("Ayarlar", 15, OFF);
        btnGo = bold("Aç", 16, Color.BLACK);
        btnList.setGravity(Gravity.CENTER);
        btnSet.setGravity(Gravity.CENTER);
        btnGo.setGravity(Gravity.CENTER);
        btnGo.setPadding(0, dp(14), 0, dp(14));
        btnList.setPadding(0, dp(12), 0, dp(12));
        btnSet.setPadding(0, dp(12), 0, dp(12));
        btnList.setOnClickListener(v -> { if (tab == 0) go(minLvl()); else { tab = 0; refresh(); } });
        btnSet.setOnClickListener(v -> { tab = 1; buildSettings(); refresh(); });
        btnGo.setOnClickListener(v -> { if (running()) stopVpn(); else startVpn(); });
        bar.addView(btnList, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(0, -2, 1.5f);
        gp.setMargins(dp(8), 0, dp(8), 0);
        bar.addView(btnGo, gp);
        bar.addView(btnSet, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(dp(16), dp(8), dp(16), dp(16));
        root.addView(bar, bp);

        setContentView(root);
        buildFilters();
        refresh();
    }

    @Override public void onBackPressed() {
        if (tab == 1) { tab = 0; refresh(); }
        else if (lvl > minLvl()) go(lvl - 1);
        else super.onBackPressed();
    }

    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }
}
