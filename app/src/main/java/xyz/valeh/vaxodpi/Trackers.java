package xyz.valeh.vaxodpi;

final class Trackers {
    private static final String[] D = {
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagmanager.com",
        "googletagservices.com", "google-analytics.com", "app-measurement.com", "firebaselogging.googleapis.com",
        "crashlytics.com", "admob.com", "adsense.com", "2mdn.net", "connect.facebook.net", "pixel.facebook.com",
        "an.facebook.com", "amazon-adsystem.com", "adnxs.com", "adcolony.com", "applovin.com", "applvn.com",
        "unityads.unity3d.com", "ads.unity3d.com", "ironsrc.com", "supersonicads.com", "vungle.com",
        "chartboost.com", "mopub.com", "inmobi.com", "pubmatic.com", "rubiconproject.com", "criteo.com",
        "criteo.net", "taboola.com", "outbrain.com", "moatads.com", "smaato.net", "startappservice.com",
        "tapjoy.com", "fyber.com", "bidswitch.net", "openx.net", "casalemedia.com", "adform.net", "adsrvr.org",
        "yieldmo.com", "teads.tv", "mintegral.com", "pangleglobal.com", "ads.tiktok.com", "analytics.tiktok.com",
        "appsflyer.com", "adjust.com", "adjust.io", "branch.io", "kochava.com", "singular.net", "mixpanel.com",
        "amplitude.com", "segment.io", "segment.com", "hotjar.com", "clarity.ms", "scorecardresearch.com",
        "quantserve.com", "bat.bing.com", "sentry.io", "datadoghq.com", "newrelic.com", "nr-data.net",
        "bugsnag.com", "flurry.com", "ads.linkedin.com", "px.ads.linkedin.com", "tr.snapchat.com",
        "sc-analytics.appspot.com", "ads.twitter.com", "analytics.twitter.com", "ads-twitter.com",
        "ads.pinterest.com", "ct.pinterest.com", "ads.yahoo.com", "analytics.yahoo.com", "mc.yandex.ru",
        "mc.yandex.com", "appmetrica.yandex.ru", "appmetrica.yandex.com", "yandexadexchange.net",
        "an.yandex.ru", "adfox.yandex.ru", "umeng.com"
    };
    private static final java.util.HashSet<String> S = new java.util.HashSet<>(java.util.Arrays.asList(D));

    static boolean host(String h) {
        if (h == null) return false;
        h = h.toLowerCase(java.util.Locale.ROOT);
        while (true) {
            if (S.contains(h)) return true;
            int i = h.indexOf('.');
            if (i < 0) return false;
            h = h.substring(i + 1);
        }
    }

    static boolean is(PacketStats.Conn c) {
        if (c.rport == 53 || c.rport == 853) return false;
        return host(c.name != null ? c.name : c.guess);
    }
}
