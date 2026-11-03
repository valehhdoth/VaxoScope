package xyz.valeh.vaxodpi.scan;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public final class MdnsDiscovery {
    public interface Listener { void onFound(String ip, String name, String type, int port); }

    static final String[] TYPES = {
        "_http._tcp.", "_googlecast._tcp.", "_airplay._tcp.", "_raop._tcp.", "_companion-link._tcp.",
        "_ipp._tcp.", "_printer._tcp.", "_smb._tcp.", "_workstation._tcp.", "_ssh._tcp.",
        "_hap._tcp.", "_spotify-connect._tcp."
    };

    private final NsdManager nsd;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<NsdManager.DiscoveryListener> active = new ArrayList<NsdManager.DiscoveryListener>();
    private final LinkedList<NsdServiceInfo> queue = new LinkedList<NsdServiceInfo>();
    private Listener out;
    private boolean resolving, stopped = true;
    private int gen;

    public MdnsDiscovery(Context c) {
        NsdManager m = null;
        try { m = (NsdManager) c.getApplicationContext().getSystemService(Context.NSD_SERVICE); } catch (Throwable ignored) { }
        nsd = m;
    }

    public boolean available() { return nsd != null; }

    public synchronized boolean start(Listener l) {
        if (nsd == null) return false;
        out = l;
        stopped = false;
        int ok = 0;
        for (String t : TYPES) {
            NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
                @Override public void onStartDiscoveryFailed(String s, int e) { }
                @Override public void onStopDiscoveryFailed(String s, int e) { }
                @Override public void onDiscoveryStarted(String s) { }
                @Override public void onDiscoveryStopped(String s) { }
                @Override public void onServiceFound(NsdServiceInfo si) { enqueue(si); }
                @Override public void onServiceLost(NsdServiceInfo si) { }
            };
            try {
                nsd.discoverServices(t, NsdManager.PROTOCOL_DNS_SD, dl);
                active.add(dl);
                ok++;
            } catch (Throwable ignored) { }
        }
        return ok > 0;
    }

    private synchronized void enqueue(NsdServiceInfo si) {
        if (stopped) return;
        queue.add(si);
        pump();
    }

    private synchronized boolean isStopped() { return stopped; }

    private void pump() {
        if (stopped || resolving || queue.isEmpty()) return;
        final NsdServiceInfo si = queue.removeFirst();
        resolving = true;
        final int g = ++gen;
        h.postDelayed(new Runnable() {
            @Override public void run() { done(g); }
        }, 3000);
        try {
            nsd.resolveService(si, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo s, int e) { done(g); }
                @Override public void onServiceResolved(NsdServiceInfo s) {
                    try {
                        InetAddress a = s.getHost();
                        Listener l = out;
                        if (l != null && a instanceof Inet4Address && !isStopped()) {
                            l.onFound(a.getHostAddress(), s.getServiceName(), s.getServiceType(), s.getPort());
                        }
                    } catch (Throwable ignored) { }
                    done(g);
                }
            });
        } catch (Throwable t) {
            resolving = false;
        }
    }

    private synchronized void done(int g) {
        if (g == gen) {
            resolving = false;
            pump();
        }
    }

    public synchronized void stop() {
        stopped = true;
        queue.clear();
        for (NsdManager.DiscoveryListener dl : active) {
            try { nsd.stopServiceDiscovery(dl); } catch (Throwable ignored) { }
        }
        active.clear();
        out = null;
        resolving = false;
    }
}
