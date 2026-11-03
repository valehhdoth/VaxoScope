package xyz.valeh.vaxodpi;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import xyz.valeh.vaxodpi.scan.Device;
import xyz.valeh.vaxodpi.scan.DeviceInfoResolver;
import xyz.valeh.vaxodpi.scan.NetInfo;
import xyz.valeh.vaxodpi.scan.NetworkInfoManager;
import xyz.valeh.vaxodpi.scan.NetworkScanner;
import xyz.valeh.vaxodpi.scan.ScanRepository;

public class ScannerActivity extends Activity {
    static final int CARD = 0xFF0C0C0C, LINE = 0xFF222222, DIM = 0xFF8A8A8A, OFF = 0xFF5E5E5E;

    final ScanRepository repo = ScanRepository.get();
    final Handler h = new Handler(Looper.getMainLooper());
    final List<Device> rows = new ArrayList<Device>();
    NetInfo net = new NetInfo();
    Device sel;
    int lang;
    float d;
    ListView lv;
    ScrollView detSv;
    TextView detail, sub, btnBack, btnScan, tvIface, tvIp, tvMask, tvGw, tvDns, tvRange, tvNote,
            vTotal, vOnline, vTime, tvPhase, tvPct, tvMethods, tvListTitle;
    View barFill, barRest;
    BaseAdapter ad;
    ConnectivityManager.NetworkCallback cb;

    String t(String tr, String en, String ru) { return lang == 1 ? en : lang == 2 ? ru : tr; }

    int dp(float v) { return (int) (v * d); }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    int readLang() {
        SharedPreferences p = getSharedPreferences("vaxodpi", 0);
        if (p.contains("lang")) return p.getInt("lang", 0);
        String c = Locale.getDefault().getLanguage();
        return c.equals("tr") ? 0 : c.equals("ru") ? 2 : 1;
    }

