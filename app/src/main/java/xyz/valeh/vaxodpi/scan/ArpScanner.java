package xyz.valeh.vaxodpi.scan;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class ArpScanner {
    private ArpScanner() { }

    /** ip -> mac. Android 10+ erişimi engellerse null döner. */
    public static Map<String, String> read() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/net/arp"));
            Map<String, String> m = new HashMap<String, String>();
            String line = r.readLine();
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 4) continue;
                String mac = p[3].toLowerCase(Locale.ROOT);
                if (p[2].equals("0x0") || mac.equals("00:00:00:00:00:00")) continue;
                m.put(p[0], mac);
            }
            return m;
        } catch (Throwable e) {
            return null;
        } finally {
            if (r != null) { try { r.close(); } catch (IOException ignored) { } }
        }
    }
}
