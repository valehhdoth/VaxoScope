package xyz.valeh.vaxodpi;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;
import java.util.Locale;

public class ScopeWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { push(c, m, ids); }

    static void refresh(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, ScopeWidget.class));
            if (ids != null && ids.length > 0) push(c, m, ids);
        } catch (Throwable ignored) { }
    }

    static void refreshLater(final Context c) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() { refresh(c); }
        }, 1500);
    }

    private static int id(Context c, String n, String t) {
        return c.getResources().getIdentifier(n, t, c.getPackageName());
    }

    private static String t(int l, String tr, String en, String ru) { return l == 1 ? en : l == 2 ? ru : tr; }

    private static String size(long b) {
        if (b < 1048576) return (b / 1024) + " KB";
        if (b < 1073741824L) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
    }

    static void push(Context c, AppWidgetManager m, int[] ids) {
        try {
            int lay = id(c, "widget_scope", "layout");
            if (lay == 0) return;
            boolean run = System.currentTimeMillis() - PacketStats.lastPkt < 8000;
            SharedPreferences p = c.getSharedPreferences("vaxodpi", 0);
            int lang;
            if (p.contains("lang")) lang = p.getInt("lang", 0);
            else { String l = Locale.getDefault().getLanguage(); lang = l.equals("tr") ? 0 : l.equals("ru") ? 2 : 1; }
            RemoteViews rv = new RemoteViews(c.getPackageName(), lay);
            int st = id(c, "w_state", "id");
            rv.setTextViewText(st, run ? t(lang, "● Açık", "● On", "● Вкл") : t(lang, "○ Kapalı", "○ Off", "○ Выкл"));
            rv.setTextColor(st, run ? Color.WHITE : 0xFF8A8A8A);
            String info = run
                    ? PacketStats.TABLE.size() + t(lang, " bağlantı", " connections", " соед.") + " · " + size(PacketStats.totIn + PacketStats.totOut)
                    : t(lang, "Trafik izlenmiyor", "Not monitoring", "Не отслеживается");
            rv.setTextViewText(id(c, "w_info", "id"), info);
            rv.setTextViewText(id(c, "w_btn", "id"), run ? t(lang, "Durdur", "Stop", "Стоп") : t(lang, "Aç", "Start", "Старт"));
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent open = PendingIntent.getActivity(c, 1, new Intent(c, ConnectionsActivity.class), fl);
            PendingIntent tog;
            if (run) {
                Intent s = new Intent(c, VaxoVpnService.class);
                s.setAction("xyz.valeh.vaxodpi.STOP");
                tog = PendingIntent.getService(c, 2, s, fl);
            } else if (VpnService.prepare(c) != null) {
                tog = open;
            } else {
                Intent s = new Intent(c, VaxoVpnService.class);
                tog = Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(c, 3, s, fl) : PendingIntent.getService(c, 3, s, fl);
            }
            rv.setOnClickPendingIntent(id(c, "w_root", "id"), open);
            rv.setOnClickPendingIntent(id(c, "w_btn", "id"), tog);
            m.updateAppWidget(ids, rv);
        } catch (Throwable ignored) { }
    }
}
