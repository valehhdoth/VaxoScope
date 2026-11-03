package xyz.valeh.vaxodpi.scan;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;

public final class Device {
    public final String ip;
    public volatile String mac, host, mdnsName, typeBasis = "-";
    public volatile int type;
    public volatile boolean self, gateway, online;
    public volatile long rtt = -1;
    public final Set<Integer> ports = new ConcurrentSkipListSet<Integer>();
    public final Set<String> methods = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    public final Set<String> serviceTypes = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    public final CopyOnWriteArrayList<String> services = new CopyOnWriteArrayList<String>();

    Device(String ip) { this.ip = ip; }
}
