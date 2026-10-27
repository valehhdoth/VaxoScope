package xyz.valeh.vaxodpi;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

final class IpInfo {
    static volatile boolean ready;
    static volatile int count4, count6;
    private static int[] s4, e4, o4, o6, off;
    private static long[] s6, e6;
    private static byte[] blob;
    private static boolean started;
    private static final String[] NONE = new String[0];
    private static final ConcurrentHashMap<String, String[]> CACHE = new ConcurrentHashMap<>();

    static synchronized void init(final Context x) {
        if (started) return;
        started = true;
        final Context app = x.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override public void run() { load(app); }
        }, "VaxoScope-IpDb");
        t.setDaemon(true);
        t.start();
    }

    private static void load(Context x) {
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(x.getAssets().open("ipdb.bin"), 1 << 16));
            byte[] mg = new byte[4];
            in.readFully(mg);
            if (!"VIP1".equals(new String(mg, StandardCharsets.US_ASCII))) { in.close(); return; }
            int n4 = in.readInt();
            int[] a = new int[n4], b = new int[n4], c = new int[n4];
            for (int i = 0; i < n4; i++) { a[i] = in.readInt(); b[i] = in.readInt(); c[i] = in.readInt(); }
            int n6 = in.readInt();
            long[] a6 = new long[n6], b6 = new long[n6];
            int[] c6 = new int[n6];
            for (int i = 0; i < n6; i++) { a6[i] = in.readLong(); b6[i] = in.readLong(); c6[i] = in.readInt(); }
            int no = in.readInt();
            int[] of = new int[no + 1];
            ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 20);
            for (int i = 0; i < no; i++) {
                int len = in.readUnsignedShort();
                byte[] bb = new byte[len];
                in.readFully(bb);
                of[i] = bo.size();
                bo.write(bb, 0, len);
            }
            of[no] = bo.size();
            in.close();
            s4 = a; e4 = b; o4 = c; s6 = a6; e6 = b6; o6 = c6; off = of; blob = bo.toByteArray();
            count4 = n4; count6 = n6;
            ready = true;
        } catch (Throwable ignored) { }
    }

    private static boolean lt(int a, int b) { return (a ^ 0x80000000) < (b ^ 0x80000000); }
    private static boolean lt(long a, long b) { return (a ^ Long.MIN_VALUE) < (b ^ Long.MIN_VALUE); }

    private static boolean localV4(int a, int b) {
        return a == 0 || a == 10 || a == 127 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)
                || (a == 169 && b == 254) || (a == 100 && b >= 64 && b <= 127) || (a == 198 && (b == 18 || b == 19)) || a >= 224;
    }

    private static String[] decode(int i) {
        String s = new String(blob, off[i], off[i + 1] - off[i], StandardCharsets.UTF_8);
        String[] p = s.split("\t", 3);
        return p.length == 3 ? p : null;
    }

    /** {ulke, asn, ad}. Yerel adreslerde {"--","",""}. Bilinmiyorsa ya da yuklenmediyse null. */
    static String[] lookup(String ip) {
        if (!ready || ip == null) return null;
        String[] r = CACHE.get(ip);
        if (r != null) return r == NONE ? null : r;
        try {
            r = calc(ip);
        } catch (Throwable e) { r = null; }
        if (CACHE.size() > 8000) CACHE.clear();
        CACHE.put(ip, r == null ? NONE : r);
        return r;
    }

    private static String[] calc(String ip) {
        if (ip.indexOf(':') < 0) {
            String[] p = ip.split("\\.");
            if (p.length != 4) return null;
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]), c = Integer.parseInt(p[2]), d = Integer.parseInt(p[3]);
            if (localV4(a, b)) return new String[]{"--", "", ""};
            int v = (a << 24) | (b << 16) | (c << 8) | d;
            int lo = 0, hi = s4.length - 1, f = -1;
            while (lo <= hi) {
                int m = (lo + hi) >>> 1;
                if (!lt(v, s4[m])) { f = m; lo = m + 1; } else hi = m - 1;
            }
            if (f >= 0 && !lt(e4[f], v)) return decode(o4[f]);
            return null;
        }
        String[] g = ip.split(":");
        if (g.length < 4) return null;
        String g0 = g[0].toLowerCase(java.util.Locale.ROOT);
        if (g0.startsWith("fe8") || g0.startsWith("fe9") || g0.startsWith("fea") || g0.startsWith("feb")
                || g0.startsWith("fc") || g0.startsWith("fd") || g0.startsWith("ff")
                || ip.equals("0:0:0:0:0:0:0:1")) return new String[]{"--", "", ""};
        long v = 0;
        for (int i = 0; i < 4; i++) v = (v << 16) | (g[i].length() == 0 ? 0 : Long.parseLong(g[i], 16));
        int lo = 0, hi = s6.length - 1, f = -1;
        while (lo <= hi) {
            int m = (lo + hi) >>> 1;
            if (!lt(v, s6[m])) { f = m; lo = m + 1; } else hi = m - 1;
        }
        if (f >= 0 && !lt(e6[f], v)) return decode(o6[f]);
        return null;
    }

    static String flag(String cc) {
        if (cc == null || cc.length() != 2 || cc.equals("ZZ") || cc.equals("--")) return "🌐";
        cc = cc.toUpperCase(java.util.Locale.ROOT);
        char a = cc.charAt(0), b = cc.charAt(1);
        if (a < 'A' || a > 'Z' || b < 'A' || b > 'Z') return "🌐";
        StringBuilder s = new StringBuilder();
        s.appendCodePoint(0x1F1E6 + (a - 'A'));
        s.appendCodePoint(0x1F1E6 + (b - 'A'));
        return s.toString();
    }
}
