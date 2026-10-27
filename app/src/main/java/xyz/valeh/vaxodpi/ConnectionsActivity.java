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
import org.json.JSONArray;
import org.json.JSONObject;

public class ConnectionsActivity extends Activity {
    static final int LIST = 0, APP = 1, CONN = 2, PKTS = 3, PKT = 4, ALL = -99, DNSL = 5, FLOW = 6, REP = 7;
    static final int CARD = 0xFF0C0C0C, LINE = 0xFF222222, DIM = 0xFF8A8A8A, OFF = 0xFF5E5E5E;
    static final int RED = 0xFFE5484D, GRN = 0xFF3FB950, BLU = 0xFF4C8DFF, ORG = 0xFFF0883E;
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

    int tab, lvl = LIST, selUid, filter, pktMode, selNum, lang, rowAccent;
    boolean imp;
    String query = "";
    PacketStats.Conn selConn;
    PacketStats.Pkt selPkt;
    Set<String> pendKeys;
    final Handler h = new Handler(Looper.getMainLooper());
    final HashMap<Integer, String> labels = new HashMap<>();
    final HashMap<Integer, Drawable> icons = new HashMap<>();
    final List<Object[]> apps = new ArrayList<>();
    final List<PacketStats.Conn> conns = new ArrayList<>();
    final List<PacketStats.Pkt> pkts = new ArrayList<>();
    final List<PacketStats.Dns> dnsRows = new ArrayList<>();
    final List<Object[]> repRows = new ArrayList<>();
    float d, gDown, gUp;
    long pendingUntil, maxBytes = 1, lastT, lastIn, lastOut;
    String crash = "";
    SharedPreferences sp;
    TextView sub, status, gtxt, empty, detail, btnList, btnGo, btnSet;
    EditText search;
    Graph graph;
    LinearLayout topBox, filtRow, srcRow, appRow, detActions, detActions2, setBox, listPage;
    ListView lv;
    ScrollView detSv, setSv;
    BaseAdapter ad;

    String t(String tr, String en, String ru) { return lang == 1 ? en : lang == 2 ? ru : tr; }

