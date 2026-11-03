package xyz.valeh.vaxodpi.scan;

import java.net.InetAddress;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class DeviceInfoResolver {
    private DeviceInfoResolver() { }

    public static final int T_UNKNOWN = 0, T_SELF = 1, T_ROUTER = 2, T_PRINTER = 3, T_MEDIA = 4,
            T_APPLE = 5, T_PC = 6, T_IOT = 7, T_SERVER = 8, T_IOS = 9;

    public static final int[] PORTS = {22, 53, 80, 443, 445, 62078, 8008, 7000, 9100, 631};

    private static final ExecutorService DNS = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "VaxoScope-rdns");
            t.setDaemon(true);
            return t;
        }
    });

    /** Ters DNS. Süre dolarsa ya da ad bulunamazsa null. */
    public static String reverseDns(final String ip, long timeoutMs) {
        Future<String> f = DNS.submit(new Callable<String>() {
            @Override public String call() throws Exception {
                String h = InetAddress.getByName(ip).getCanonicalHostName();
                return (h == null || h.equals(ip)) ? null : h;
            }
        });
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            f.cancel(true);
            return null;
        }
    }

    /** Cihaz türünü mDNS servislerinden ve açık portlardan tahmin eder. Kesin değildir. */
    public static void classify(Device d) {
        if (d.self) { d.type = T_SELF; d.typeBasis = "-"; return; }
        if (d.gateway) { d.type = T_ROUTER; d.typeBasis = "gateway"; return; }
        String s = d.serviceTypes.toString();
        if (s.contains("_googlecast")) { set(d, T_MEDIA, "mDNS _googlecast"); return; }
        if (s.contains("_airplay") || s.contains("_raop") || s.contains("_companion-link")) { set(d, T_APPLE, "mDNS AirPlay"); return; }
        if (s.contains("_ipp") || s.contains("_printer")) { set(d, T_PRINTER, "mDNS printer"); return; }
        if (s.contains("_hap") || s.contains("_spotify-connect")) { set(d, T_IOT, "mDNS"); return; }
        if (s.contains("_smb") || s.contains("_workstation")) { set(d, T_PC, "mDNS"); return; }
        if (s.contains("_ssh")) { set(d, T_SERVER, "mDNS ssh"); return; }
        if (d.ports.contains(62078)) { set(d, T_IOS, "port 62078"); return; }
        if (d.ports.contains(8008)) { set(d, T_MEDIA, "port 8008"); return; }
        if (d.ports.contains(9100) || d.ports.contains(631)) { set(d, T_PRINTER, "port 9100/631"); return; }
        if (d.ports.contains(445)) { set(d, T_PC, "port 445"); return; }
        if (d.ports.contains(22)) { set(d, T_SERVER, "port 22"); return; }
        d.type = T_UNKNOWN;
        d.typeBasis = "-";
    }

    private static void set(Device d, int t, String basis) { d.type = t; d.typeBasis = basis; }
}
