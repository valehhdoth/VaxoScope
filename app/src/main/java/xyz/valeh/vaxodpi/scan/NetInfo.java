package xyz.valeh.vaxodpi.scan;

import android.net.Network;
import java.util.ArrayList;
import java.util.List;

public final class NetInfo {
    public boolean wifi;
    public String iface = "-", ip = "-", mask = "-", gateway = "-", range = "-";
    public final List<String> dns = new ArrayList<String>();
    public int prefix;
    public long firstHost, lastHost = -1;
    public boolean limited;
    public Network network;

    public int hostCount() { return lastHost >= firstHost ? (int) (lastHost - firstHost + 1) : 0; }

    public static String str(long v) {
        return ((v >> 24) & 255) + "." + ((v >> 16) & 255) + "." + ((v >> 8) & 255) + "." + (v & 255);
    }

    public static long parse(String s) {
        try {
            String[] p = s.split("\\.");
            if (p.length != 4) return -1;
            long v = 0;
            for (String x : p) {
                int i = Integer.parseInt(x);
                if (i < 0 || i > 255) return -1;
                v = (v << 8) | i;
            }
            return v;
        } catch (Throwable e) {
            return -1;
        }
    }
}
