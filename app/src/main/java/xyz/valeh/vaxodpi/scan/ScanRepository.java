package xyz.valeh.vaxodpi.scan;

import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Tarama durumunun tek sahibi. Ekran dönse ya da kapansa bile korunur. */
public final class ScanRepository {
    public static final int IDLE = 0, RUNNING = 1, DONE = 2, CANCELLED = 3, FAILED = 4;

    private static final ScanRepository I = new ScanRepository();
    public static ScanRepository get() { return I; }
    private ScanRepository() { }

    public volatile int state = IDLE;
    public volatile String phase = "", error = "", arpState = "-";
    public volatile boolean mdnsOk;
    public volatile NetInfo net;
    public volatile NetworkScanner scanner;
    public volatile long startMs, endMs;
    public final ConcurrentHashMap<String, Device> devices = new ConcurrentHashMap<String, Device>();

    private volatile int total, dTotal;
    private final AtomicInteger done = new AtomicInteger(), dDone = new AtomicInteger();

    synchronized void begin(NetInfo n) {
        devices.clear();
        net = n;
        error = "";
        arpState = "-";
        mdnsOk = false;
        phase = "probe";
        total = 0;
        dTotal = 0;
        done.set(0);
        dDone.set(0);
        startMs = SystemClock.elapsedRealtime();
        endMs = 0;
        state = RUNNING;
    }

    void setTotal(int t) { total = t; }
    void tick() { done.incrementAndGet(); }
    void setDetailTotal(int t) { dTotal = t; dDone.set(0); }
    void detailTick() { dDone.incrementAndGet(); }

    synchronized void finish() {
        if (state != RUNNING) return;
        endMs = SystemClock.elapsedRealtime();
        state = DONE;
    }

    synchronized void fail(String m) {
        if (state != RUNNING) return;
        error = m;
        endMs = SystemClock.elapsedRealtime();
        state = FAILED;
    }

    synchronized void markCancelled() {
        if (state != RUNNING) return;
        endMs = SystemClock.elapsedRealtime();
        state = CANCELLED;
    }

    public synchronized void clear() {
        if (state == RUNNING) return;
        devices.clear();
        state = IDLE;
        startMs = 0;
        endMs = 0;
        net = null;
    }

    Device device(String ip) {
        Device d = devices.get(ip);
        if (d == null) {
            Device n = new Device(ip);
            d = devices.putIfAbsent(ip, n);
            if (d == null) d = n;
        }
        return d;
    }

    public List<Device> snapshot() {
        TreeMap<Long, Device> tm = new TreeMap<Long, Device>();
        for (Device d : devices.values()) {
            long g = d.self ? 0 : d.gateway ? 1 : 2;
            tm.put((g << 40) + NetInfo.parse(d.ip), d);
        }
        return new ArrayList<Device>(tm.values());
    }

    public float progress() {
        if (state == DONE) return 1f;
        if (total <= 0) return 0f;
        float p1 = Math.min(1f, done.get() / (float) total);
        if (!"details".equals(phase)) return 0.8f * p1;
        float p2 = dTotal == 0 ? 1f : Math.min(1f, dDone.get() / (float) dTotal);
        return 0.8f + 0.2f * p2;
    }

    public long elapsedMs() {
        if (startMs == 0) return 0;
        return (state == RUNNING ? SystemClock.elapsedRealtime() : endMs) - startMs;
    }
}
