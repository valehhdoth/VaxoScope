package xyz.valeh.vaxodpi;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class PacketStats {
    static final int CAP = 512, KEEP = 40;

    static final class Pkt { long t; boolean out; int orig; byte[] data; }

    static final class Conn {
        int proto, lport, rport;
        String laddr, raddr;
        long pOut, bOut, pIn, bIn, first, last;
        volatile String name;
        volatile int uid = -2;
        volatile String guess;
        long sUp, sDown;
        boolean sCnt;
        final ArrayDeque<Pkt> pkts = new ArrayDeque<>();
        List<Pkt> snapshot() { synchronized (pkts) { return new ArrayList<>(pkts); } }
    }

    static final ConcurrentHashMap<String, Conn> TABLE = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, Conn> IMPORT = new ConcurrentHashMap<>();
    static volatile String lastName = "-";
    static volatile long lastPkt, totIn, totOut, pcapPkts;
    static volatile boolean pcapOn = true;
    static volatile File dir;

    private static final Object LK = new Object();
    private static OutputStream pw;
    private static File pf;
    private static long pcapBytes, lastFlush;
    private static volatile android.net.ConnectivityManager cm;
    private static final java.util.concurrent.ExecutorService EX =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    // ---------- IP -> ad, DNS gecmisi, suzulmus PCAP ----------
    static final ConcurrentHashMap<String, String> IPNAME = new ConcurrentHashMap<>();

    static final class Dns {
        long t; String name; int id, qtype; Conn conn;
        volatile int rcode = -1;
        volatile String ans = "";
    }

    static final ArrayDeque<Dns> DNSLOG = new ArrayDeque<>();

    static List<Dns> dnsSnapshot() { synchronized (DNSLOG) { return new ArrayList<>(DNSLOG); } }

    private static int skipName(byte[] b, int p, int n) {
        while (p < n) {
            int l = b[p] & 255;
            if (l == 0) return p + 1;
            if ((l & 0xC0) == 0xC0) return p + 2;
            p += l + 1;
        }
        return n;
    }

    private static void hook(Conn c, boolean out, byte[] b, int n, int ip, int pr, int sp, int dp, long t) {
        try {
            if (c.name == null) {
                String g = IPNAME.get(c.raddr);
                if (g != null) c.guess = g;
            }
            if (pr == 17 && n > ip + 8 + 12) {
                if (out && dp == 53) dnsQuery(c, b, ip + 8, n, t);
                else if (!out && sp == 53) dnsReply(c, b, ip + 8, n);
            }
        } catch (Throwable ignored) { }
    }

    private static void dnsQuery(Conn c, byte[] b, int d, int n, long t) {
        int fl = u16(b, d + 2);
        if ((fl & 0x8000) != 0 || u16(b, d + 4) < 1) return;
        String q = dns(b, d + 12, n);
        if (q == null) return;
        int p = skipName(b, d + 12, n);
        if (p + 4 > n) return;
        Dns e = new Dns();
        e.t = t; e.name = q; e.id = u16(b, d); e.qtype = u16(b, p); e.conn = c;
        synchronized (DNSLOG) {
            DNSLOG.addLast(e);
            if (DNSLOG.size() > 600) DNSLOG.removeFirst();
        }
    }

    private static void dnsReply(Conn c, byte[] b, int d, int n) {
        int fl = u16(b, d + 2);
        if ((fl & 0x8000) == 0) return;
        int id = u16(b, d), qd = u16(b, d + 4), an = u16(b, d + 6);
        String q = qd > 0 ? dns(b, d + 12, n) : null;
        int p = d + 12;
        for (int i = 0; i < qd && p < n; i++) p = skipName(b, p, n) + 4;
        StringBuilder ans = new StringBuilder();
        for (int i = 0; i < an && p + 10 <= n; i++) {
            p = skipName(b, p, n);
            if (p + 10 > n) break;
            int ty = u16(b, p), rl = u16(b, p + 8);
            p += 10;
            if (p + rl > n) break;
            String addr = null;
            if (ty == 1 && rl == 4) addr = a4(b, p);
            else if (ty == 28 && rl == 16) addr = a6(b, p);
            if (addr != null) {
                if (q != null) IPNAME.put(addr, q);
                if (ans.length() < 120) {
                    if (ans.length() > 0) ans.append(", ");
                    ans.append(addr);
                }
            }
            p += rl;
        }
        synchronized (DNSLOG) {
            Iterator<Dns> it = DNSLOG.descendingIterator();
            int k = 0;
            while (it.hasNext() && k++ < 300) {
                Dns e = it.next();
                if (e.id == id && e.conn == c && e.rcode < 0) {
                    e.rcode = fl & 15;
                    e.ans = ans.toString();
                    break;
                }
            }
        }
    }

    static Set<String> keysOf(Collection<Conn> cs) {
        HashSet<String> k = new HashSet<>();
        for (Conn c : cs) {
            k.add(c.proto + "|" + c.laddr + ":" + c.lport + ">" + c.raddr + ":" + c.rport);
            k.add(c.proto + "|" + c.raddr + ":" + c.rport + ">" + c.laddr + ":" + c.lport);
        }
        return k;
    }

    private static String flowKey(byte[] b, int n) {
        if (n < 20) return null;
        int v = (b[0] >> 4) & 15, ip, pr;
        String sa, da;
        if (v == 4) { ip = (b[0] & 15) * 4; pr = b[9] & 255; sa = a4(b, 12); da = a4(b, 16); }
        else if (v == 6 && n >= 44) { ip = 40; pr = b[6] & 255; sa = a6(b, 8); da = a6(b, 24); }
        else return null;
        int sp = 0, dp = 0;
        if ((pr == 6 || pr == 17) && n >= ip + 4) { sp = u16(b, ip); dp = u16(b, ip + 2); }
        return pr + "|" + sa + ":" + sp + ">" + da + ":" + dp;
    }

    /** Eslesen paket sayisini dondurur, -1: kayit yok. */
    static long exportFiltered(OutputStream out, Set<String> keys) throws IOException {
        long len;
        synchronized (LK) {
            if (pw == null) return -1;
            pw.flush();
            len = pcapBytes;
        }
        File f = new File(dir, "capture.pcap");
        if (!f.exists() || len <= 24) return -1;
        long pos = 24, cnt = 0;
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 65536));
        try {
            byte[] gh = new byte[24];
            in.readFully(gh);
            out.write(gh);
            byte[] ph = new byte[16];
            while (pos + 16 <= len) {
                in.readFully(ph);
                int incl = (int) rd32(ph, 8, true);
                if (incl < 0 || incl > 262144 || pos + 16 + incl > len) break;
                byte[] d = new byte[incl];
                in.readFully(d);
                pos += 16 + incl;
                String k = flowKey(d, incl);
                if (k != null && keys.contains(k)) { out.write(ph); out.write(d); cnt++; }
            }
        } finally { in.close(); }
        out.flush();
        return cnt;
    }

    // ---------- kullanim gecmisi, canli bildirim metni, pcapng ----------
    private static android.content.Context appCtx;
    private static long ltT, ltI, ltO;
    private static String ltS = "";

    private static String spd(double kb) {
        return kb < 1024 ? String.format(Locale.US, "%.0f KB/s", kb) : String.format(Locale.US, "%.1f MB/s", kb / 1024);
    }

    private static String sizeStr(long b) {
        return b < 1048576 ? (b / 1024) + " KB" : b < 1073741824L
                ? String.format(Locale.US, "%.1f MB", b / 1048576.0) : String.format(Locale.US, "%.2f GB", b / 1073741824.0);
    }

    static synchronized String liveText(String fb) {
        long now = System.currentTimeMillis();
        if (ltT == 0) { ltT = now; ltI = totIn; ltO = totOut; return fb; }
        if (now - ltT >= 1500) {
            double dt = (now - ltT) / 1000.0;
            double dn = (totIn - ltI) / 1024.0 / dt, up = (totOut - ltO) / 1024.0 / dt;
            ltT = now; ltI = totIn; ltO = totOut;
            ltS = "↓ " + spd(dn) + "   ↑ " + spd(up) + "   ·   " + sizeStr(totIn + totOut);
        }
        return ltS.length() == 0 ? fb : ltS;
    }

    static String pkgOf(int uid) {
        if (uid < 0) return "?";
        try {
            String[] p = appCtx.getPackageManager().getPackagesForUid(uid);
            if (p != null && p.length > 0) return p[0];
        } catch (Throwable ignored) { }
        return "uid:" + uid;
    }

    static synchronized void saveUsage() {
        try {
            if (appCtx == null) return;
            long now = System.currentTimeMillis();
            String day = new java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
            HashMap<String, long[]> add = new HashMap<>();
            for (Conn c : TABLE.values()) {
                if (c.uid == -2 && now - c.first < 5000) continue;
                long du = c.bOut - c.sUp, dd = c.bIn - c.sDown;
                boolean nw = !c.sCnt;
                if (du == 0 && dd == 0 && !nw) continue;
                String pk = pkgOf(c.uid);
                long[] a = add.get(pk);
                if (a == null) { a = new long[3]; add.put(pk, a); }
                a[0] += du; a[1] += dd; if (nw) a[2]++;
                c.sUp = c.bOut; c.sDown = c.bIn; c.sCnt = true;
            }
            if (add.isEmpty()) return;
            android.content.SharedPreferences u = appCtx.getSharedPreferences("vaxo_usage", 0);
            android.content.SharedPreferences.Editor e = u.edit();
            for (Map.Entry<String, long[]> en : add.entrySet()) {
                String k = day + "|" + en.getKey();
                String[] p = u.getString(k, "0,0,0").split(",");
                long[] v = en.getValue();
                e.putString(k, (Long.parseLong(p[0]) + v[0]) + "," + (Long.parseLong(p[1]) + v[1]) + "," + (Long.parseLong(p[2]) + v[2]));
            }
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, -30);
            String cut = new java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(cal.getTime());
            for (String k : u.getAll().keySet()) if (k.length() > 8 && k.substring(0, 8).compareTo(cut) < 0) e.remove(k);
            e.apply();
        } catch (Throwable ignored) { }
    }

    private static int linkSkip(int link) {
        switch (link) {
            case 1: return 14;
            case 0: return 4;
            case 113: return 16;
            case 276: return 20;
            case 101: case 12: case 14: return 0;
            default: return -1;
        }
    }

    private static int rd16(byte[] b, int i, boolean le) {
        return le ? ((b[i] & 255) | ((b[i + 1] & 255) << 8)) : (((b[i] & 255) << 8) | (b[i + 1] & 255));
    }

    private static boolean feed(int link, int skip, byte[] d, int incl, int orig, long t) {
        int off = skip;
        if (link == 1 && incl >= 14) {
            int et = ((d[12] & 255) << 8) | (d[13] & 255);
            if (et == 0x8100 && incl >= 18) { off = 18; et = ((d[16] & 255) << 8) | (d[17] & 255); }
            if (et != 0x0800 && et != 0x86dd) return false;
        }
        int len = incl - off;
        if (len < 20) return false;
        byte[] ip = off == 0 ? d : Arrays.copyOfRange(d, off, incl);
        parse(IMPORT, guessOut(ip, len), ip, len, Math.max(len, orig - off), t, false);
        return true;
    }

    private static int importNg(DataInputStream in, byte[] gh) throws IOException {
        boolean le = (gh[8] & 255) == 0x4D;
        long shb = rd32(gh, 4, le);
        if (shb < 24 || shb > (1 << 20)) return -1;
        in.readFully(new byte[(int) shb - 24]);
        IMPORT.clear();
        ArrayList<double[]> ifs = new ArrayList<>();
        byte[] hd = new byte[8], tail = new byte[4];
        int cnt = 0;
        try {
            while (true) {
                in.readFully(hd);
                long type, len;
                byte[] body;
                if ((hd[0] & 255) == 0x0a && (hd[1] & 255) == 0x0d && (hd[2] & 255) == 0x0d && (hd[3] & 255) == 0x0a) {
                    byte[] bm = new byte[4];
                    in.readFully(bm);
                    le = (bm[0] & 255) == 0x4D;
                    len = rd32(hd, 4, le);
                    if (len < 16 || len > (1 << 20)) break;
                    in.readFully(new byte[(int) len - 16]);
                    in.readFully(tail);
                    ifs.clear();
                    continue;
                }
                type = rd32(hd, 0, le);
                len = rd32(hd, 4, le);
                if (len < 12 || len > 16L * 1024 * 1024) break;
                int bl = (int) len - 12;
                body = new byte[bl];
                in.readFully(body);
                in.readFully(tail);
                if (type == 1 && bl >= 8) {
                    double res = 1e6;
                    int o = 8;
                    while (o + 4 <= bl) {
                        int code = rd16(body, o, le), ol = rd16(body, o + 2, le);
                        o += 4;
                        if (code == 0) break;
                        if (code == 9 && ol >= 1 && o < bl) {
                            int v = body[o] & 255;
                            res = (v & 0x80) == 0 ? Math.pow(10, v) : Math.pow(2, v & 0x7f);
                        }
                        o += (ol + 3) & ~3;
                    }
                    ifs.add(new double[]{rd16(body, 0, le), res});
                } else if (type == 6 && bl >= 20) {
                    int ifid = (int) rd32(body, 0, le);
                    long ts = (rd32(body, 4, le) << 32) | rd32(body, 8, le);
                    int cap = (int) rd32(body, 12, le), orig = (int) rd32(body, 16, le);
                    if (ifid >= ifs.size() || cap < 0 || 20 + cap > bl) continue;
                    int link = (int) ifs.get(ifid)[0], skip = linkSkip(link);
                    if (skip < 0) continue;
                    long t = (long) (ts / ifs.get(ifid)[1] * 1000.0);
                    try { if (feed(link, skip, Arrays.copyOfRange(body, 20, 20 + cap), cap, orig, t)) cnt++; } catch (Throwable ignored) { }
                } else if (type == 3 && bl >= 4 && !ifs.isEmpty()) {
                    int orig = (int) rd32(body, 0, le), cap = Math.min(orig, bl - 4);
                    int link = (int) ifs.get(0)[0], skip = linkSkip(link);
                    if (skip < 0 || cap < 0) continue;
                    try { if (feed(link, skip, Arrays.copyOfRange(body, 4, 4 + cap), cap, orig, System.currentTimeMillis())) cnt++; } catch (Throwable ignored) { }
                }
            }
        } catch (EOFException ignored) { }
        return cnt;
    }

    static void init(android.content.Context x) {
        cm = (android.net.ConnectivityManager) x.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        dir = x.getFilesDir();
        appCtx = x.getApplicationContext();
        pcapOn = x.getSharedPreferences("vaxodpi", 0).getBoolean("cap_on", true);
    }

    static String summary() {
        int named = 0;
        for (Conn c : TABLE.values()) if (c.name != null) named++;
        return "bag=" + TABLE.size() + " ad=" + named + " son=" + lastName;
    }

    static String pcapInfo() {
        long sz = 0;
        synchronized (LK) { sz = pcapBytes; }
        return pcapPkts + " paket · " + (sz < 1048576 ? (sz / 1024) + " KB" : String.format(Locale.US, "%.1f MB", sz / 1048576.0));
    }

    static void clearAll() {
        TABLE.clear();
        synchronized (DNSLOG) { DNSLOG.clear(); }
        IPNAME.clear();
        synchronized (LK) {
            closePcap();
            if (pf != null) pf.delete();
            pcapPkts = 0;
            pcapBytes = 0;
        }
    }

    // ---------- PCAP yazma ----------
    private static void le32(OutputStream o, long v) throws IOException {
        o.write((int) (v & 255)); o.write((int) ((v >> 8) & 255));
        o.write((int) ((v >> 16) & 255)); o.write((int) ((v >> 24) & 255));
    }

    private static void le16(OutputStream o, int v) throws IOException {
        o.write(v & 255); o.write((v >> 8) & 255);
    }

    private static void closePcap() {
        try { if (pw != null) pw.close(); } catch (Throwable ignored) { }
        pw = null;
    }

    private static void openPcap() throws IOException {
        closePcap();
        pf = new File(dir, "capture.pcap");
        pw = new BufferedOutputStream(new FileOutputStream(pf, false), 65536);
        le32(pw, 0xa1b2c3d4L); le16(pw, 2); le16(pw, 4);
        le32(pw, 0); le32(pw, 0); le32(pw, 65535); le32(pw, 101);
        pcapBytes = 24;
        pcapPkts = 0;
    }

    private static void writePcap(byte[] b, int n, long t) {
        if (!pcapOn || dir == null) return;
        synchronized (LK) {
            try {
                if (pw == null || pcapBytes > 64L * 1024 * 1024) openPcap();
                le32(pw, t / 1000); le32(pw, (t % 1000) * 1000); le32(pw, n); le32(pw, n);
                pw.write(b, 0, n);
                pcapBytes += 16 + n;
                pcapPkts++;
                if (t - lastFlush > 1000) { pw.flush(); lastFlush = t; }
            } catch (Throwable e) {
                closePcap();
            }
        }
    }

    static long exportTo(OutputStream out) throws IOException {
        long len;
        synchronized (LK) {
            if (pw == null) return -1;
            pw.flush();
            len = pcapBytes;
        }
        File f = new File(dir, "capture.pcap");
        if (!f.exists() || len <= 24) return -1;
        long n = 0;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int r;
            while (n < len && (r = in.read(buf, 0, (int) Math.min(buf.length, len - n))) > 0) {
                out.write(buf, 0, r);
                n += r;
            }
        }
        out.flush();
        return n;
    }

    // ---------- PCAP okuma ----------
    private static long rd32(byte[] b, int i, boolean le) {
        if (le) return (b[i] & 255L) | ((b[i + 1] & 255L) << 8) | ((b[i + 2] & 255L) << 16) | ((b[i + 3] & 255L) << 24);
        return (b[i + 3] & 255L) | ((b[i + 2] & 255L) << 8) | ((b[i + 1] & 255L) << 16) | ((b[i] & 255L) << 24);
    }

    /** Paket sayısı döner. -1: pcap değil, -2: bağlantı türü desteklenmiyor, -3: pcapng. */
    static int importFrom(InputStream in0) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(in0, 65536));
        byte[] gh = new byte[24];
        in.readFully(gh);
        long m = rd32(gh, 0, false);
        boolean le, nano = false;
        if (m == 0xa1b2c3d4L) le = false;
        else if (m == 0xd4c3b2a1L) le = true;
        else if (m == 0xa1b23c4dL) { le = false; nano = true; }
        else if (m == 0x4d3cb2a1L) { le = true; nano = true; }
        else if (m == 0x0a0d0d0aL) return importNg(in, gh);
        else return -1;
        int link = (int) rd32(gh, 20, le), skip;
        switch (link) {
            case 1: skip = 14; break;
            case 0: skip = 4; break;
            case 113: skip = 16; break;
            case 101: case 12: case 14: skip = 0; break;
            default: return -2;
        }
        IMPORT.clear();
        byte[] ph = new byte[16];
        int cnt = 0;
        while (true) {
            try { in.readFully(ph); } catch (EOFException e) { break; }
            long sec = rd32(ph, 0, le), sub = rd32(ph, 4, le);
            int incl = (int) rd32(ph, 8, le), orig = (int) rd32(ph, 12, le);
            if (incl < 0 || incl > 262144) break;
            byte[] d = new byte[incl];
            try { in.readFully(d); } catch (EOFException e) { break; }
            long t = sec * 1000 + (nano ? sub / 1000000 : sub / 1000);
            int off = skip;
            if (link == 1 && incl >= 14) {
                int et = ((d[12] & 255) << 8) | (d[13] & 255);
                if (et == 0x8100 && incl >= 18) { off = 18; et = ((d[16] & 255) << 8) | (d[17] & 255); }
                if (et != 0x0800 && et != 0x86dd) continue;
            }
            int len = incl - off;
            if (len < 20) continue;
            byte[] ip = off == 0 ? d : Arrays.copyOfRange(d, off, incl);
            try {
                parse(IMPORT, guessOut(ip, len), ip, len, Math.max(len, orig - off), t, false);
                cnt++;
            } catch (Throwable ignored) { }
        }
        return cnt;
    }

    private static boolean local(String a) {
        if (a.startsWith("10.") || a.startsWith("192.168.") || a.startsWith("198.18.") || a.startsWith("127.")
                || a.startsWith("fe80") || a.startsWith("fc") || a.startsWith("fd")) return true;
        if (a.startsWith("172.")) {
            try {
                int s = Integer.parseInt(a.substring(4, a.indexOf('.', 4)));
                return s >= 16 && s <= 31;
            } catch (Throwable ignored) { }
        }
        return false;
    }

    private static boolean guessOut(byte[] b, int n) {
        int v = (b[0] >> 4) & 15;
        String sa, da;
        if (v == 4) { sa = a4(b, 12); da = a4(b, 16); }
        else if (v == 6 && n >= 40) { sa = a6(b, 8); da = a6(b, 24); }
        else return true;
        boolean la = local(sa), lb = local(da);
        return la != lb ? la : sa.compareTo(da) <= 0;
    }

    // ---------- Canlı paket ----------
    static void onPacket(boolean out, byte[] b, int n) {
        try {
            long t = System.currentTimeMillis();
            lastPkt = t;
            if (out) totOut += n; else totIn += n;
            writePcap(b, n, t);
            parse(TABLE, out, b, n, n, t, true);
        } catch (Throwable ignored) { }
    }

    private static void resolve(final Conn c) {
        EX.execute(new Runnable() {
            @Override public void run() {
                int u = -1;
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 29 && cm != null) {
                        u = cm.getConnectionOwnerUid(c.proto,
                                new java.net.InetSocketAddress(java.net.InetAddress.getByName(c.laddr), c.lport),
                                new java.net.InetSocketAddress(java.net.InetAddress.getByName(c.raddr), c.rport));
                    }
                } catch (Throwable ignored) { }
                c.uid = u;
            }
        });
    }

    private static int u16(byte[] b, int i) { return ((b[i] & 255) << 8) | (b[i + 1] & 255); }

    private static long u32(byte[] b, int i) {
        return ((b[i] & 255L) << 24) | ((b[i + 1] & 255L) << 16) | ((b[i + 2] & 255L) << 8) | (b[i + 3] & 255L);
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

    private static void parse(Map<String, Conn> tbl, boolean out, byte[] b, int n, int orig, long t, boolean live) {
        if (n < 20) return;
        int v = (b[0] >> 4) & 15, ip, pr;
        String sa, da;
        if (v == 4) {
            ip = (b[0] & 15) * 4; pr = b[9] & 255; sa = a4(b, 12); da = a4(b, 16);
        } else if (v == 6) {
            if (n < 44) return;
            ip = 40; pr = b[6] & 255; sa = a6(b, 8); da = a6(b, 24);
        } else return;
        int sp = 0, dp = 0;
        if ((pr == 6 || pr == 17) && n >= ip + 4) { sp = u16(b, ip); dp = u16(b, ip + 2); }
        String key = out ? pr + "|" + sa + ":" + sp + ">" + da + ":" + dp
                         : pr + "|" + da + ":" + dp + ">" + sa + ":" + sp;
        Conn c = tbl.get(key);
        if (c == null) {
            c = new Conn();
            c.proto = pr;
            c.laddr = out ? sa : da; c.lport = out ? sp : dp;
            c.raddr = out ? da : sa; c.rport = out ? dp : sp;
            c.first = t;
            tbl.put(key, c);
            if (live && out && (pr == 6 || pr == 17)) resolve(c);
        }
        c.last = t;
        if (out) { c.pOut++; c.bOut += orig; } else { c.pIn++; c.bIn += orig; }
        if (live) hook(c, out, b, n, ip, pr, sp, dp, t);
        Pkt p = new Pkt();
        p.t = t; p.out = out; p.orig = orig;
        p.data = Arrays.copyOf(b, Math.min(n, CAP));
        synchronized (c.pkts) {
            c.pkts.addLast(p);
            if (c.pkts.size() > KEEP) c.pkts.removeFirst();
        }
        if (!out || c.name != null) return;
        if (pr == 17 && dp == 53 && n > ip + 8 + 12) {
            String d = dns(b, ip + 8 + 12, n);
            if (d != null) { c.name = d; if (live) lastName = d; }
        } else if (pr == 6 && n >= ip + 20) {
            int off = ip + ((b[ip + 12] >> 4) & 15) * 4;
            if (n > off + 44 && (b[off] & 255) == 0x16) {
                String s = sni(b, off, n);
                if (s != null) { c.name = s; if (live) { lastName = s; IPNAME.put(c.raddr, s); } }
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
                int nl = u16(b, p + 3), st = p + 5;
                if (st + nl > n) return null;
                return new String(b, st, nl, StandardCharsets.US_ASCII);
            }
            p += l;
        }
        return null;
    }

    // ---------- Paket ayrıntısı ve hex ----------
    private static String pn(int p) {
        switch (p) {
            case 1: return "ICMP"; case 2: return "IGMP"; case 6: return "TCP"; case 17: return "UDP";
            case 47: return "GRE"; case 50: return "ESP"; case 58: return "ICMPv6"; default: return "IP/" + p;
        }
    }

    private static String flags(int f) {
        String[] nm = {"FIN", "SYN", "RST", "PSH", "ACK", "URG", "ECE", "CWR"};
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 8; i++) if ((f & (1 << i)) != 0) { if (s.length() > 0) s.append(','); s.append(nm[i]); }
        return s.length() > 0 ? s.toString() : "-";
    }

    /** {kısa özet, ayrıntılı metin} */
    static String[] decode(Pkt p) {
        byte[] b = p.data;
        int n = b.length;
        StringBuilder s = new StringBuilder();
        String br = "Paket";
        try {
            int v = (b[0] >> 4) & 15, ip, pr;
            String sa, da;
            if (v == 4) {
                ip = (b[0] & 15) * 4; pr = b[9] & 255; sa = a4(b, 12); da = a4(b, 16);
                s.append("IPv4\n  Başlık      ").append(ip).append(" B\n  Toplam      ").append(u16(b, 2))
                 .append(" B\n  Tanıtıcı    0x").append(Integer.toHexString(u16(b, 4)))
                 .append("\n  Bayrak      ").append((b[6] & 0x40) != 0 ? "DF " : "").append((b[6] & 0x20) != 0 ? "MF" : "")
                 .append("\n  TTL         ").append(b[8] & 255)
                 .append("\n  Protokol    ").append(pr).append(" (").append(pn(pr)).append(")")
                 .append("\n  Sağlama     0x").append(Integer.toHexString(u16(b, 10)));
            } else if (v == 6) {
                ip = 40; pr = b[6] & 255; sa = a6(b, 8); da = a6(b, 24);
                s.append("IPv6\n  Yük         ").append(u16(b, 4)).append(" B\n  Sonraki     ").append(pr)
                 .append(" (").append(pn(pr)).append(")\n  Hop limit   ").append(b[7] & 255);
            } else return new String[]{"?", "Tanınmayan paket (sürüm " + v + ")"};
            s.append("\n  Kaynak      ").append(sa).append("\n  Hedef       ").append(da).append("\n\n");
            br = pn(pr) + " " + sa + "→" + da;
            if (pr == 6 && n >= ip + 20) {
                int sp = u16(b, ip), dp = u16(b, ip + 2), off = ((b[ip + 12] >> 4) & 15) * 4, fl = b[ip + 13] & 255;
                int pay = Math.max(0, p.orig - ip - off), ao = ip + off;
                String f = flags(fl);
                s.append("TCP\n  Kaynak port ").append(sp).append("\n  Hedef port  ").append(dp)
                 .append("\n  Seq         ").append(u32(b, ip + 4)).append("\n  Ack         ").append(u32(b, ip + 8))
                 .append("\n  Başlık      ").append(off).append(" B\n  Bayraklar   ").append(f)
                 .append("\n  Pencere     ").append(u16(b, ip + 14))
                 .append("\n  Sağlama     0x").append(Integer.toHexString(u16(b, ip + 16)))
                 .append("\n  Yük         ").append(pay).append(" B\n");
                br = "TCP " + sp + "→" + dp + " [" + f + "] " + pay + " B";
                if (pay > 5 && n > ao + 5) {
                    int rt = b[ao] & 255;
                    String rn = rt == 0x16 ? "Handshake" : rt == 0x17 ? "Uygulama verisi (şifreli)" : rt == 0x15 ? "Uyarı" : rt == 0x14 ? "ChangeCipherSpec" : null;
                    if (rn != null && (b[ao + 1] & 255) == 3) {
                        s.append("\nTLS\n  Kayıt       ").append(rn).append('\n');
                        if (rt == 0x16) {
                            int ht = b[ao + 5] & 255;
                            String hn = ht == 1 ? "ClientHello" : ht == 2 ? "ServerHello" : ht == 11 ? "Certificate"
                                    : ht == 4 ? "NewSessionTicket" : ht == 20 ? "Finished" : "tür " + ht;
                            s.append("  Handshake   ").append(hn).append('\n');
                            br += " TLS " + hn;
                            String sn = n > ao + 44 ? sni(b, ao, n) : null;
                            if (sn != null) { s.append("  SNI         ").append(sn).append('\n'); br += " " + sn; }
                        } else br += " TLS";
                    } else if (n > ao + 8) {
                        String h = new String(b, ao, Math.min(8, n - ao), StandardCharsets.US_ASCII);
                        if (h.startsWith("GET ") || h.startsWith("POST ") || h.startsWith("HEAD ") || h.startsWith("PUT ") || h.startsWith("HTTP/")) {
                            int e = ao;
                            while (e < n && b[e] != '\r' && b[e] != '\n') e++;
                            String line = new String(b, ao, e - ao, StandardCharsets.US_ASCII);
                            s.append("\nHTTP\n  ").append(line).append('\n');
                            br += " " + line;
                        }
                    }
                }
            } else if (pr == 17 && n >= ip + 8) {
                int sp = u16(b, ip), dp = u16(b, ip + 2);
                int pay = Math.max(0, p.orig - ip - 8);
                s.append("UDP\n  Kaynak port ").append(sp).append("\n  Hedef port  ").append(dp)
                 .append("\n  Uzunluk     ").append(u16(b, ip + 4))
                 .append("\n  Sağlama     0x").append(Integer.toHexString(u16(b, ip + 6)))
                 .append("\n  Yük         ").append(pay).append(" B\n");
                br = "UDP " + sp + "→" + dp + " " + pay + " B";
                if ((sp == 53 || dp == 53) && n >= ip + 8 + 12) {
                    int fl = u16(b, ip + 10);
                    boolean resp = (fl & 0x8000) != 0;
                    String q = dns(b, ip + 8 + 12, n);
                    s.append("\nDNS\n  Tür         ").append(resp ? "Yanıt" : "Sorgu")
                     .append("\n  Soru        ").append(u16(b, ip + 12)).append("\n  Yanıt       ").append(u16(b, ip + 14));
                    if (resp) s.append("\n  Rcode       ").append(fl & 15);
                    if (q != null) s.append("\n  Ad          ").append(q);
                    s.append('\n');
                    br = "DNS " + (resp ? "yanıt " : "sorgu ") + (q != null ? q : "");
                } else if (sp == 443 || dp == 443) {
                    s.append("\nQUIC / HTTP3 olabilir, içerik şifreli.\n");
                    br += " QUIC?";
                }
            } else if ((pr == 1 || pr == 58) && n >= ip + 4) {
                s.append(pn(pr)).append("\n  Tip         ").append(b[ip] & 255).append("\n  Kod         ").append(b[ip + 1] & 255).append('\n');
                br = pn(pr) + " tip " + (b[ip] & 255) + " " + sa + "→" + da;
            }
        } catch (Throwable e) {
            s.append("\n(ayrıştırma hatası)");
        }
        return new String[]{br, s.toString()};
    }

    static String hexdump(byte[] d) {
        final String H = "0123456789abcdef";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < d.length; i += 8) {
            s.append(String.format(Locale.US, "%04x  ", i));
            for (int j = 0; j < 8; j++) {
                if (i + j < d.length) {
                    int x = d[i + j] & 255;
                    s.append(H.charAt(x >> 4)).append(H.charAt(x & 15)).append(' ');
                } else s.append("   ");
            }
            s.append(' ');
            for (int j = 0; j < 8 && i + j < d.length; j++) {
                int x = d[i + j] & 255;
                s.append(x >= 32 && x < 127 ? (char) x : '.');
            }
            s.append('\n');
        }
        return s.toString();
    }
}