    int dp(float v) { return (int) (v * d); }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    GradientDrawable rr(int fill, float r, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(r));
        if (stroke != 0) g.setStroke(Math.max(2, dp(1.2f)), stroke);
        return g;
    }

    TextView tv(String s, int sz, int col) {
        TextView x = new TextView(this);
        x.setText(s); x.setTextSize(sz); x.setTextColor(col);
        return x;
    }

    TextView bold(String s, int sz, int col) {
        TextView x = tv(s, sz, col);
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

    TextView chip(String s, boolean on, int padV, View.OnClickListener l) {
        TextView c = bold(s, 12, on ? Color.BLACK : DIM);
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

    TextView btn(String s, boolean primary, View.OnClickListener l) {
        TextView b = bold(s, 15, primary ? Color.BLACK : Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setBackground(primary ? rr(Color.WHITE, 26, 0) : rr(Color.BLACK, 26, 0xFF444444));
        b.setPadding(0, dp(14), 0, dp(14));
        b.setOnClickListener(l);
        return b;
    }

    // ---------- yardımcılar ----------
    String label(int uid) {
        if (uid < 0) return t("Bilinmeyen", "Unknown", "Неизвестно");
        if (uid == 0) return t("Sistem (root)", "System (root)", "Система (root)");
        if (uid == 1000) return t("Android sistemi", "Android system", "Система Android");
        if (uid == 1051) return t("Sistem DNS", "System DNS", "Системный DNS");
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

    String pkgLabel(String pk) {
        if (pk.equals("?")) return t("Bilinmeyen", "Unknown", "Неизвестно");
        if (pk.startsWith("uid:")) {
            try { return label(Integer.parseInt(pk.substring(4))); } catch (Throwable e) { return pk; }
        }
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pk, 0)).toString();
        } catch (Throwable e) { return pk; }
    }

    Drawable pkgIcon(String pk) {
        try { return getPackageManager().getApplicationIcon(pk); }
        catch (Throwable e) { return getPackageManager().getDefaultActivityIcon(); }
    }

    static String fmt(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return (b / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", b / 1048576.0);
    }

    String dur(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + t(" sn", " s", " с");
        if (s < 3600) return (s / 60) + t(" dk", " min", " мин") + " " + (s % 60) + t(" sn", " s", " с");
        return (s / 3600) + t(" sa", " h", " ч") + " " + ((s % 3600) / 60) + t(" dk", " min", " мин");
    }

    static String clock(long x) { return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(x)); }

    static String proto(int p) { return p == 6 ? "TCP" : p == 17 ? "UDP" : p == 1 ? "ICMP" : "IP/" + p; }

    static String dest(PacketStats.Conn c) {
        return c.name != null ? c.name : c.guess != null ? "≈ " + c.guess : c.raddr + ":" + c.rport;
    }

    static long parseSize(String s) {
        try {
            long m = 1;
            if (s.endsWith("mb")) { m = 1048576; s = s.substring(0, s.length() - 2); }
            else if (s.endsWith("kb")) { m = 1024; s = s.substring(0, s.length() - 2); }
            else if (s.endsWith("b")) s = s.substring(0, s.length() - 1);
            return (long) (Double.parseDouble(s) * m);
        } catch (Throwable e) { return 0; }
    }

    boolean qmatch(PacketStats.Conn c, String hay) {
        String[] tk = query.split(" +");
        for (int i = 0; i < tk.length; i++) {
            String w = tk[i];
            if (w.length() == 0) continue;
            if (w.equals("tcp")) { if (c.proto != 6) return false; }
            else if (w.equals("udp")) { if (c.proto != 17) return false; }
            else if (w.equals("icmp")) { if (c.proto != 1) return false; }
            else if (w.equals("dns")) { if (!(c.rport == 53 || c.rport == 853)) return false; }
            else if (w.equals("tls")) { if (!(c.proto == 6 && (c.rport == 443 || c.rport == 853))) return false; }
            else if (w.equals("quic")) { if (!(c.proto == 17 && c.rport == 443)) return false; }
            else if (w.equals("ipv6")) { if (c.raddr.indexOf(':') < 0) return false; }
            else if (w.equals("ipv4")) { if (c.raddr.indexOf(':') >= 0) return false; }
            else if (w.equals("izleyici") || w.equals("tracker") || w.equals("reklam") || w.equals("трекер")) { if (!Trackers.is(c)) return false; }
            else if (w.equals("engelli") || w.equals("blocked") || w.equals("блок")) { if (!c.blocked) return false; }
            else if ((w.equals("port") || w.equals("ip") || w.equals("app") || w.equals("host")) && i + 1 < tk.length) {
                String a = tk[++i];
                if (w.equals("port")) { if (!(String.valueOf(c.rport).equals(a) || String.valueOf(c.lport).equals(a))) return false; }
                else if (w.equals("ip")) { if (!c.raddr.startsWith(a)) return false; }
                else if (w.equals("app")) { if (imp || !label(c.uid).toLowerCase(Locale.ROOT).contains(a)) return false; }
                else { if (!dest(c).toLowerCase(Locale.ROOT).contains(a)) return false; }
            }
            else if ((w.equals("country") || w.equals("ulke") || w.equals("ülke") || w.equals("страна")) && i + 1 < tk.length) {
                String a = tk[++i];
                String[] o = IpInfo.lookup(c.raddr);
                if (o == null || !o[0].equalsIgnoreCase(a)) return false;
            }
            else if ((w.equals("org") || w.equals("asn")) && i + 1 < tk.length) {
                String a = tk[++i];
                String[] o = IpInfo.lookup(c.raddr);
                if (o == null || !(o[2].toLowerCase(Locale.ROOT).contains(a) || ("as" + o[1]).equals(a))) return false;
            }
            else if (w.startsWith(">") && w.length() > 1) { if (c.bOut + c.bIn < parseSize(w.substring(1))) return false; }
            else if (!hay.contains(w)) return false;
        }
        return true;
    }

    boolean match(PacketStats.Conn c) {
        if (query.length() > 0) {
            String hay = ((imp ? "" : label(c.uid)) + " " + dest(c) + " " + c.raddr + ":" + c.rport + " "
                    + proto(c.proto) + " " + ownerText(c)).toLowerCase(Locale.ROOT);
            if (!qmatch(c, hay)) return false;
        }
        switch (filter) {
            case 1: return c.proto == 6;
            case 2: return c.proto == 17;
            case 3: return c.rport == 53 || c.rport == 853 || c.lport == 53;
            case 4: return c.proto == 6 && (c.rport == 443 || c.rport == 853 || c.rport == 8443);
            default: return true;
        }
    }

    int accentOf(PacketStats.Conn c) {
        if (c.blocked) return ORG;
        if (c.rst) return RED;
        if (c.rport == 53 || c.rport == 853 || c.lport == 53) return BLU;
        if (c.proto == 6 && (c.rport == 443 || c.rport == 8443)) return GRN;
        return 0;
    }

    int pktAccent(String br) {
        if (br.contains("RST")) return RED;
        if (br.startsWith("DNS")) return BLU;
        if (br.contains("TLS")) return GRN;
        return 0;
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

    // ---------- PCAP ----------
    void exportKeys(Set<String> keys, String tag) {
        if (PacketStats.pcapPkts == 0) {
            toast(t("Kayıtlı paket yok. PCAP kaydı açık olmalı.", "No recorded packets. PCAP recording must be on.", "Нет записанных пакетов. Включите запись PCAP."));
            return;
        }
        pendKeys = keys;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/vnd.tcpdump.pcap");
        i.putExtra(Intent.EXTRA_TITLE, "vaxoscope-" + tag + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".pcap");
        startActivityForResult(i, 3);
    }

    void exportPcap() { exportKeys(null, "all"); }

    void exportApp() {
        List<PacketStats.Conn> all = new ArrayList<>();
        for (PacketStats.Conn c : PacketStats.TABLE.values()) if ((c.uid < 0 ? -1 : c.uid) == selUid) all.add(c);
        String tag = label(selUid).replaceAll("[^A-Za-z0-9]", "");
        if (tag.length() == 0) tag = "app";
        exportKeys(PacketStats.keysOf(all), tag);
    }

    void importPcap() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, 4);
    }

    // ---------- yedek ----------
    JSONObject dump(String name) throws Exception {
        JSONObject j = new JSONObject();
        for (Map.Entry<String, ?> e : getSharedPreferences(name, 0).getAll().entrySet()) {
            Object v = e.getValue();
            JSONObject x = new JSONObject();
            if (v instanceof Boolean) { x.put("t", "b"); x.put("v", v); }
            else if (v instanceof Integer) { x.put("t", "i"); x.put("v", v); }
            else if (v instanceof Long) { x.put("t", "l"); x.put("v", v); }
            else if (v instanceof Float) { x.put("t", "f"); x.put("v", ((Float) v).doubleValue()); }
            else if (v instanceof String) { x.put("t", "s"); x.put("v", v); }
            else if (v instanceof Set) {
                JSONArray a = new JSONArray();
                for (Object o : (Set<?>) v) a.put(String.valueOf(o));
                x.put("t", "ss"); x.put("v", a);
            } else continue;
            j.put(e.getKey(), x);
        }
        return j;
    }

    void load(String name, JSONObject j) throws Exception {
        SharedPreferences.Editor e = getSharedPreferences(name, 0).edit();
        e.clear();
        Iterator<String> it = j.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONObject x = j.getJSONObject(k);
            String ty = x.getString("t");
            if (ty.equals("b")) e.putBoolean(k, x.getBoolean("v"));
            else if (ty.equals("i")) e.putInt(k, x.getInt("v"));
            else if (ty.equals("l")) e.putLong(k, x.getLong("v"));
            else if (ty.equals("f")) e.putFloat(k, (float) x.getDouble("v"));
            else if (ty.equals("s")) e.putString(k, x.getString("v"));
            else if (ty.equals("ss")) {
                JSONArray a = x.getJSONArray("v");
                HashSet<String> s = new HashSet<>();
                for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
                e.putStringSet(k, s);
            }
        }
        e.apply();
    }

    void doBackup(final Uri u) {
        new Thread(() -> {
            String msg;
            try (OutputStream o = getContentResolver().openOutputStream(u)) {
                PacketStats.saveUsage();
                JSONObject r = new JSONObject();
                r.put("app", "VaxoScope");
                r.put("v", 1);
                r.put("prefs", dump("vaxodpi"));
                r.put("usage", dump("vaxo_usage"));
                o.write(r.toString(1).getBytes("UTF-8"));
                msg = t("Yedek kaydedildi", "Backup saved", "Резервная копия сохранена");
            } catch (Throwable e) { msg = "Hata: " + e.getMessage(); }
            final String m = msg;
            runOnUiThread(() -> toast(m));
        }).start();
    }

    void doRestore(final Uri u) {
        new Thread(() -> {
            String msg;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
                JSONObject j = new JSONObject(bo.toString("UTF-8"));
                if (!"VaxoScope".equals(j.optString("app"))) throw new Exception("x");
                load("vaxodpi", j.getJSONObject("prefs"));
                load("vaxo_usage", j.getJSONObject("usage"));
                msg = t("Yedek geri yüklendi", "Backup restored", "Копия восстановлена");
            } catch (Throwable e) {
                msg = t("Geçerli bir yedek dosyası değil", "Not a valid backup file", "Недействительный файл копии");
            }
            final String m = msg;
            runOnUiThread(() -> {
                toast(m);
                lang = sp.getInt("lang", 0);
                PacketStats.lang = lang;
                PacketStats.pcapOn = sp.getBoolean("cap_on", true);
                PacketStats.loadBlocks(this);
                applyLang();
            });
        }).start();
    }

    @Override protected void onActivityResult(int rq, int rs, Intent data) {
        if (rq == 1) { if (rs == RESULT_OK) launch(); else pendingUntil = 0; return; }
        if (rs != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        if (rq == 3) {
            final Set<String> keys = pendKeys;
            new Thread(() -> {
                String msg;
                try (OutputStream o = getContentResolver().openOutputStream(u)) {
                    long n = keys == null ? PacketStats.exportTo(o) : PacketStats.exportFiltered(o, keys);
                    if (n < 0) msg = t("Kayıtlı paket yok", "No recorded packets", "Нет записанных пакетов");
                    else if (keys == null) msg = t("Kaydedildi: ", "Saved: ", "Сохранено: ") + fmt(n);
                    else if (n == 0) msg = t("Eşleşen kayıtlı paket bulunamadı", "No matching recorded packets", "Подходящих пакетов не найдено");
                    else msg = t("Kaydedildi: ", "Saved: ", "Сохранено: ") + n + t(" paket", " packets", " пакетов");
                } catch (Throwable e) { msg = "Hata: " + e.getMessage(); }
                final String m = msg;
                runOnUiThread(() -> toast(m));
            }).start();
        } else if (rq == 4) {
            new Thread(() -> {
                int n;
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    n = PacketStats.importFrom(in);
                } catch (Throwable e) { n = -9; }
                String msg;
                if (n >= 0) msg = n + t(" paket içe aktarıldı", " packets imported", " пакетов импортировано");
                else if (n == -2) msg = t("Bu bağlantı türü desteklenmiyor", "This link type is not supported", "Этот тип канала не поддерживается");
                else if (n == -1) msg = t("Geçerli bir pcap dosyası değil", "Not a valid pcap file", "Недействительный файл pcap");
                else msg = t("Dosya okunamadı", "Could not read file", "Не удалось прочитать файл");
                final String m = msg;
                final boolean ok = n >= 0;
                runOnUiThread(() -> {
                    toast(m);
                    if (ok) { imp = true; selUid = ALL; tab = 0; buildFilters(); go(APP); }
                });
            }).start();
        } else if (rq == 5) doBackup(u);
        else if (rq == 6) doRestore(u);
    }

    // ---------- engelleme ----------
    boolean hostIsBlocked(String hh) {
        return sp.getStringSet("blk_hosts", new HashSet<String>()).contains(hh);
    }

    void toggleBlockApp(String pk) {
        if (pk.equals("-") || selUid < 10000) {
            toast(t("Sistem uygulamaları engellenemez", "System apps cannot be blocked", "Системные приложения нельзя блокировать"));
            return;
        }
        HashSet<String> s = new HashSet<>(sp.getStringSet("blk_apps", new HashSet<String>()));
        if (!s.add(pk)) s.remove(pk);
        sp.edit().putStringSet("blk_apps", s).apply();
        PacketStats.loadBlocks(this);
        buildAppRow();
        refresh();
    }

    void toggleBlockHost(String hh) {
        HashSet<String> s = new HashSet<>(sp.getStringSet("blk_hosts", new HashSet<String>()));
        if (!s.add(hh)) s.remove(hh);
        sp.edit().putStringSet("blk_hosts", s).apply();
        PacketStats.loadBlocks(this);
        buildDetActions();
        refresh();
    }

    void manageBlocks() {
        final List<String> keys = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (String pk : sp.getStringSet("blk_apps", new HashSet<String>())) {
            keys.add("a:" + pk);
            names.add(t("Uygulama: ", "App: ", "Приложение: ") + pkgLabel(pk));
        }
        for (String hh : sp.getStringSet("blk_hosts", new HashSet<String>())) {
            keys.add("h:" + hh);
            names.add(t("Site: ", "Host: ", "Хост: ") + hh);
        }
        if (keys.isEmpty()) { toast(t("Engelli öğe yok", "Nothing is blocked", "Нет блокировок")); return; }
        final boolean[] chk = new boolean[keys.size()];
        Arrays.fill(chk, true);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(t("Engelli liste (işareti kaldır = engeli aç)", "Blocked list (uncheck = unblock)", "Блокировки (снять отметку = разблокировать)"))
                .setMultiChoiceItems(names.toArray(new String[0]), chk, (dlg, which, on) -> chk[which] = on)
                .setPositiveButton(t("Kaydet", "Save", "Сохранить"), (dlg, w) -> {
                    HashSet<String> a = new HashSet<>(), hs = new HashSet<>();
                    for (int i = 0; i < chk.length; i++) {
                        if (!chk[i]) continue;
                        String k = keys.get(i);
                        if (k.startsWith("a:")) a.add(k.substring(2)); else hs.add(k.substring(2));
                    }
                    sp.edit().putStringSet("blk_apps", a).putStringSet("blk_hosts", hs).apply();
                    PacketStats.loadBlocks(this);
                    buildSettings();
                })
                .setNegativeButton(t("İptal", "Cancel", "Отмена"), null)
                .show();
    }

    // ---------- uygulama seçimi / log ----------
    void pickApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent li = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<android.content.pm.ResolveInfo> ri = pm.queryIntentActivities(li, 0);
            final List<String[]> items = new ArrayList<>();
            HashSet<String> seen = new HashSet<>();
            for (android.content.pm.ResolveInfo r : ri) {
                String p = r.activityInfo.packageName;
                if (p.equals(getPackageName()) || !seen.add(p)) continue;
                items.add(new String[]{r.loadLabel(pm).toString(), p});
            }
            Collections.sort(items, (a, b) -> a[0].compareToIgnoreCase(b[0]));
            runOnUiThread(() -> showPicker(items));
        }).start();
    }

    void showPicker(final List<String[]> items) {
        Set<String> cur = sp.getStringSet("apps", null);
        final String[] names = new String[items.size()];
        final boolean[] chk = new boolean[items.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = items.get(i)[0];
            chk[i] = cur != null && cur.contains(items.get(i)[1]);
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(t("İzlenecek uygulamalar", "Apps to monitor", "Отслеживаемые приложения"))
                .setMultiChoiceItems(names, chk, (dlg, which, on) -> chk[which] = on)
                .setPositiveButton(t("Kaydet", "Save", "Сохранить"), (dlg, w) -> {
                    HashSet<String> res = new HashSet<>();
                    for (int i = 0; i < chk.length; i++) if (chk[i]) res.add(items.get(i)[1]);
                    sp.edit().putStringSet("apps", res).apply();
                    buildSettings();
                })
                .setNeutralButton(t("Hepsini izle", "Monitor all", "Следить за всеми"), (dlg, w) -> { sp.edit().remove("apps").apply(); buildSettings(); })
                .setNegativeButton(t("İptal", "Cancel", "Отмена"), null)
                .show();
    }

    void shareLog() {
        try {
            File f = new File(getFilesDir(), "vaxodpi.log");
            String s = "";
            if (f.exists()) {
                long len = f.length();
                byte[] bb = new byte[(int) Math.min(len, 60000)];
                try (FileInputStream in = new FileInputStream(f)) {
                    in.skip(Math.max(0, len - bb.length));
                    int n = in.read(bb);
                    s = new String(bb, 0, Math.max(n, 0), "UTF-8");
                }
            }
            if (s.length() == 0) {
                toast(t("Log boş. Log kaydını aç ve bağlan.", "Log is empty. Turn on logging and connect.", "Лог пуст. Включите журнал и подключитесь."));
                return;
            }
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, "VaxoScope log\n" + s);
            startActivity(Intent.createChooser(i, t("Logu paylaş", "Share log", "Поделиться логом")));
        } catch (Throwable e) {
            toast(t("Log okunamadı", "Could not read log", "Не удалось прочитать лог"));
        }
    }

    // ---------- görünüm parçaları ----------
    View row(Drawable ic, String t1, String t2, String right, float frac) {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(14), dp(4), dp(14), dp(4));
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(rr(CARD, 18, rowAccent != 0 ? rowAccent : LINE));
        rowAccent = 0;
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
        String st = imp ? t("Dosyadan", "From file", "Из файла")
                : c.blocked ? t("Engellendi", "Blocked", "Заблокировано")
                : now - c.last < 3000 ? t("Aktif", "Active", "Активно") : t("Boşta", "Idle", "Простой");
        return t("UYGULAMA", "APP", "ПРИЛОЖЕНИЕ") + "\n" + (imp ? "-" : label(c.uid) + "\n" + pkg(c.uid))
                + "\n\n" + t("SİTE ADI (SNI/DNS)", "HOST (SNI/DNS)", "ХОСТ (SNI/DNS)") + "\n"
                + (c.name != null ? c.name : c.guess != null ? "≈ " + c.guess : "-")
                + (Trackers.is(c) ? "\n⚑ " + t("Reklam/izleyici listesinde", "On the ad/tracker list", "В списке рекламы/трекеров") : "")
                + (ownerText(c).length() > 0 ? "\n\n" + t("SAHİBİ (IP)", "OWNER (IP)", "ВЛАДЕЛЕЦ (IP)") + "\n" + ownerText(c) : "")
                + "\n\n" + t("PROTOKOL", "PROTOCOL", "ПРОТОКОЛ") + "\n" + proto(c.proto) + " · " + ver
                + "\n\n" + t("ADRESLER", "ADDRESSES", "АДРЕСА")
                + "\n" + t("Yerel ", "Local  ", "Локал.") + " " + c.laddr + ":" + c.lport
                + "\n" + t("Uzak  ", "Remote ", "Удал. ") + " " + c.raddr + ":" + c.rport
                + "\n\n" + t("TRAFİK", "TRAFFIC", "ТРАФИК")
                + "\n" + t("Giden ", "Out   ", "Исх.  ") + " " + c.pOut + " " + t("paket", "pkts", "пак.") + " · " + fmt(c.bOut)
                + "\n" + t("Gelen ", "In    ", "Вх.   ") + " " + c.pIn + " " + t("paket", "pkts", "пак.") + " · " + fmt(c.bIn)
                + "\n" + t("Toplam", "Total ", "Всего ") + " " + fmt(c.bOut + c.bIn)
                + "\n" + t("Hız   ", "Speed ", "Скор. ") + " " + fmt((c.bOut + c.bIn) / sec) + "/" + t("sn", "s", "с")
                + "\n\n" + t("ZAMAN", "TIME", "ВРЕМЯ")
                + "\n" + t("İlk   ", "First ", "Первый") + " " + clock(c.first)
                + "\n" + t("Son   ", "Last  ", "Послед") + " " + clock(c.last)
                + "\n" + t("Süre  ", "Span  ", "Длит. ") + " " + dur(c.last - c.first)
                + "\n" + t("Durum ", "State ", "Статус") + " " + st;
    }

    String ownerText(PacketStats.Conn c) {
        String[] o = IpInfo.lookup(c.raddr);
        if (o == null) return "";
        if (o[0].equals("--")) return t("Yerel ağ", "Local network", "Локальная сеть");
        return IpInfo.flag(o[0]) + " " + o[2] + " · AS" + o[1] + " · " + o[0];
    }

    String ownerShort(PacketStats.Conn c) {
        String[] o = IpInfo.lookup(c.raddr);
        if (o == null) return "";
        if (o[0].equals("--")) return " · " + t("yerel", "local", "локальный");
        String n = o[2].length() > 22 ? o[2].substring(0, 22) : o[2];
        return " · " + IpInfo.flag(o[0]) + " " + n;
    }

    static long u32(byte[] b, int i) {
        return ((b[i] & 255L) << 24) | ((b[i + 1] & 255L) << 16) | ((b[i + 2] & 255L) << 8) | (b[i + 3] & 255L);
    }

    String flowText(PacketStats.Conn c) {
        List<PacketStats.Pkt> l = c.snapshot();
        StringBuilder s = new StringBuilder();
        s.append(c.laddr).append(":").append(c.lport).append("  ⇄  ").append(c.raddr).append(":").append(c.rport)
         .append("\n").append(t("Son ", "Last ", "Последние ")).append(l.size())
         .append(t(" paket · seq/ack göreli", " packets · relative seq/ack", " пакетов · отн. seq/ack"))
         .append("\n→ ").append(t("giden", "out", "исх.")).append("   ← ").append(t("gelen", "in", "вх.")).append("\n\n");
        if (l.isEmpty()) return s.append(t("Tutulan paket yok.", "No stored packets.", "Нет сохранённых пакетов.")).toString();
        long t0 = l.get(0).t;
        long[] base = {-1, -1};
        String[] fn = {"FIN", "SYN", "RST", "PSH", "ACK", "URG"};
        for (PacketStats.Pkt p : l) {
            byte[] b = p.data;
            try {
                int v = (b[0] >> 4) & 15, ip, pr;
                if (v == 4) { ip = (b[0] & 15) * 4; pr = b[9] & 255; }
                else if (v == 6) { ip = 40; pr = b[6] & 255; }
                else continue;
                s.append(String.format(Locale.US, "%+7.3f ", (p.t - t0) / 1000.0)).append(p.out ? "→ " : "← ");
                if (pr == 6 && b.length >= ip + 20) {
                    int off = ((b[ip + 12] >> 4) & 15) * 4, fl = b[ip + 13] & 255;
                    long seq = u32(b, ip + 4), ack = u32(b, ip + 8);
                    int di = p.out ? 0 : 1;
                    if (base[di] < 0) base[di] = seq;
                    long rs = (seq - base[di]) & 0xFFFFFFFFL;
                    long ra = base[1 - di] >= 0 ? ((ack - base[1 - di]) & 0xFFFFFFFFL) : ack;
                    StringBuilder fs = new StringBuilder();
                    for (int i = 0; i < 6; i++) if ((fl & (1 << i)) != 0) { if (fs.length() > 0) fs.append(','); fs.append(fn[i]); }
                    int pay = Math.max(0, p.orig - ip - off);
                    s.append(fs.length() > 0 ? fs : "-").append("  seq=").append(rs);
                    if ((fl & 0x10) != 0) s.append(" ack=").append(ra);
                    s.append("  len=").append(pay).append('\n');
                    String br = PacketStats.decode(p)[0];
                    int k = br.indexOf(" B ");
                    if (k >= 0 && k + 3 < br.length()) s.append("         ").append(br.substring(k + 3)).append('\n');
                } else {
                    s.append(PacketStats.decode(p)[0]).append('\n');
                }
            } catch (Throwable ignored) { }
        }
        return s.toString();
    }

    String dayLabel(String day) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        String today = f.format(new Date());
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -1);
        String y = f.format(c.getTime());
        if (day.equals(today)) return t("Bugün", "Today", "Сегодня");
        if (day.equals(y)) return t("Dün", "Yesterday", "Вчера");
        return day.substring(6) + "." + day.substring(4, 6) + "." + day.substring(0, 4);
    }

    void buildReport() {
        PacketStats.saveUsage();
        repRows.clear();
        TreeMap<String, List<Object[]>> days = new TreeMap<>(Collections.reverseOrder());
        for (Map.Entry<String, ?> e : getSharedPreferences("vaxo_usage", 0).getAll().entrySet()) {
            try {
                String[] k = e.getKey().split("\\|", 2);
                String[] v = String.valueOf(e.getValue()).split(",");
                List<Object[]> l = days.get(k[0]);
                if (l == null) { l = new ArrayList<>(); days.put(k[0], l); }
                l.add(new Object[]{k[1], Long.parseLong(v[0]), Long.parseLong(v[1]), Long.parseLong(v[2])});
            } catch (Throwable ignored) { }
        }
        int n = 0;
        for (Map.Entry<String, List<Object[]>> e : days.entrySet()) {
            if (n++ >= 7) break;
            List<Object[]> l = e.getValue();
            Collections.sort(l, (a, b) -> Long.compare((Long) b[1] + (Long) b[2], (Long) a[1] + (Long) a[2]));
            long up = 0, dn = 0, cn = 0;
            for (Object[] o : l) { up += (Long) o[1]; dn += (Long) o[2]; cn += (Long) o[3]; }
            repRows.add(new Object[]{"H", "▸ " + dayLabel(e.getKey()), up, dn, cn});
            for (int i = 0; i < l.size() && i < 5; i++) {
                Object[] o = l.get(i);
                repRows.add(new Object[]{"A", o[0], o[1], o[2], o[3]});
            }
        }
    }

    // ---------- üst düğmeler ----------
    void buildFilters() {
        srcRow.removeAllViews();
        filtRow.removeAllViews();
        put(srcRow, chip(t("Canlı", "Live", "Живой"), !imp, 9, v -> { imp = false; buildFilters(); go(LIST); }));
        put(srcRow, chip(t("Dosya", "File", "Файл"), imp, 9, v -> { imp = true; selUid = ALL; buildFilters(); go(APP); }));
        put(srcRow, chip("DNS", false, 9, v -> go(DNSL)));
        put(srcRow, chip(t("Rapor", "Report", "Отчёт"), false, 9, v -> go(REP)));
        String[] fn = {t("Tümü", "All", "Все"), "TCP", "UDP", "DNS", "TLS"};
        for (int i = 0; i < fn.length; i++) {
            final int k = i;
            put(filtRow, chip(fn[i], filter == i, 9, v -> { filter = k; buildFilters(); refresh(); }));
        }
    }

    void buildAppRow() {
        appRow.removeAllViews();
        if (lvl != APP || imp || selUid == ALL) return;
        final String pk = pkg(selUid);
        boolean bl = PacketStats.BLK_UID.contains(selUid);
        put(appRow, chip("PCAP", false, 9, v -> exportApp()));
        put(appRow, chip(bl ? t("Engeli kaldır", "Unblock", "Разблокировать") : "⛔ " + t("Engelle", "Block", "Блок"), bl, 9, v -> toggleBlockApp(pk)));
    }

    void buildDetActions() {
        detActions.removeAllViews();
        detActions2.removeAllViews();
        if (selConn == null) return;
        if (lvl == CONN) {
            int n = selConn.snapshot().size();
            put(detActions, chip(t("Paketler", "Packets", "Пакеты") + " (" + n + ")", true, 13, v -> go(PKTS)));
            if (selConn.proto == 6) put(detActions, chip(t("Konuşma", "Conversation", "Диалог"), false, 13, v -> go(FLOW)));
            if (!imp) {
                put(detActions2, chip(t("PCAP aktar", "Export PCAP", "Экспорт PCAP"), false, 13,
                        v -> exportKeys(PacketStats.keysOf(Collections.singletonList(selConn)), "conn")));
                String host = selConn.name != null ? selConn.name : selConn.guess;
                if (host != null) {
                    final String hh = host;
                    boolean bl = hostIsBlocked(hh);
                    put(detActions2, chip(bl ? t("Engeli kaldır", "Unblock", "Разблокировать")
                            : "⛔ " + t("Siteyi engelle", "Block host", "Блок хоста"), bl, 13, v -> toggleBlockHost(hh)));
                }
            }
        } else if (lvl == PKT) {
            put(detActions, chip(t("Ayrıntı", "Details", "Детали"), pktMode == 0, 13, v -> { pktMode = 0; buildDetActions(); refresh(); }));
            put(detActions, chip("Hex", pktMode == 1, 13, v -> { pktMode = 1; buildDetActions(); refresh(); }));
        }
    }

    void go(int l) {
        lvl = l;
        tab = 0;
        buildFilters();
        buildAppRow();
        buildDetActions();
        refresh();
    }

    void setLang(int k) {
        lang = k;
        PacketStats.lang = k;
        sp.edit().putInt("lang", k).apply();
        applyLang();
    }

    void applyLang() {
        btnList.setText(t("Liste", "List", "Список"));
        btnSet.setText(t("Ayarlar", "Settings", "Настройки"));
        search.setHint(t("Ara: tiktok, tcp port 443, app chrome, >1mb", "Search: tiktok, tcp port 443, app chrome, >1mb", "Поиск: tiktok, tcp port 443, app chrome, >1mb"));
        buildFilters();
        buildAppRow();
        buildDetActions();
        if (tab == 1) buildSettings();
        refresh();
    }

    // ---------- ayarlar ----------
    LinearLayout toggle(String title, String desc, final String key, boolean def, final Runnable after) {
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
        TextView ch = chip(on ? t("Açık", "On", "Вкл") : t("Kapalı", "Off", "Выкл"), on, 11, v -> {
            sp.edit().putBoolean(key, !on).apply();
            if (after != null) after.run();
            buildSettings();
        });
        ch.setPadding(dp(16), dp(11), dp(16), dp(11));
        c.addView(ch);
        return c;
    }

    LinearLayout info(String title, String body) {
        LinearLayout c = card();
        c.addView(bold(title, 16, Color.WHITE));
        TextView b = tv(body, 13, DIM);
        b.setPadding(0, dp(6), 0, 0);
        b.setLineSpacing(0, 1.1f);
        c.addView(b);
        return c;
    }

    void buildSettings() {
        setBox.removeAllViews();

        LinearLayout cl = card();
        cl.addView(bold(t("Dil", "Language", "Язык"), 16, Color.WHITE));
        LinearLayout lr = new LinearLayout(this);
        lr.setPadding(0, dp(12), 0, 0);
        String[] ln = {"Türkçe", "English", "Русский"};
        for (int k = 0; k < 3; k++) {
            final int kk = k;
            put(lr, chip(ln[k], lang == k, 12, v -> setLang(kk)));
        }
        cl.addView(lr);
        setBox.addView(cl, lp(12));

        setBox.addView(toggle(t("PCAP kaydı", "PCAP recording", "Запись PCAP"),
                t("Ham paketler capture.pcap dosyasına yazılır (en çok 64 MB).", "Raw packets are written to capture.pcap (max 64 MB).", "Сырые пакеты пишутся в capture.pcap (до 64 МБ).")
                        + "\n" + PacketStats.pcapInfo(),
                "cap_on", true, () -> PacketStats.pcapOn = sp.getBoolean("cap_on", true)), lp(12));

        LinearLayout c2 = card();
        c2.addView(bold(t("PCAP dosyaları", "PCAP files", "Файлы PCAP"), 16, Color.WHITE));
        TextView t2 = tv(t("Dışa aktar: Wireshark'ta açılır. İçe aktar: .pcap ve .pcapng dosyalarını çevrimdışı incele.",
                "Export opens in Wireshark. Import lets you inspect .pcap and .pcapng files offline.",
                "Экспорт открывается в Wireshark. Импорт — просмотр файлов .pcap и .pcapng офлайн."), 13, DIM);
        t2.setPadding(0, dp(3), 0, dp(12));
        c2.addView(t2);
        c2.addView(btn(t("PCAP dışa aktar", "Export PCAP", "Экспорт PCAP"), true, v -> exportPcap()));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(0, dp(10), 0, 0);
        c2.addView(btn(t("PCAP içe aktar", "Import PCAP", "Импорт PCAP"), false, v -> importPcap()), bp);
        setBox.addView(c2, lp(12));

        int nb = sp.getStringSet("blk_apps", new HashSet<String>()).size() + sp.getStringSet("blk_hosts", new HashSet<String>()).size();
        LinearLayout cf = info(t("Güvenlik duvarı", "Firewall", "Брандмауэр"),
                t("Listeden bir uygulamayı ya da bağlantı ayrıntısından bir siteyi engelleyebilirsin. Engellenen trafik internete çıkmaz. Yalnızca VPN açıkken çalışır.",
                  "Block an app from the list or a host from connection details. Blocked traffic never leaves the phone. Works only while the VPN is on.",
                  "Блокируйте приложение из списка или хост из деталей соединения. Заблокированный трафик не выходит в сеть. Работает только при включённом VPN."));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.setMargins(0, dp(12), 0, 0);
        cf.addView(btn(t("Engelli liste", "Blocked list", "Список блокировок") + " (" + nb + ")", false, v -> manageBlocks()), fp);
        setBox.addView(cf, lp(12));

        setBox.addView(toggle(t("İzleyici uyarısı", "Tracker alert", "Уведомление о трекерах"),
                t("Reklam/izleyici alan adına ilk temasta bildirim gönderir.", "Notifies on first contact with an ad/tracker domain.", "Уведомляет при первом обращении к рекламному/трекерному домену."),
                "alert_trk", false, null), lp(12));

        java.util.Set<String> selA = sp.getStringSet("apps", null);
        int na = selA == null ? 0 : selA.size();
        LinearLayout ca = info(t("İzlenecek uygulamalar", "Apps to monitor", "Отслеживаемые приложения"),
                (na == 0 ? t("Şu an: tüm uygulamalar.", "Now: all apps.", "Сейчас: все приложения.")
                         : t("Şu an: ", "Now: ", "Сейчас: ") + na + t(" uygulama seçili.", " apps selected.", " приложений выбрано."))
                        + "\n" + t("Değişiklik bir sonraki bağlantıda geçerli olur.", "Changes apply on the next connection.", "Изменения вступят в силу при следующем подключении."));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.setMargins(0, dp(12), 0, 0);
        ca.addView(btn(t("Uygulama seç", "Choose apps", "Выбрать приложения"), false, v -> pickApps()), ap);
        setBox.addView(ca, lp(12));

        setBox.addView(toggle(t("Yalnızca aktif bağlantılar", "Active connections only", "Только активные соединения"),
                t("Son 5 saniyede trafiği olanları göster.", "Show only those with traffic in the last 5 seconds.", "Показывать только с трафиком за последние 5 секунд."),
                "only_active", false, null), lp(12));
        setBox.addView(toggle(t("Log kaydı", "Logging", "Журнал"),
                t("Hata ayıklama olaylarını dosyaya yazar.", "Writes debug events to a file.", "Пишет отладочные события в файл."),
                "log_on", false, null), lp(12));
        setBox.addView(btn(t("Logu paylaş", "Share log", "Поделиться логом"), false, v -> shareLog()), lp(8));

        LinearLayout c3 = card();
        c3.addView(bold(t("DNS sunucusu", "DNS server", "DNS-сервер"), 16, Color.WHITE));
        TextView t3 = tv(t("Değişiklik bir sonraki bağlantıda geçerli olur.", "Changes apply on the next connection.", "Изменения вступят в силу при следующем подключении."), 13, DIM);
        t3.setPadding(0, dp(3), 0, dp(12));
        c3.addView(t3);
        String[] dn = {"Cloudflare", "Google", "Quad9", "AdGuard"};
        int cur = sp.getInt("dns", 0);
        for (int r = 0; r < 2; r++) {
            LinearLayout rw = new LinearLayout(this);
            rw.setPadding(0, r == 0 ? 0 : dp(8), 0, 0);
            for (int k = r * 2; k < r * 2 + 2; k++) {
                final int kk = k;
                put(rw, chip(dn[k], cur == k, 12, v -> { sp.edit().putInt("dns", kk).apply(); buildSettings(); }));
            }
            c3.addView(rw);
        }
        setBox.addView(c3, lp(12));

        LinearLayout cb = info(t("Yedekleme", "Backup", "Резервная копия"),
                t("Ayarlar, engeller ve günlük raporlar tek dosyaya kaydedilir.", "Settings, blocks and daily reports are saved to a single file.", "Настройки, блокировки и отчёты сохраняются в один файл."));
        LinearLayout br = new LinearLayout(this);
        br.setPadding(0, dp(12), 0, 0);
        put(br, chip(t("Yedekle", "Back up", "Сохранить"), true, 13, v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE, "vaxoscope-backup-" + new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()) + ".json");
            startActivityForResult(i, 5);
        }));
        put(br, chip(t("Geri yükle", "Restore", "Восстановить"), false, 13, v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, 6);
        }));
        cb.addView(br);
        setBox.addView(cb, lp(12));

        setBox.addView(info(t("IP veritabanı", "IP database", "База IP"),
                IpInfo.ready
                    ? (IpInfo.count4 + IpInfo.count6) + t(" aralık yüklü. Bağlantı ayrıntısında sahibi (ülke, şirket) görünür. Kaynak: iptoasn.com (kamu malı).",
                        " ranges loaded. Connection details show the owner (country, company). Source: iptoasn.com (public domain).",
                        " диапазонов загружено. В деталях соединения виден владелец (страна, компания). Источник: iptoasn.com (общественное достояние).")
                    : t("Veritabanı yükleniyor ya da pakette yok.", "Database is loading or missing from the package.", "База загружается или отсутствует в пакете.")), lp(12));

        setBox.addView(info(t("Renk anlamları", "Color legend", "Цвета"),
                t("Kırmızı: RST / bağlantı hatası\nYeşil: TLS (şifreli web)\nMavi: DNS\nTuruncu: engellendi\n⚑ reklam/izleyici",
                  "Red: RST / connection error\nGreen: TLS (encrypted web)\nBlue: DNS\nOrange: blocked\n⚑ ad/tracker",
                  "Красный: RST / ошибка соединения\nЗелёный: TLS (шифрованный веб)\nСиний: DNS\nОранжевый: заблокировано\n⚑ реклама/трекер")), lp(12));

        setBox.addView(info(t("Hızlı ayar kutucuğu", "Quick settings tile", "Плитка быстрых настроек"),
                t("Bildirim panelini aç, kalem simgesine bas ve 'VaxoScope' kutucuğunu ekle. Oradan tek dokunuşla aç/durdur yapabilirsin.",
                  "Open the notification panel, tap the pencil icon and add the 'VaxoScope' tile. You can then start/stop with one tap.",
                  "Откройте шторку, нажмите значок карандаша и добавьте плитку «VaxoScope». Затем запуск/остановка одним касанием.")), lp(12));

        setBox.addView(info(t("Gizlilik", "Privacy", "Конфиденциальность"),
                t("VaxoScope yalnızca bu telefonun kendi trafiğini izler. Veri telefondan çıkmaz, internete hiçbir şey gönderilmez. HTTPS içeriği okunmaz; yalnızca site adı, adres, port ve bayt sayıları görünür.\n\nPrivate DNS açıksa DNS sorguları şifreli gider ve bazı adlar eksik kalır.",
                  "VaxoScope only watches this phone's own traffic. Nothing leaves the phone and nothing is sent to the internet. HTTPS content is not read; only host names, addresses, ports and byte counts are visible.\n\nIf Private DNS is on, DNS queries are encrypted and some names will be missing.",
                  "VaxoScope следит только за трафиком этого телефона. Данные не покидают телефон, ничего не отправляется в интернет. Содержимое HTTPS не читается; видны только имена хостов, адреса, порты и объём.\n\nЕсли включён Private DNS, DNS-запросы шифруются и часть имён будет недоступна.")), lp(12));

        setBox.addView(btn(t("Listeyi ve kaydı temizle", "Clear list and recording", "Очистить список и запись"), false, v -> {
            PacketStats.clearAll();
            PacketStats.IMPORT.clear();
            toast(t("Temizlendi", "Cleared", "Очищено"));
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
        btnGo.setText(run ? t("Durdur", "Stop", "Стоп") : pend ? t("Bağlanıyor…", "Connecting…", "Подключение…") : t("Aç", "Start", "Старт"));
        btnGo.setTextColor(run ? Color.WHITE : Color.BLACK);
        btnGo.setBackground(run ? rr(Color.BLACK, 24, Color.WHITE) : rr(Color.WHITE, 24, 0));
        status.setText(run ? t("● Açık", "● On", "● Вкл") : pend ? t("● Bağlanıyor", "● Connecting", "● Подключение") : t("○ Kapalı", "○ Off", "○ Выкл"));
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

        boolean listy = tab == 0 && (lvl == LIST || lvl == APP || lvl == PKTS || lvl == DNSL || lvl == REP);
        boolean det = tab == 0 && (lvl == CONN || lvl == PKT || lvl == FLOW);
        listPage.setVisibility(listy ? View.VISIBLE : View.GONE);
        detSv.setVisibility(det ? View.VISIBLE : View.GONE);
        setSv.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        topBox.setVisibility(lvl <= APP ? View.VISIBLE : View.GONE);
        appRow.setVisibility(lvl == APP && !imp && selUid != ALL ? View.VISIBLE : View.GONE);
        graph.setVisibility(imp ? View.GONE : View.VISIBLE);
        gtxt.setVisibility(imp ? View.GONE : View.VISIBLE);
        if (tab == 1) { sub.setText(t("Ayarlar", "Settings", "Настройки")); return; }

        if (lvl == REP) {
            buildReport();
            sub.setText("‹  " + t("Kullanım raporu · son günler", "Usage report · recent days", "Отчёт об использовании · последние дни"));
            empty.setText(t("Henüz kayıtlı kullanım yok.\nVPN açıkken trafik oluştur.", "No recorded usage yet.\nGenerate some traffic with the VPN on.", "Пока нет данных.\nСоздайте трафик при включённом VPN."));
            ad.notifyDataSetChanged();
            return;
        }
        if (lvl == DNSL) {
            dnsRows.clear();
            List<PacketStats.Dns> dl = PacketStats.dnsSnapshot();
            Collections.reverse(dl);
            for (PacketStats.Dns e : dl) {
                if (query.length() > 0 && !e.name.toLowerCase(Locale.ROOT).contains(query)) continue;
                dnsRows.add(e);
            }
            sub.setText("‹  " + t("DNS geçmişi · ", "DNS history · ", "История DNS · ") + dnsRows.size() + t(" sorgu", " queries", " запросов"));
            empty.setText(t("Henüz DNS sorgusu yok.\nPrivate DNS açıksa sorgular şifreli gider.", "No DNS queries yet.\nWith Private DNS on, queries are encrypted.", "Пока нет DNS-запросов.\nПри включённом Private DNS запросы шифруются."));
            ad.notifyDataSetChanged();
            return;
        }

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
            if (a == null) { a = new long[5]; m.put(u, a); }
            a[0]++; a[1] += c.bOut; a[2] += c.bIn;
            if (Trackers.is(c)) a[4]++;
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
        if (conns.size() > 300) conns.subList(300, conns.size()).clear();
        maxBytes = 1;
        if (lvl == LIST && !apps.isEmpty()) { long[] a = (long[]) apps.get(0)[1]; maxBytes = Math.max(1, a[1] + a[2]); }
        for (PacketStats.Conn c : conns) maxBytes = Math.max(maxBytes, c.bOut + c.bIn);

        String back = lvl > minLvl() ? "‹  " : "";
        if (lvl == LIST) {
            sub.setText(t("Uygulamalar · ", "Apps · ", "Приложения · ") + apps.size() + "   ↑" + fmt(up) + " ↓" + fmt(dn));
            empty.setText(t("Henüz trafik yok.\nAşağıdan Aç'a bas.", "No traffic yet.\nTap Start below.", "Трафика пока нет.\nНажмите «Старт» внизу."));
        } else if (lvl == APP) {
            sub.setText(back + (imp ? t("Dosya", "File", "Файл") : label(selUid)) + " · " + conns.size() + t(" bağlantı", " connections", " соединений"));
            empty.setText(imp ? t("Dosya boş ya da filtreye uyan yok.\nAyarlar'dan PCAP içe aktar.", "File is empty or nothing matches.\nImport a PCAP from Settings.", "Файл пуст или нет совпадений.\nИмпортируйте PCAP в настройках.")
                    : t("Bu filtreye uyan bağlantı yok.", "No connections match this filter.", "Нет соединений под этот фильтр."));
        } else if (selConn != null && lvl == CONN) {
            sub.setText(back + dest(selConn));
            detail.setTextSize(13);
            detail.setText(detailText(selConn));
        } else if (selConn != null && lvl == FLOW) {
            sub.setText(back + t("Konuşma · ", "Conversation · ", "Диалог · ") + dest(selConn));
            detail.setTextSize(11);
            detail.setText(flowText(selConn));
        } else if (selConn != null && lvl == PKTS) {
            pkts.clear();
            pkts.addAll(selConn.snapshot());
            sub.setText(back + t("Paketler · ", "Packets · ", "Пакеты · ") + dest(selConn));
            empty.setText(t("Bu bağlantı için tutulan paket yok.", "No stored packets for this connection.", "Для этого соединения нет сохранённых пакетов."));
        } else if (selPkt != null && lvl == PKT) {
            sub.setText(back + t("Paket #", "Packet #", "Пакет #") + selNum);
            String hdr = "#" + selNum + "  " + (selPkt.out ? t("↑ giden", "↑ outgoing", "↑ исходящий") : t("↓ gelen", "↓ incoming", "↓ входящий"))
                    + "  " + TF.format(new Date(selPkt.t)) + "\n" + selPkt.orig + " B ("
                    + t("yakalanan ", "captured ", "захвачено ") + selPkt.data.length + " B)\n\n";
            if (pktMode == 0) { detail.setTextSize(12); detail.setText(hdr + PacketStats.decode(selPkt)[1]); }
            else { detail.setTextSize(11); detail.setText(hdr + PacketStats.hexdump(selPkt.data)); }
        }
        ad.notifyDataSetChanged();
    }

    final Runnable tick = new Runnable() {
        @Override public void run() {
            try { refresh(); } catch (Throwable ignored) { }
            h.postDelayed(this, 1500);
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
                crash = "CRASH\n" + new String(bb, 0, Math.max(n, 0));
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
        IpInfo.init(this);
        lang = PacketStats.lang;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(0, dp(34), 0, 0);

        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        brandRow.setPadding(dp(20), dp(8), dp(20), 0);
        TextView brand = bold("VaxoScope", 28, Color.WHITE);
        status = tv("○", 13, OFF);
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
        search = new EditText(this);
        search.setHintTextColor(OFF);
        search.setTextColor(Color.WHITE);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setBackground(rr(CARD, 22, LINE));
        search.setPadding(dp(18), dp(12), dp(18), dp(12));
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence x, int a, int bb, int c) { }
            @Override public void onTextChanged(CharSequence x, int a, int bb, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                query = e.toString().trim().toLowerCase(Locale.ROOT);
                refresh();
            }
        });
        topBox.addView(search, lp(10));
        srcRow = new LinearLayout(this);
        srcRow.setPadding(dp(11), 0, dp(11), 0);
        topBox.addView(srcRow, new LinearLayout.LayoutParams(-1, -2));
        ((LinearLayout.LayoutParams) srcRow.getLayoutParams()).topMargin = dp(10);
        filtRow = new LinearLayout(this);
        filtRow.setPadding(dp(11), 0, dp(11), 0);
        topBox.addView(filtRow, new LinearLayout.LayoutParams(-1, -2));
        ((LinearLayout.LayoutParams) filtRow.getLayoutParams()).topMargin = dp(8);
        appRow = new LinearLayout(this);
        appRow.setPadding(dp(11), 0, dp(11), dp(6));
        topBox.addView(appRow, new LinearLayout.LayoutParams(-1, -2));
        ((LinearLayout.LayoutParams) appRow.getLayoutParams()).topMargin = dp(8);
        listPage.addView(topBox);

        FrameLayout lf = new FrameLayout(this);
        lv = new ListView(this);
        lv.setDividerHeight(0);
        lv.setDivider(null);
        lv.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        lv.setVerticalScrollBarEnabled(false);
        ad = new BaseAdapter() {
            @Override public int getCount() {
                return lvl == LIST ? apps.size() : lvl == APP ? conns.size() : lvl == PKTS ? pkts.size()
                        : lvl == DNSL ? dnsRows.size() : lvl == REP ? repRows.size() : 0;
            }
            @Override public Object getItem(int i) { return null; }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup p) {
                if (lvl == REP) {
                    Object[] o = repRows.get(i);
                    String dt = o[4] + t(" bağlantı", " connections", " соед.") + " · ↑" + fmt((Long) o[2]) + " ↓" + fmt((Long) o[3]);
                    String tot = fmt((Long) o[2] + (Long) o[3]);
                    if (o[0].equals("H")) return row(null, "" + o[1], dt, tot, -1f);
                    String pk = "" + o[1];
                    return row(pkgIcon(pk), pkgLabel(pk), dt, tot, -1f);
                }
                if (lvl == DNSL) {
                    PacketStats.Dns e = dnsRows.get(i);
                    String ap = e.conn != null && e.conn.uid >= 0 ? label(e.conn.uid) : "?";
                    String ty = e.qtype == 1 ? "A" : e.qtype == 28 ? "AAAA" : e.qtype == 65 ? "HTTPS" : e.qtype == 5 ? "CNAME" : "type " + e.qtype;
                    String rt = e.rcode < 0 ? "…" : e.rcode == 0 ? (e.ans.length() == 0 ? t("boş", "empty", "пусто") : "✓") : t("hata ", "err ", "ошибка ") + e.rcode;
                    if (e.rcode > 0) rowAccent = RED;
                    return row(null, e.name, TF.format(new Date(e.t)) + " · " + ap + " · " + ty + (e.ans.length() > 0 ? " → " + e.ans : ""), rt, -1f);
                }
                if (lvl == LIST) {
                    Object[] o = apps.get(i);
                    int u = (Integer) o[0];
                    long[] a = (long[]) o[1];
                    String dot = System.currentTimeMillis() - a[3] < 3000 ? "● " : "";
                    boolean bl = PacketStats.BLK_UID.contains(u);
                    if (bl) rowAccent = ORG;
                    return row(icon(u), (bl ? "⛔ " : "") + dot + label(u),
                            a[0] + t(" bağlantı", " connections", " соед.") + (a[4] > 0 ? " · ⚑" + a[4] : "") + " · ↑" + fmt(a[1]) + " ↓" + fmt(a[2]),
                            fmt(a[1] + a[2]), (float) (a[1] + a[2]) / maxBytes);
                }
                if (lvl == APP) {
                    PacketStats.Conn c = conns.get(i);
                    rowAccent = accentOf(c);
                    return row(null, (c.blocked ? "⛔ " : "") + (Trackers.is(c) ? "⚑ " : "") + dest(c),
                            proto(c.proto) + " · " + c.raddr + ":" + c.rport + ownerShort(c) + " · ↑" + fmt(c.bOut) + " ↓" + fmt(c.bIn),
                            fmt(c.bOut + c.bIn), (float) (c.bOut + c.bIn) / maxBytes);
                }
                PacketStats.Pkt k = pkts.get(i);
                String[] dc = PacketStats.decode(k);
                rowAccent = pktAccent(dc[0]);
                long num = selConn.pOut + selConn.pIn - pkts.size() + i + 1;
                return row(null, "#" + num + "  " + (k.out ? "↑ " : "↓ ") + dc[0], TF.format(new Date(k.t)), k.orig + " B", -1f);
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

        detSv = new ScrollView(this);
        detSv.setVerticalScrollBarEnabled(false);
        LinearLayout dbox = new LinearLayout(this);
        dbox.setOrientation(LinearLayout.VERTICAL);
        detActions = new LinearLayout(this);
        detActions.setPadding(dp(11), 0, dp(11), 0);
        dbox.addView(detActions, new LinearLayout.LayoutParams(-1, -2));
        detActions2 = new LinearLayout(this);
        detActions2.setPadding(dp(11), dp(8), dp(11), 0);
        dbox.addView(detActions2, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout dcard = card();
        detail = tv("", 13, Color.WHITE);
        detail.setTypeface(Typeface.MONOSPACE);
        detail.setLineSpacing(0, 1.15f);
        dcard.addView(detail);
        dbox.addView(dcard, lp(10));
        dbox.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(20)));
        detSv.addView(dbox);
        pages.addView(detSv);

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
        btnList = bold("", 15, Color.WHITE);
        btnSet = bold("", 15, OFF);
        btnGo = bold("", 16, Color.BLACK);
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
        applyLang();
    }

    @Override public void onBackPressed() {
        if (tab == 1) { tab = 0; refresh(); }
        else if (lvl == DNSL || lvl == REP) go(minLvl());
        else if (lvl == FLOW) go(CONN);
        else if (lvl > minLvl()) go(lvl - 1);
        else super.onBackPressed();
    }

    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }
}