    GradientDrawable rr(int fill, float r, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(r));
        if (stroke != 0) g.setStroke(Math.max(2, dp(1.2f)), stroke);
        return g;
    }

    TextView tv(String s, int sz, int col) {
        TextView x = new TextView(this);
        x.setText(s);
        x.setTextSize(sz);
        x.setTextColor(col);
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

    TextView kvRow(LinearLayout parent, String k) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, dp(5), 0, dp(5));
        TextView a = tv(k, 14, DIM);
        TextView b = bold("-", 14, Color.WHITE);
        b.setGravity(Gravity.END);
        r.addView(a, new LinearLayout.LayoutParams(0, -2, 0.8f));
        r.addView(b, new LinearLayout.LayoutParams(0, -2, 1.4f));
        parent.addView(r);
        return b;
    }

    TextView statCard(LinearLayout row, String label, boolean first) {
        LinearLayout c = card();
        c.addView(bold(label, 11, DIM));
        TextView v = bold("0", 24, Color.WHITE);
        v.setPadding(0, dp(8), 0, 0);
        c.addView(v);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        p.setMargins(first ? 0 : dp(5), 0, 0, 0);
        row.addView(c, p);
        return v;
    }

    View row(String t1, String t2, String right) {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(14), dp(4), dp(14), dp(4));
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(rr(CARD, 18, LINE));
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout m = new LinearLayout(this);
        m.setOrientation(LinearLayout.VERTICAL);
        TextView a = bold(t1, 15, Color.WHITE);
        a.setSingleLine(true);
        a.setEllipsize(TextUtils.TruncateAt.END);
        TextView b = tv(t2, 12, DIM);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        m.addView(a);
        m.addView(b);
        c.addView(m, new LinearLayout.LayoutParams(0, -2, 1f));
        c.addView(bold(right, 12, Color.WHITE));
        wrap.addView(c);
        return wrap;
    }

    String na() { return t("Kullanılamıyor", "Unavailable", "Недоступно"); }

    String typeName(Device x) {
        switch (x.type) {
            case DeviceInfoResolver.T_SELF: return t("Bu telefon", "This phone", "Этот телефон");
            case DeviceInfoResolver.T_ROUTER: return t("Yönlendirici / ağ geçidi", "Router / gateway", "Роутер / шлюз");
            case DeviceInfoResolver.T_PRINTER: return t("Yazıcı", "Printer", "Принтер") + guess();
            case DeviceInfoResolver.T_MEDIA: return t("TV / medya cihazı", "TV / media device", "ТВ / медиаустройство") + guess();
            case DeviceInfoResolver.T_APPLE: return t("Apple cihazı", "Apple device", "Устройство Apple") + guess();
            case DeviceInfoResolver.T_PC: return t("Bilgisayar", "Computer", "Компьютер") + guess();
            case DeviceInfoResolver.T_IOT: return t("Akıllı ev / IoT", "Smart home / IoT", "Умный дом / IoT") + guess();
            case DeviceInfoResolver.T_SERVER: return t("Sunucu", "Server", "Сервер") + guess();
            case DeviceInfoResolver.T_IOS: return "iPhone / iPad" + guess();
            default: return t("Bilinmiyor", "Unknown", "Неизвестно");
        }
    }

    String guess() { return " " + t("(tahmin)", "(guess)", "(догадка)"); }

    String emoji(Device x) {
        switch (x.type) {
            case DeviceInfoResolver.T_SELF: return "📱";
            case DeviceInfoResolver.T_ROUTER: return "📶";
            case DeviceInfoResolver.T_PRINTER: return "🖨";
            case DeviceInfoResolver.T_MEDIA: return "📺";
            case DeviceInfoResolver.T_APPLE: case DeviceInfoResolver.T_IOS: return "📱";
            case DeviceInfoResolver.T_PC: return "💻";
            case DeviceInfoResolver.T_IOT: return "🏠";
            case DeviceInfoResolver.T_SERVER: return "🖥";
            default: return "•";
        }
    }

    String title(Device x) {
        if (x.self) return emoji(x) + " " + t("Bu telefon", "This phone", "Этот телефон") + (x.host != null ? " (" + x.host + ")" : "");
        String n = x.host != null ? x.host : x.mdnsName != null ? x.mdnsName : x.ip;
        return emoji(x) + " " + n;
    }

    String detailText(Device x) {
        StringBuilder s = new StringBuilder();
        s.append("IP         ").append(x.ip).append('\n');
        s.append("MAC        ").append(x.mac != null ? x.mac : na()).append('\n');
        s.append(t("Ad         ", "Hostname   ", "Имя        ")).append(x.host != null ? x.host : na()).append('\n');
        if (x.mdnsName != null) s.append("mDNS       ").append(x.mdnsName).append('\n');
        s.append(t("Üretici    ", "Vendor     ", "Произв.    ")).append(na()).append('\n');
        s.append(t("Tür        ", "Type       ", "Тип        ")).append(typeName(x));
        if (!x.typeBasis.equals("-")) s.append("  [").append(x.typeBasis).append(']');
        s.append('\n');
        s.append(t("Durum      ", "Status     ", "Статус      ")).append(x.online ? t("Çevrimiçi", "Online", "В сети") : "?").append('\n');
        s.append(t("Yanıt      ", "Response   ", "Отклик     ")).append(x.rtt >= 0 ? x.rtt + " ms" : na()).append('\n');
        s.append(t("Açık port  ", "Open ports ", "Порты      ")).append(x.ports.isEmpty() ? "-" : x.ports.toString().replace("[", "").replace("]", "")).append('\n');
        s.append(t("Servisler  ", "Services   ", "Службы     "));
        if (x.services.isEmpty()) s.append("-\n");
        else {
            s.append('\n');
            for (String sv : x.services) s.append("  ").append(sv).append('\n');
        }
        s.append(t("Bulunma    ", "Found by   ", "Найдено    ")).append(x.methods.toString().replace("[", "").replace("]", "")).append('\n');
        if (x.gateway) s.append('\n').append(t("Varsayılan ağ geçidi.", "Default gateway.", "Шлюз по умолчанию.")).append('\n');
        if (x.mac == null && !x.self) {
            s.append('\n').append(t("MAC adresi okunamadı. Android 10 ve üstü uygulamaların MAC/ARP tablosuna erişimini engeller. Üretici bilgisi MAC adresine bağlı olduğu için de gösterilemez.",
                    "The MAC address could not be read. Android 10 and newer block apps from the MAC/ARP table, so the vendor cannot be shown either.",
                    "MAC-адрес прочитать не удалось. Android 10+ запрещает приложениям доступ к таблице MAC/ARP, поэтому производитель тоже недоступен.")).append('\n');
        }
        return s.toString();
    }

    static String clock(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    // ---------- tarama ----------
    void toggleScan() {
        if (repo.state == ScanRepository.RUNNING) {
            NetworkScanner sc = repo.scanner;
            if (sc != null) sc.cancel();
            refresh();
            return;
        }
        net = NetworkInfoManager.read(this);
        if (!net.wifi) {
            toast(t("Wi-Fi bağlantısı yok. Tarama yalnızca bağlı olduğun Wi-Fi ağında yapılır.",
                    "No Wi-Fi connection. Scanning works only on the Wi-Fi network you are connected to.",
                    "Нет подключения к Wi-Fi. Сканирование работает только в сети Wi-Fi, к которой вы подключены."));
            refresh();
            return;
        }
        if (net.hostCount() <= 0) {
            toast(t("Bu ağda taranacak adres yok.", "No addresses to scan on this network.", "В этой сети нет адресов для сканирования."));
            return;
        }
        NetworkScanner old = repo.scanner;
        if (old != null) old.cancel();
        NetworkScanner sc = new NetworkScanner(getApplicationContext(), repo);
        repo.scanner = sc;
        sel = null;
        sc.start(net);
        refresh();
    }

    void onNetChanged() {
        NetInfo n = NetworkInfoManager.read(this);
        boolean changed = !n.ip.equals(net.ip) || !n.gateway.equals(net.gateway) || n.wifi != net.wifi;
        net = n;
        if (changed) {
            if (repo.state == ScanRepository.RUNNING) {
                NetworkScanner sc = repo.scanner;
                if (sc != null) sc.cancel();
                toast(t("Ağ değişti, tarama durduruldu.", "Network changed, scan stopped.", "Сеть изменилась, сканирование остановлено."));
            }
            NetInfo old = repo.net;
            if (old != null && (!n.wifi || !n.range.equals(old.range))) repo.clear();
            sel = null;
        }
        refresh();
    }

    // ---------- yenileme ----------
    void refresh() {
        boolean run = repo.state == ScanRepository.RUNNING;
        tvIface.setText(net.iface);
        tvIp.setText(net.ip);
        tvMask.setText(net.mask);
        tvGw.setText(net.gateway);
        tvDns.setText(net.dns.isEmpty() ? "-" : TextUtils.join(", ", net.dns));
        tvRange.setText(net.range);
        if (!net.wifi) {
            tvNote.setVisibility(View.VISIBLE);
            tvNote.setText(t("Wi-Fi bağlantısı yok. Bir Wi-Fi ağına bağlanınca tarayabilirsin.",
                    "No Wi-Fi connection. Connect to a Wi-Fi network to scan.",
                    "Нет подключения к Wi-Fi. Подключитесь к сети Wi-Fi для сканирования."));
        } else if (net.limited) {
            tvNote.setVisibility(View.VISIBLE);
            tvNote.setText(t("Ağ /24'ten büyük: yalnızca kendi /24 aralığı taranır.",
                    "The network is larger than /24: only your own /24 range is scanned.",
                    "Сеть больше /24: сканируется только ваш диапазон /24."));
        } else {
            tvNote.setVisibility(View.GONE);
        }

        List<Device> l = repo.snapshot();
        rows.clear();
        rows.addAll(l);
        int online = 0, ping = 0, tcp = 0, mdns = 0, arp = 0;
        for (Device x : l) {
            if (x.online) online++;
            if (x.methods.contains("ping")) ping++;
            if (x.methods.contains("tcp")) tcp++;
            if (x.methods.contains("mdns")) mdns++;
            if (x.methods.contains("arp")) arp++;
        }
        vTotal.setText("" + l.size());
        vOnline.setText("" + online);
        vTime.setText(clock(repo.elapsedMs()));

        String ph;
        switch (repo.state) {
            case ScanRepository.RUNNING:
                if ("wait".equals(repo.phase)) ph = t("Servisler bekleniyor…", "Waiting for services…", "Ожидание служб…");
                else if ("details".equals(repo.phase)) ph = t("Cihaz bilgileri alınıyor…", "Resolving device info…", "Получение сведений…");
                else ph = t("Cihazlar aranıyor…", "Probing hosts…", "Поиск устройств…");
                break;
            case ScanRepository.DONE: ph = t("Tamamlandı", "Completed", "Завершено"); break;
            case ScanRepository.CANCELLED: ph = t("Durduruldu", "Stopped", "Остановлено"); break;
            case ScanRepository.FAILED: ph = t("Hata: ", "Error: ", "Ошибка: ") + repo.error; break;
            default: ph = t("Hazır", "Ready", "Готово");
        }
        tvPhase.setText(ph);
        float p = repo.progress();
        tvPct.setText((int) (p * 100) + "%");
        ((LinearLayout.LayoutParams) barFill.getLayoutParams()).weight = p;
        ((LinearLayout.LayoutParams) barRest.getLayoutParams()).weight = 1f - p;
        barFill.requestLayout();

        boolean idle = repo.state == ScanRepository.IDLE;
        String arpTxt = idle ? "-" : "unavailable".equals(repo.arpState)
                ? t("kullanılamadı (Android 10+ engeller)", "unavailable (blocked on Android 10+)", "недоступно (блокируется в Android 10+)")
                : "" + arp;
        String mdnsTxt = idle ? "-" : !repo.mdnsOk
                ? t("kullanılamadı", "unavailable", "недоступно") : "" + mdns;
        tvMethods.setText("Ping (ICMP)   " + (idle ? "-" : ping) + "\nTCP               " + (idle ? "-" : tcp)
                + "\nmDNS            " + mdnsTxt + "\nARP               " + arpTxt);
        tvListTitle.setText(t("Cihazlar", "Devices", "Устройства") + " (" + l.size() + ")");

        btnScan.setText(run ? t("Durdur", "Stop", "Стоп")
                : idle ? t("Tara", "Scan", "Сканировать") : t("Tekrar tara", "Scan again", "Сканировать снова"));
        btnScan.setTextColor(run ? Color.WHITE : Color.BLACK);
        btnScan.setBackground(run ? rr(Color.BLACK, 24, Color.WHITE) : rr(Color.WHITE, 24, 0));

        lv.setVisibility(sel == null ? View.VISIBLE : View.GONE);
        detSv.setVisibility(sel == null ? View.GONE : View.VISIBLE);
        sub.setText(sel == null
                ? t("Ağ tarayıcı", "Network scanner", "Сканер сети")
                : "‹  " + (sel.host != null ? sel.host : sel.ip));
        if (sel != null) detail.setText(detailText(sel));
        ad.notifyDataSetChanged();
    }

    final Runnable tick = new Runnable() {
        @Override public void run() {
            try { refresh(); } catch (Throwable ignored) { }
            h.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        lang = readLang();
        d = getResources().getDisplayMetrics().density;
        try {
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
        } catch (Throwable ignored) { }
        net = NetworkInfoManager.read(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(0, dp(34), 0, 0);
        TextView brand = bold("VaxoScope", 28, Color.WHITE);
        brand.setPadding(dp(20), dp(8), dp(20), 0);
        root.addView(brand);
        sub = tv("", 14, DIM);
        sub.setPadding(dp(20), dp(4), dp(20), dp(8));
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (sel != null) { sel = null; refresh(); } }
        });
        root.addView(sub);

        FrameLayout pages = new FrameLayout(this);
        lv = new ListView(this);
        lv.setDividerHeight(0);
        lv.setDivider(null);
        lv.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        lv.setVerticalScrollBarEnabled(false);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);

        LinearLayout nc = card();
        TextView nh = bold(t("Ağ bilgisi", "Network info", "Сведения о сети"), 16, Color.WHITE);
        nh.setPadding(0, 0, 0, dp(6));
        nc.addView(nh);
        tvIface = kvRow(nc, t("Arayüz", "Interface", "Интерфейс"));
        tvIp = kvRow(nc, t("Yerel IP", "Local IP", "Локальный IP"));
        tvMask = kvRow(nc, t("Alt ağ maskesi", "Subnet mask", "Маска подсети"));
        tvGw = kvRow(nc, t("Ağ geçidi", "Gateway", "Шлюз"));
        tvDns = kvRow(nc, "DNS");
        tvRange = kvRow(nc, t("IP aralığı", "IP range", "Диапазон IP"));
        header.addView(nc, lp(0));

        tvNote = tv("", 12, 0xFFF0883E);
        tvNote.setPadding(dp(22), dp(8), dp(22), 0);
        header.addView(tvNote);

        LinearLayout stats = new LinearLayout(this);
        vTotal = statCard(stats, t("TOPLAM", "TOTAL", "ВСЕГО"), true);
        vOnline = statCard(stats, t("ÇEVRİMİÇİ", "ONLINE", "В СЕТИ"), false);
        vTime = statCard(stats, t("SÜRE", "DURATION", "ВРЕМЯ"), false);
        header.addView(stats, lp(10));

        LinearLayout pc = card();
        LinearLayout pr = new LinearLayout(this);
        tvPhase = tv("", 13, Color.WHITE);
        tvPct = bold("0%", 13, Color.WHITE);
        pr.addView(tvPhase, new LinearLayout.LayoutParams(0, -2, 1f));
        pr.addView(tvPct);
        pc.addView(pr);
        LinearLayout bar = new LinearLayout(this);
        bar.setBackground(rr(0xFF1C1C1C, 2, 0));
        barFill = new View(this);
        barFill.setBackground(rr(Color.WHITE, 2, 0));
        barRest = new View(this);
        bar.addView(barFill, new LinearLayout.LayoutParams(0, dp(4), 0f));
        bar.addView(barRest, new LinearLayout.LayoutParams(0, dp(4), 1f));
        LinearLayout.LayoutParams bpp = new LinearLayout.LayoutParams(-1, dp(4));
        bpp.topMargin = dp(10);
        pc.addView(bar, bpp);
        header.addView(pc, lp(10));

        LinearLayout mc = card();
        TextView mh = bold(t("Yöntemler (bulunan cihaz sayısı)", "Methods (devices found)", "Методы (найдено устройств)"), 14, Color.WHITE);
        mh.setPadding(0, 0, 0, dp(6));
        mc.addView(mh);
        tvMethods = tv("", 13, DIM);
        tvMethods.setTypeface(Typeface.MONOSPACE);
        mc.addView(tvMethods);
        header.addView(mc, lp(10));

        tvListTitle = bold("", 15, Color.WHITE);
        tvListTitle.setPadding(dp(20), dp(16), dp(20), dp(6));
        header.addView(tvListTitle);
        lv.addHeaderView(header, null, false);

        ad = new BaseAdapter() {
            @Override public int getCount() { return rows.size(); }
            @Override public Object getItem(int i) { return null; }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup p) {
                Device x = rows.get(i);
                return row(title(x), x.ip + " · " + typeName(x), x.rtt >= 0 ? x.rtt + " ms" : "—");
            }
        };
        lv.setAdapter(ad);
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> a, View v, int pos, long id) {
                int i = pos - lv.getHeaderViewsCount();
                if (i >= 0 && i < rows.size()) { sel = rows.get(i); refresh(); }
            }
        });
        pages.addView(lv);

        detSv = new ScrollView(this);
        detSv.setVerticalScrollBarEnabled(false);
        LinearLayout dbox = new LinearLayout(this);
        dbox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout dcard = card();
        detail = tv("", 13, Color.WHITE);
        detail.setTypeface(Typeface.MONOSPACE);
        detail.setLineSpacing(0, 1.15f);
        dcard.addView(detail);
        dbox.addView(dcard, lp(0));
        dbox.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(20)));
        detSv.addView(dbox);
        detSv.setVisibility(View.GONE);
        pages.addView(detSv);
        root.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setBackground(rr(0xFF0E0E0E, 34, 0xFF242424));
        nav.setPadding(dp(10), dp(10), dp(10), dp(10));
        btnBack = bold("‹ " + t("Geri", "Back", "Назад"), 15, Color.WHITE);
        btnBack.setGravity(Gravity.CENTER);
        btnBack.setPadding(0, dp(12), 0, dp(12));
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onBackPressed(); }
        });
        btnScan = bold("", 16, Color.BLACK);
        btnScan.setGravity(Gravity.CENTER);
        btnScan.setPadding(0, dp(14), 0, dp(14));
        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleScan(); }
        });
        nav.addView(btnBack, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(0, -2, 1.8f);
        gp.setMargins(dp(8), 0, 0, 0);
        nav.addView(btnScan, gp);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.setMargins(dp(16), dp(8), dp(16), dp(16));
        root.addView(nav, np);

        setContentView(root);
        cb = NetworkInfoManager.watch(this, new Runnable() {
            @Override public void run() {
                h.post(new Runnable() { @Override public void run() { onNetChanged(); } });
            }
        });
        refresh();
    }

    @Override public void onBackPressed() {
        if (sel != null) { sel = null; refresh(); }
        else super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        net = NetworkInfoManager.read(this);
        h.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (cb != null) NetworkInfoManager.unwatch(this, cb);
        if (isFinishing()) {
            NetworkScanner sc = repo.scanner;
            if (sc != null) sc.cancel();
        }
    }
}
