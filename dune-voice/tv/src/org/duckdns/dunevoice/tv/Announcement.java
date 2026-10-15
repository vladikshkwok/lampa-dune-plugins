package org.duckdns.dunevoice.tv;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import org.duckdns.dunevoice.core.Endpoint;

/** Advertise only after the paired-phone gateway has bound its socket. */
final class Announcement implements AutoCloseable {
    private final NsdManager manager;
    private boolean requested;
    private final NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
        public void onServiceRegistered(NsdServiceInfo info) {}
        public void onRegistrationFailed(NsdServiceInfo info, int code) {}
        public void onServiceUnregistered(NsdServiceInfo info) {}
        public void onUnregistrationFailed(NsdServiceInfo info, int code) {}
    };
    Announcement(Context context) { manager = context.getSystemService(NsdManager.class); }
    static String name() { return "Dune " + Build.MODEL; }
    synchronized void start() {
        if (manager == null || requested) return;
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(name()); info.setServiceType("_dune-voice._tcp."); info.setPort(Endpoint.PORT);
        info.setAttribute("version", "1");
        try { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener); requested = true; }
        catch (RuntimeException unavailable) { /* Manual IP pairing remains available. */ }
    }
    @Override public synchronized void close() {
        if (!requested) return;
        requested = false;
        try { manager.unregisterService(listener); } catch (RuntimeException ignored) {}
    }
}
