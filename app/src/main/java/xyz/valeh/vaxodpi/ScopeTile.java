package xyz.valeh.vaxodpi;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class ScopeTile extends TileService {
    private boolean run() { return System.currentTimeMillis() - PacketStats.lastPkt < 8000; }

    private void upd() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(run() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("VaxoScope");
        t.updateTile();
    }

    @Override public void onStartListening() { upd(); }

    @Override public void onClick() {
        if (run()) {
            Intent s = new Intent(this, VaxoVpnService.class);
            s.setAction("xyz.valeh.vaxodpi.STOP");
            startService(s);
        } else if (VpnService.prepare(this) != null) {
            Intent i = new Intent(this, ConnectionsActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE));
            } else {
                startActivityAndCollapse(i);
            }
        } else {
            Intent s = new Intent(this, VaxoVpnService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
        }
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(this::upd, 1500);
        h.postDelayed(this::upd, 4000);
    }
}
