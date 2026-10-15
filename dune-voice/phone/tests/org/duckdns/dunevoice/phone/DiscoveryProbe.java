package org.duckdns.dunevoice.phone;

import android.app.Instrumentation;
import android.net.nsd.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.duckdns.dunevoice.core.Endpoint;

/** Exercise the production browser over real Android mDNS and HTTP sockets. */
final class DiscoveryProbe {
    static void run(Instrumentation probe) throws Exception {
        NsdManager nsd = probe.getTargetContext().getSystemService(NsdManager.class);
        CountDownLatch registered = new CountDownLatch(1);
        AtomicBoolean valid = new AtomicBoolean(); AtomicReference<Throwable> failure = new AtomicReference<>();
        NsdManager.RegistrationListener announcement = new NsdManager.RegistrationListener() {
            public void onServiceRegistered(NsdServiceInfo info) { registered.countDown(); }
            public void onRegistrationFailed(NsdServiceInfo info, int code) { failure.set(new AssertionError("registration " + code)); registered.countDown(); }
            public void onServiceUnregistered(NsdServiceInfo info) {}
            public void onUnregistrationFailed(NsdServiceInfo info, int code) {}
        };
        try (ServerSocket server = new ServerSocket(Endpoint.PORT)) {
            Thread http = new Thread(() -> {
                while (!server.isClosed()) try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    if (!"POST /discover HTTP/1.1".equals(reader.readLine())) throw new AssertionError("Discovery attempted a command or pairing");
                    int length = 0; String line;
                    while (!(line = reader.readLine()).isEmpty()) if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                    for (int i = 0; i < length; i++) if (reader.read() < 0) throw new EOFException();
                    byte[] body = ("{\"ok\":true,\"service\":\"" + (valid.get() ? "dune-voice" : "other-device") + "\",\"version\":1,\"name\":\"Dune Test\"}").getBytes(StandardCharsets.UTF_8);
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush();
                } catch (Throwable error) { if (!server.isClosed()) failure.set(error); }
            }, "discovery-fixture");
            http.setDaemon(true); http.start();
            NsdServiceInfo info = new NsdServiceInfo(); info.setServiceName("Dune Test"); info.setServiceType("_dune-voice._tcp."); info.setPort(Endpoint.PORT);
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, announcement);
            try {
                if (!registered.await(10, TimeUnit.SECONDS) || failure.get() != null) throw new AssertionError("NSD advertisement failed", failure.get());
                for (boolean accept : new boolean[]{false, true}) {
                    valid.set(accept); CountDownLatch done = new CountDownLatch(1); AtomicReference<String> found = new AtomicReference<>();
                    AtomicReference<DuneDiscovery> search = new AtomicReference<>();
                    probe.runOnMainSync(() -> {
                        search.set(new DuneDiscovery(probe.getTargetContext(), new DuneDiscovery.Listener() {
                            public void found(String name, String address) { found.set(name + " " + address); done.countDown(); }
                            public void finished(boolean available) { done.countDown(); }
                        })); search.get().start();
                    });
                    try {
                        if (!done.await(16, TimeUnit.SECONDS)) throw new AssertionError("Discovery deadline failed");
                        if (accept ? found.get() == null || !found.get().startsWith("Dune Test ") : found.get() != null) throw new AssertionError("Incorrect discovery result: " + found.get());
                        if (failure.get() != null) throw new AssertionError("Unexpected HTTP request", failure.get());
                    } finally { probe.runOnMainSync(() -> { search.get().close(); search.get().close(); }); }
                }
            } finally { nsd.unregisterService(announcement); }
        }
    }
}
