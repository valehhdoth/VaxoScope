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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Yerel ağ keşfi. Bir nesne = bir tarama. En çok 32 eşzamanlı iş parçacığı kullanır. */
public final class NetworkScanner {
    private static final int THREADS = 32;
    private static final int[] PROBE = {443, 80, 445, 62078, 22};

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
            @Override public void onFound(String ip, String name, String type, int port) {
                if (cancelled || !inRange(net, ip)) return;
                Device d = repo.device(ip);
                d.online = true;
                d.methods.add("mdns");
                if (name != null) {
                    if (d.mdnsName == null) d.mdnsName = name;
                    d.services.addIfAbsent(name + " (" + type + port + ")");
                }
                if (type != null) d.serviceTypes.add(type);
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
