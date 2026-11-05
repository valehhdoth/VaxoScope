package xyz.valeh.vaxodpi.scan;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Önceki taramalarda görülen cihazların ağ başına kaydı (çevrimdışı karşılaştırma için). */
public final class KnownStore {
    private KnownStore() { }

    public static final class Rec {
        public String ip, host, mdns, mac, macSrc, basis = "-";
        public int type;
        public long first, last;
        public final List<Integer> ports = new ArrayList<Integer>();
    }

    private static final String P = "vaxo_scan";
    private static final long KEEP_MS = 90L * 24 * 3600 * 1000;

    public static Map<String, Rec> load(Context c, String key) {
        Map<String, Rec> m = new LinkedHashMap<String, Rec>();
        try {
            String s = c.getSharedPreferences(P, 0).getString("net:" + key, null);
            if (s == null) return m;
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Rec r = new Rec();
                r.ip = o.getString("ip");
                r.host = o.has("host") ? o.getString("host") : null;
                r.mdns = o.has("mdns") ? o.getString("mdns") : null;
                r.mac = o.has("mac") ? o.getString("mac") : null;
                r.macSrc = o.has("macSrc") ? o.getString("macSrc") : null;
                r.basis = o.has("basis") ? o.getString("basis") : "-";
                r.type = o.optInt("type", 0);
                r.first = o.optLong("first", 0);
                r.last = o.optLong("last", 0);
                JSONArray ps = o.optJSONArray("ports");
                if (ps != null) for (int j = 0; j < ps.length(); j++) r.ports.add(ps.getInt(j));
                m.put(r.ip, r);
            }
        } catch (Throwable ignored) { }
        return m;
    }

    public static void save(Context c, String key, Collection<Rec> recs) {
        try {
            long cut = System.currentTimeMillis() - KEEP_MS;
            List<Rec> l = new ArrayList<Rec>();
            for (Rec r : recs) if (r.last == 0 || r.last >= cut) l.add(r);
            Collections.sort(l, new Comparator<Rec>() {
                @Override public int compare(Rec a, Rec b) { return Long.compare(b.last, a.last); }
            });
            if (l.size() > 250) l = new ArrayList<Rec>(l.subList(0, 250));
            JSONArray a = new JSONArray();
            for (Rec r : l) {
                JSONObject o = new JSONObject();
                o.put("ip", r.ip);
                if (r.host != null) o.put("host", r.host);
                if (r.mdns != null) o.put("mdns", r.mdns);
                if (r.mac != null) o.put("mac", r.mac);
                if (r.macSrc != null) o.put("macSrc", r.macSrc);
                o.put("basis", r.basis == null ? "-" : r.basis);
                o.put("type", r.type);
                o.put("first", r.first);
                o.put("last", r.last);
                JSONArray ps = new JSONArray();
                for (int p : r.ports) ps.put(p);
                o.put("ports", ps);
                a.put(o);
            }
            c.getSharedPreferences(P, 0).edit().putString("net:" + key, a.toString()).apply();
        } catch (Throwable ignored) { }
    }

    public static void clearAll(Context c) {
        try { c.getSharedPreferences(P, 0).edit().clear().apply(); } catch (Throwable ignored) { }
    }
}
