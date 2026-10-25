package xyz.valeh.vaxodpi;

import java.util.concurrent.ConcurrentHashMap;

final class PacketStats {
    static final class Conn {
        int proto, lport, rport;
        String laddr, raddr;
        long pOut, bOut, pIn, bIn, first, last;
        volatile String name;
    }

    static final ConcurrentHashMap<String, Conn> TABLE = new ConcurrentHashMap<>();
    static volatile String lastName = "-";

    static String summary() {
        int named = 0;
        for (Conn c : TABLE.values()) if (c.name != null) named++;
        return "bag=" + TABLE.size() + " ad=" + named + " son=" + lastName;
    }

    private static int u16(byte[] b, int i) {
        return ((b[i] & 255) << 8) | (b[i + 1] & 255);
    }

    private static String a4(byte[] b, int i) {
        return (b[i] & 255) + "." + (b[i + 1] & 255) + "." + (b[i + 2] & 255) + "." + (b[i + 3] & 255);
    }

    private static String a6(byte[] b, int i) {
        StringBuilder s = new StringBuilder();
        for (int k = 0; k < 16; k += 2) {
            if (k > 0) s.append(':');
            s.append(Integer.toHexString(u16(b, i + k)));
        }
        return s.toString();
    }

    static void onPacket(boolean out, byte[] b, int n) {
        try {
            parse(out, b, n);
        } catch (Throwable ignored) {
        }
    }

    private static void parse(boolean out, byte[] b, int n) {
        if (n < 20) return;
        int v = (b[0] >> 4) & 15;
        int ip, pr;
        String sa, da;
        if (v == 4) {
            ip = (b[0] & 15) * 4;
            pr = b[9] & 255;
            sa = a4(b, 12);
            da = a4(b, 16);
        } else if (v == 6) {
            if (n < 44) return;
            ip = 40;
            pr = b[6] & 255;
            sa = a6(b, 8);
            da = a6(b, 24);
        } else {
            return;
        }
        int sp = 0, dp = 0;
        if ((pr == 6 || pr == 17) && n >= ip + 4) {
            sp = u16(b, ip);
            dp = u16(b, ip + 2);
        }
        String key = out
                ? pr + "|" + sa + ":" + sp + ">" + da + ":" + dp
                : pr + "|" + da + ":" + dp + ">" + sa + ":" + sp;
        Conn c = TABLE.get(key);
        long now = System.currentTimeMillis();
        if (c == null) {
            c = new Conn();
            c.proto = pr;
            c.laddr = out ? sa : da;
            c.lport = out ? sp : dp;
            c.raddr = out ? da : sa;
            c.rport = out ? dp : sp;
            c.first = now;
            TABLE.put(key, c);
        }
        c.last = now;
        if (out) { c.pOut++; c.bOut += n; } else { c.pIn++; c.bIn += n; }

        if (!out || c.name != null) return;
        if (pr == 17 && dp == 53 && n > ip + 8 + 12) {
            String d = dns(b, ip + 8 + 12, n);
            if (d != null) { c.name = d; lastName = d; }
        } else if (pr == 6 && n >= ip + 20) {
            int off = ip + ((b[ip + 12] >> 4) & 15) * 4;
            if (n > off + 44 && (b[off] & 255) == 0x16) {
                String s = sni(b, off, n);
                if (s != null) { c.name = s; lastName = s; }
            }
        }
    }

    private static String dns(byte[] b, int p, int n) {
        StringBuilder s = new StringBuilder();
        while (p < n) {
            int l = b[p] & 255;
            if (l == 0 || l >= 192) break;
            if (p + 1 + l > n) return null;
            if (s.length() > 0) s.append('.');
            for (int k = 1; k <= l; k++) s.append((char) (b[p + k] & 255));
            p += l + 1;
        }
        return s.length() > 0 ? s.toString() : null;
    }

    private static String sni(byte[] b, int o, int n) {
        if (b[o + 1] != 3 || (b[o + 5] & 255) != 1) return null;
        int p = o + 43;
        if (p >= n) return null;
        p += 1 + (b[p] & 255);
        if (p + 2 > n) return null;
        p += 2 + u16(b, p);
        if (p >= n) return null;
        p += 1 + (b[p] & 255);
        if (p + 2 > n) return null;
        int end = Math.min(n, p + 2 + u16(b, p));
        p += 2;
        while (p + 4 <= end) {
            int t = u16(b, p), l = u16(b, p + 2);
            p += 4;
            if (t == 0) {
                if (p + 5 > end) return null;
                int nl = u16(b, p + 3);
                int st = p + 5;
                if (st + nl > n) return null;
                return new String(b, st, nl, java.nio.charset.StandardCharsets.US_ASCII);
            }
            p += l;
        }
        return null;
    }
}
