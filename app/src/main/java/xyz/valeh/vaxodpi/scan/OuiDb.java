package xyz.valeh.vaxodpi.scan;

import android.content.Context;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;

/** MAC ön ekinden üretici adı (IEEE OUI kaydı, çevrimdışı). Varlık dosyası yoksa ready=false kalır. */
public final class OuiDb {
    private OuiDb() { }

    public static volatile boolean ready;
    public static volatile int count;
    private static int[] keys, offs;
    private static byte[] blob;
    private static boolean started;

    public static synchronized void init(Context x) {
        if (started) return;
        started = true;
        final Context app = x.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override public void run() { load(app); }
        }, "VaxoScope-Oui");
        t.setDaemon(true);
        t.start();
    }

    private static void load(Context x) {
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(x.getAssets().open("oui.bin"), 1 << 16));
            byte[] mg = new byte[4];
            in.readFully(mg);
            if (!"OUI1".equals(new String(mg, StandardCharsets.US_ASCII))) { in.close(); return; }
            int n = in.readInt();
            int[] k = new int[n], o = new int[n];
            for (int i = 0; i < n; i++) k[i] = in.readInt();
            for (int i = 0; i < n; i++) o[i] = in.readInt();
            int bl = in.readInt();
            byte[] b = new byte[bl];
            in.readFully(b);
            in.close();
            keys = k;
            offs = o;
            blob = b;
            count = n;
            ready = true;
        } catch (Throwable ignored) { }
    }

    /** İlk oktetin "yerel yönetilen" biti: rastgele/gizlilik MAC'i. */
    public static boolean isRandom(String mac) {
        try { return (Integer.parseInt(mac.substring(0, 2), 16) & 2) != 0; }
        catch (Throwable e) { return false; }
    }

    public static String vendor(String mac) {
        if (!ready || mac == null || mac.length() < 8) return null;
        try {
            int key = (Integer.parseInt(mac.substring(0, 2), 16) << 16)
                    | (Integer.parseInt(mac.substring(3, 5), 16) << 8)
                    | Integer.parseInt(mac.substring(6, 8), 16);
            int lo = 0, hi = keys.length - 1;
            while (lo <= hi) {
                int m = (lo + hi) >>> 1;
                if (keys[m] == key) {
                    int end = m + 1 < offs.length ? offs[m + 1] : blob.length;
                    return new String(blob, offs[m], end - offs[m], StandardCharsets.UTF_8);
                }
                if (keys[m] < key) lo = m + 1; else hi = m - 1;
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
