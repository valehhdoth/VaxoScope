package xyz.valeh.vaxodpi.scan;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import java.net.Inet4Address;
import java.net.InetAddress;

public final class NetworkInfoManager {
    private NetworkInfoManager() { }

    /** Bağlı Wi-Fi ağının bilgisini okur. VPN ağını atlar. Wi-Fi yoksa wifi=false döner. */
    public static NetInfo read(Context c) {
        NetInfo n = new NetInfo();
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network wifi = null;
            for (Network nw : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(nw);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { wifi = nw; break; }
            }
            if (wifi == null) return n;
            LinkProperties lp = cm.getLinkProperties(wifi);
            if (lp == null) return n;
            String ifn = lp.getInterfaceName();
            if (ifn != null) n.iface = ifn;
            for (LinkAddress la : lp.getLinkAddresses()) {
                InetAddress a = la.getAddress();
                if (!(a instanceof Inet4Address)) continue;
                byte[] b = a.getAddress();
                int ip = ((b[0] & 255) << 24) | ((b[1] & 255) << 16) | ((b[2] & 255) << 8) | (b[3] & 255);
                int pl = la.getPrefixLength();
                int mask = pl == 0 ? 0 : (-1 << (32 - pl));
                long ipL = ip & 0xFFFFFFFFL, maskL = mask & 0xFFFFFFFFL;
                long net = ipL & maskL, bc = net | (~maskL & 0xFFFFFFFFL);
                long first = net + 1, last = bc - 1;
                if (pl < 24) {
                    long n24 = ipL & 0xFFFFFF00L;
                    first = n24 + 1;
                    last = n24 + 254;
                    n.limited = true;
                }
                if (pl >= 31) { first = 1; last = 0; }
                n.ip = NetInfo.str(ipL);
                n.mask = NetInfo.str(maskL);
                n.prefix = pl;
                n.range = NetInfo.str(net) + "/" + pl;
                n.firstHost = first;
                n.lastHost = last;
                break;
            }
            for (RouteInfo r : lp.getRoutes()) {
                if (r.isDefaultRoute() && r.getGateway() instanceof Inet4Address) {
                    n.gateway = r.getGateway().getHostAddress();
                    break;
                }
            }
            for (InetAddress d : lp.getDnsServers()) n.dns.add(d.getHostAddress());
            n.network = wifi;
            n.wifi = !n.ip.equals("-");
        } catch (Throwable ignored) { }
        return n;
    }

    /** Wi-Fi değişimlerini izler. onChange herhangi bir iş parçacığından çağrılabilir. */
    public static ConnectivityManager.NetworkCallback watch(Context c, final Runnable onChange) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkRequest rq = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
            ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { onChange.run(); }
                @Override public void onLost(Network n) { onChange.run(); }
                @Override public void onLinkPropertiesChanged(Network n, LinkProperties lp) { onChange.run(); }
            };
            cm.registerNetworkCallback(rq, cb);
            return cb;
        } catch (Throwable e) {
            return null;
        }
    }

    public static void unwatch(Context c, ConnectivityManager.NetworkCallback cb) {
        try {
            ((ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(cb);
        } catch (Throwable ignored) { }
    }
}
