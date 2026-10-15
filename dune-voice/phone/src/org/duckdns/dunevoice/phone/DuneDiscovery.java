package org.duckdns.dunevoice.phone;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.duckdns.dunevoice.Net;
import org.duckdns.dunevoice.core.Endpoint;

/** One bounded, foreground search. Callbacks always run on the main thread. */
final class DuneDiscovery implements AutoCloseable {
    interface Listener {
        void found(String name, String address);
        void finished(boolean available);
    }
    private final NsdManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final ExecutorService checks = Executors.newSingleThreadExecutor();
    private final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
    private final Set<String> seen = new HashSet<>();
    private final Set<String> addresses = new HashSet<>();
    private boolean closed, requested, resolving;
    private final Runnable deadline = () -> finish(true);
    private final NsdManager.DiscoveryListener browser = new NsdManager.DiscoveryListener() {
        public void onDiscoveryStarted(String type) {}
        public void onDiscoveryStopped(String type) {}
        public void onStartDiscoveryFailed(String type, int code) { main.post(() -> finish(false)); }
        public void onStopDiscoveryFailed(String type, int code) {}
        public void onServiceLost(NsdServiceInfo info) {}
        public void onServiceFound(NsdServiceInfo info) { main.post(() -> enqueue(info)); }
    };
    DuneDiscovery(Context context, Listener value) { manager = context.getSystemService(NsdManager.class); listener = value; }
    void start() {
        if (closed || requested) return;
        if (manager == null) { finish(false); return; }
        try {
            manager.discoverServices("_dune-voice._tcp.", NsdManager.PROTOCOL_DNS_SD, browser);
            requested = true; main.postDelayed(deadline, 12000);
        } catch (RuntimeException unavailable) { finish(false); }
    }
    private void enqueue(NsdServiceInfo info) {
        if (closed || seen.size() >= 32 || !"_dune-voice._tcp".equals(info.getServiceType().replaceFirst("\\.$", "")) || !seen.add(info.getServiceName())) return;
        queue.add(info); resolveNext();
    }
    private void resolveNext() {
        if (closed || resolving || queue.isEmpty()) return;
        resolving = true;
        try {
            manager.resolveService(queue.remove(), new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo info, int code) { main.post(() -> { resolving = false; resolveNext(); }); }
                public void onServiceResolved(NsdServiceInfo info) { main.post(() -> { resolving = false; verify(info); resolveNext(); }); }
            });
        } catch (RuntimeException unavailable) { resolving = false; resolveNext(); }
    }
    private void verify(NsdServiceInfo info) {
        if (closed || info.getPort() != Endpoint.PORT) return;
        List<InetAddress> hosts = Build.VERSION.SDK_INT >= 34 ? info.getHostAddresses() : Collections.singletonList(info.getHost());
        for (InetAddress host : hosts) if (host instanceof Inet4Address) verifyAddress(host.getHostAddress());
    }
    private void verifyAddress(String address) {
        final String endpoint;
        try { endpoint = Endpoint.fromAddress(address); } catch (IllegalArgumentException invalid) { return; }
        if (!addresses.add(address)) return;
        checks.execute(() -> {
            try {
                JSONObject result = Net.post(endpoint, "/discover", "", new JSONObject());
                if (!"dune-voice".equals(result.optString("service")) || result.optInt("version") != 1) return;
                String name = result.optString("name", "Dune").replaceAll("[\\p{Cntrl}]", " ");
                final String label = name.substring(0, Math.min(80, name.length()));
                main.post(() -> { if (!closed) listener.found(label, address); });
            } catch (Exception ignored) { /* Discovery never pairs or sends commands. */ }
        });
    }
    private void finish(boolean available) {
        if (closed) return;
        close(); listener.finished(available);
    }
    @Override public void close() {
        if (closed) return;
        closed = true; main.removeCallbacks(deadline); queue.clear(); checks.shutdownNow();
        if (requested) {
            requested = false;
            try { manager.stopServiceDiscovery(browser); } catch (RuntimeException ignored) {}
        }
    }
}
