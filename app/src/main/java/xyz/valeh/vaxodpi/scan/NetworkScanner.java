package xyz.valeh.vaxodpi.scan;

import android.content.Context;
import android.net.Network;
import android.os.Build;
import android.os.SystemClock;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Yerel ağ keşfi. Bir nesne = bir tarama. En çok 32 eşzamanlı iş parçacığı kullanır. */
public final class NetworkScanner {
    private static final int THREADS = 32;
    private static final int[] PROBE = {443, 80, 445, 62078, 22};
    private static final Pattern MAC = Pattern.compile("([0-9a-f]{2}:){5}[0-9a-f]{2}");
    private static final Pattern RAOP = Pattern.compile("^([0-9A-Fa-f]{12})@.*");

    private final Context ctx;
    private final ScanRepository repo;
    private volatile boolean cancelled;
    private volatile ExecutorService pool;
    private volatile MdnsDiscovery mdns;
    private Thread runner;

    public NetworkScanner(Context c, ScanRepository r) {
        ctx = c.getApplicationContext();
        repo = r;
    }

    public synchronized void start(final NetInfo net) {
        if (runner != null) return;
        repo.begin(net);
        runner = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    scan(net);
                } catch (Throwable t) {
                    if (!cancelled) repo.fail(String.valueOf(t));
                } finally {
                    cleanup();
                }
            }
        }, "VaxoScope-Scan");
        runner.start();
    }

    public void cancel() {
        cancelled = true;
        ExecutorService p = pool;
        if (p != null) p.shutdownNow();
        MdnsDiscovery m = mdns;
        if (m != null) m.stop();
        repo.markCancelled();
    }

    private void cleanup() {
        ExecutorService p = pool;
        if (p != null) p.shutdownNow();
        MdnsDiscovery m = mdns;
        if (m != null) m.stop();
    }

    private static boolean inRange(NetInfo n, String ip) {
        long v = NetInfo.parse(ip);
        return ip.equals(n.ip) || (v >= n.firstHost && v <= n.lastHost);
    }

    private void awaitPool() throws InterruptedException {
        while (!pool.awaitTermination(250, TimeUnit.MILLISECONDS)) {
            if (cancelled) return;
        }
    }

    private void scan(final NetInfo net) throws Exception {
        final long t0 = SystemClock.elapsedRealtime();
        final String key = net.range + "|" + net.gateway;
        repo.netKey = key;
        final Map<String, KnownStore.Rec> known = KnownStore.load(ctx, key);

        List<String> hosts = new ArrayList<String>();
        for (long a = net.firstHost; a <= net.lastHost; a++) {
            String ip = NetInfo.str(a);
            if (!ip.equals(net.ip)) hosts.add(ip);
        }
        repo.setTotal(hosts.size());

        Device self = repo.device(net.ip);
        self.self = true;
        self.online = true;
        self.host = Build.MODEL;
        self.methods.add("local");

        mdns = new MdnsDiscovery(ctx);
        repo.mdnsOk = mdns.start(new MdnsDiscovery.Listener() {
            @Override public void onFound(String ip, String name, String type, int port, Map<String, byte[]> attrs) {
                if (cancelled || !inRange(net, ip)) return;
                Device d = repo.device(ip);
                d.online = true;
                d.methods.add("mdns");
                if (name != null) {
                    if (d.mdnsName == null) d.mdnsName = name;
                    d.services.addIfAbsent(name + " (" + type + port + ")");
                }
                if (type != null) d.serviceTypes.add(type);
                if (d.mac == null && type != null) {
                    String mc = null;
                    if (type.contains("_airplay") && attrs != null) {
                        byte[] v = attrs.get("deviceid");
                        if (v != null) {
                            String m = new String(v, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
                            if (MAC.matcher(m).matches()) mc = m;
                        }
                    }
                    if (mc == null && type.contains("_raop") && name != null) {
                        Matcher mm = RAOP.matcher(name);
                        if (mm.matches()) {
                            String x = mm.group(1).toLowerCase(Locale.ROOT);
                            mc = x.substring(0, 2) + ":" + x.substring(2, 4) + ":" + x.substring(4, 6) + ":"
                                    + x.substring(6, 8) + ":" + x.substring(8, 10) + ":" + x.substring(10, 12);
                        }
                    }
                    if (mc != null) { d.mac = mc; d.macSrc = "mdns"; }
                }
            }
        });

        // 1) Canlılık: ICMP, olmazsa TCP
        repo.phase = "probe";
        pool = Executors.newFixedThreadPool(THREADS);
        for (final String ip : hosts) {
            pool.execute(new Runnable() {
                @Override public void run() {
                    try { probe(net, ip); } finally { repo.tick(); }
                }
            });
        }
        pool.shutdown();
        awaitPool();
        if (cancelled) return;

        // 2) mDNS'e süre tanı
        repo.phase = "wait";
        while (!cancelled && SystemClock.elapsedRealtime() - t0 < 7000) Thread.sleep(150);
        if (cancelled) return;

        // 3) ARP (Android 10+ genellikle erişimi engeller)
        Map<String, String> arp = ArpScanner.read();
        if (arp == null) {
            repo.arpState = "unavailable";
        } else {
            repo.arpState = "ok";
            for (Map.Entry<String, String> e : arp.entrySet()) {
                if (!inRange(net, e.getKey())) continue;
                Device d = repo.device(e.getKey());
                d.mac = e.getValue();
                d.macSrc = "arp";
                d.online = true;
                d.methods.add("arp");
            }
        }

        // 4) Ayrıntılar: ters DNS, port, tür tahmini
        repo.phase = "details";
        final List<Device> alive = repo.snapshot();
        repo.setDetailTotal(alive.size());
        pool = Executors.newFixedThreadPool(16);
        for (final Device d : alive) {
            pool.execute(new Runnable() {
                @Override public void run() {
                    try { detail(net, d); } finally { repo.detailTick(); }
                }
            });
        }
        pool.shutdown();
        awaitPool();
        if (cancelled) return;

        // 5) Geçmişle karşılaştır
        final long now = System.currentTimeMillis();
        for (Device d : repo.snapshot()) {
            if (!d.online) continue;
            KnownStore.Rec r = known.get(d.ip);
            d.firstSeen = (r != null && r.first > 0) ? r.first : now;
            d.lastSeen = now;
            if (!d.self && d.host == null && r != null && r.host != null) {
                d.host = r.host;
                d.hostCached = true;
            }
        }
        final List<Device> missing = new ArrayList<Device>();
        for (KnownStore.Rec r : known.values()) {
            if (r.ip.equals(net.ip) || !inRange(net, r.ip) || repo.devices.containsKey(r.ip)) continue;
            Device d = repo.device(r.ip);
            d.host = r.host;
            d.hostCached = r.host != null;
            d.mdnsName = r.mdns;
            d.mac = r.mac;
            d.macSrc = r.macSrc;
            d.type = r.type;
            d.typeBasis = r.basis == null ? "-" : r.basis;
            d.ports.addAll(r.ports);
            d.firstSeen = r.first;
            d.lastSeen = r.last;
            missing.add(d);
        }

        // 6) Kayıtlı ama bulunamayan cihazları yeniden dene, yanıt yoksa çevrimdışı say
        repo.phase = "verify";
        repo.setVerifyTotal(missing.size());
        pool = Executors.newFixedThreadPool(8);
        for (final Device d : missing) {
            pool.execute(new Runnable() {
                @Override public void run() {
                    try { verify(net, d, now); } finally { repo.verifyTick(); }
                }
            });
        }
        pool.shutdown();
        awaitPool();
        if (cancelled) return;

        // 7) Kaydet
        Map<String, KnownStore.Rec> out = new LinkedHashMap<String, KnownStore.Rec>(known);
        for (Device d : repo.snapshot()) {
            if (d.self) continue;
            KnownStore.Rec r = out.get(d.ip);
            if (r == null) {
                if (!d.online) continue;
                r = new KnownStore.Rec();
                r.ip = d.ip;
                r.first = d.firstSeen > 0 ? d.firstSeen : now;
                out.put(d.ip, r);
            }
            if (d.online) {
                r.last = now;
                if (d.host != null && !d.hostCached) r.host = d.host;
                if (d.mdnsName != null) r.mdns = d.mdnsName;
                if (d.mac != null) { r.mac = d.mac; r.macSrc = d.macSrc; }
                r.type = d.type;
                r.basis = d.typeBasis;
                r.ports.clear();
                r.ports.addAll(d.ports);
            }
        }
        KnownStore.save(ctx, key, out.values());

        MdnsDiscovery m = mdns;
        if (m != null) m.stop();
        repo.finish();
    }

    private void probe(NetInfo net, String ip) {
        if (cancelled) return;
        long t = SystemClock.elapsedRealtime();
        boolean alive = false;
        long rtt = -1;
        String how = null;
        int openPort = -1;
        try {
            if (InetAddress.getByName(ip).isReachable(450)) {
                alive = true;
                rtt = SystemClock.elapsedRealtime() - t;
                how = "ping";
            }
        } catch (Throwable ignored) { }
        if (!alive) {
            for (int p : PROBE) {
                if (cancelled) return;
                long[] r = new long[1];
                int s = tcp(net.network, ip, p, 300, r);
                if (s >= 0) {
                    alive = true;
                    rtt = r[0];
                    how = "tcp";
                    if (s == 1) openPort = p;
                    break;
                }
            }
        }
        if (!alive || cancelled) return;
        Device d = repo.device(ip);
        d.online = true;
        d.methods.add(how);
        if (openPort > 0) d.ports.add(openPort);
        if (rtt >= 0 && (d.rtt < 0 || rtt < d.rtt)) d.rtt = rtt;
    }

    private void detail(NetInfo net, Device d) {
        if (cancelled) return;
        if (d.ip.equals(net.gateway)) d.gateway = true;
        if (!d.self) {
            String hn = DeviceInfoResolver.reverseDns(d.ip, 1500);
            if (hn != null) d.host = hn;
            for (int p : DeviceInfoResolver.PORTS) {
                if (cancelled) return;
                if (d.ports.contains(p)) continue;
                long[] r = new long[1];
                int s = tcp(net.network, d.ip, p, 300, r);
                if (s == 1) d.ports.add(p);
                if (s >= 0 && d.rtt < 0) d.rtt = r[0];
            }
        }
        DeviceInfoResolver.classify(d);
    }

    private void verify(NetInfo net, Device d, long now) {
        if (cancelled) return;
        boolean alive = false;
        long rtt = -1;
        long t = SystemClock.elapsedRealtime();
        try {
            if (InetAddress.getByName(d.ip).isReachable(700)) {
                alive = true;
                rtt = SystemClock.elapsedRealtime() - t;
            }
        } catch (Throwable ignored) { }
        if (!alive) {
            List<Integer> ps = new ArrayList<Integer>(d.ports);
            if (!ps.contains(443)) ps.add(443);
            if (!ps.contains(80)) ps.add(80);
            for (int p : ps) {
                if (cancelled) return;
                long[] r = new long[1];
                if (tcp(net.network, d.ip, p, 400, r) >= 0) {
                    alive = true;
                    rtt = r[0];
                    break;
                }
            }
        }
        if (cancelled) return;
        if (alive) {
            d.online = true;
            d.offline = false;
            d.lastSeen = now;
            d.rtt = rtt;
            d.methods.add("recheck");
        } else {
            d.online = false;
            d.offline = true;
        }
    }

    /** 1: bağlandı (port açık). 0: reddedildi (cihaz canlı). -1: cevap yok. */
    static int tcp(Network nw, String ip, int port, int timeout, long[] rtt) {
        Socket s = new Socket();
        long t = SystemClock.elapsedRealtime();
        try {
            if (nw != null) nw.bindSocket(s);
            s.connect(new InetSocketAddress(InetAddress.getByName(ip), port), timeout);
            rtt[0] = SystemClock.elapsedRealtime() - t;
            return 1;
        } catch (ConnectException e) {
            String m = e.getMessage();
            if (m != null && m.contains("ECONNREFUSED")) {
                rtt[0] = SystemClock.elapsedRealtime() - t;
                return 0;
            }
            return -1;
        } catch (IOException e) {
            return -1;
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }
}
