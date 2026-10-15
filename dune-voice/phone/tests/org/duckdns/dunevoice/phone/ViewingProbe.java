package org.duckdns.dunevoice.phone;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Disposable emulator only. Exercises real recorders through the production LAN client. */
final class ViewingProbe {
    private static void await(BooleanSupplier condition, String message) throws Exception {
        long end = SystemClock.elapsedRealtime() + 35000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) Thread.sleep(200);
        if (!condition.getAsBoolean()) throw new AssertionError(message);
    }
    private static String shell(Instrumentation probe, String command) throws Exception {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(probe.getUiAutomation().executeShellCommand(command)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
    private static boolean recording(Instrumentation probe) throws Exception {
        String audio = shell(probe, "dumpsys audio");
        String current = audio.split("RecordActivityMonitor dump time:", 2)[1].split("Events log:", 2)[0];
        return current.contains("active? true") && current.contains("pack:org.duckdns.dunevoice.phone");
    }
    static void run(Instrumentation probe) throws Exception {
        if (!Build.HARDWARE.equals("ranchu") && !Build.HARDWARE.equals("goldfish")) throw new IllegalStateException("Emulator only");
        Context context = probe.getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("phone", 0);
        String address = null;
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces()))
            for (InetAddress ip : Collections.list(nic.getInetAddresses()))
                if (ip instanceof Inet4Address && ip.isSiteLocalAddress()) address = ip.getHostAddress();
        if (address == null) throw new AssertionError("No emulator LAN address");
        AtomicInteger mode = new AtomicInteger(1); // Start while a movie is already playing.
        AtomicInteger requests = new AtomicInteger(); AtomicReference<Throwable> failure = new AtomicReference<>();
        Intent service = new Intent(context, ListeningService.class);
        String component = context.getPackageName() + "/.ListeningTile";
        String originalTiles = shell(probe, "settings get secure sysui_qs_tiles").trim();
        try (ServerSocket server = new ServerSocket(18766)) {
            Thread fixture = new Thread(() -> {
                while (!server.isClosed()) try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    if (!"POST /playback HTTP/1.1".equals(reader.readLine())) throw new AssertionError("Unexpected command");
                    boolean authorized = false; int length = 0; String line;
                    while (!(line = reader.readLine()).isEmpty()) {
                        if (line.equalsIgnoreCase("Authorization: Bearer viewing-lab")) authorized = true;
                        if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                    }
                    if (!authorized) throw new AssertionError("Missing bearer");
                    for (int i = 0; i < length; i++) if (reader.read() < 0) throw new EOFException();
                    requests.incrementAndGet();
                    if (mode.get() == 3) continue; // Simulate transport failure, not an idle reply.
                    String json = mode.get() == 2 ? "{\"ok\":true,\"known\":false}" : "{\"ok\":true,\"known\":true,\"viewing\":" + (mode.get() == 1) + "}";
                    byte[] body = json.getBytes(StandardCharsets.UTF_8);
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush();
                } catch (Throwable error) { if (!server.isClosed()) failure.set(error); }
            }, "viewing-fixture"); fixture.setDaemon(true); fixture.start();
            context.stopService(service);
            prefs.edit().putString("address", address).putString("token", "viewing-lab").commit();
            try {
                shell(probe, "input keyevent 224");
                shell(probe, "wm dismiss-keyguard");
                probe.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                shell(probe, "input keyevent 3");
                shell(probe, "cmd statusbar add-tile " + component);
                shell(probe, "settings put secure sysui_qs_tiles custom(" + component + ")," + (originalTiles.equals("null") ? "" : originalTiles));
                shell(probe, "cmd statusbar expand-settings"); Thread.sleep(2000);
                shell(probe, "cmd statusbar click-tile " + component);
                await(() -> requests.get() >= 2, "No playback polls");
                if (recording(probe) || prefs.getBoolean("microphone", false)) throw new AssertionError("Startup captured a playing movie");
                shell(probe, "input keyevent 3"); shell(probe, "input keyevent 223");
                mode.set(0); await(() -> prefs.getBoolean("microphone", false), "No resume after player exit");
                if (!recording(probe)) throw new AssertionError("Recorder did not actually resume in background");
                String fgs = shell(probe, "dumpsys activity services " + context.getPackageName());
                if (!fgs.contains("isForeground=true") || !fgs.contains("types=00000080")) throw new AssertionError("Missing microphone FGS");
                String locks = shell(probe, "dumpsys power").split("Wake Locks:", 2)[1].split("\n\n", 2)[0];
                if (!locks.contains("dunevoice:listening")) throw new AssertionError("Missing monitor wake lock");
                mode.set(1); await(() -> !prefs.getBoolean("microphone", true), "Movie did not suspend microphone");
                if (recording(probe)) throw new AssertionError("Recorder still active during movie");
                mode.set(2); Thread.sleep(7000);
                if (recording(probe)) throw new AssertionError("Unknown state resumed capture");
                mode.set(3); Thread.sleep(7000);
                if (recording(probe)) throw new AssertionError("Lost connection resumed capture");
                mode.set(1); Thread.sleep(4000); // Pause is represented by an open playback session too.
                if (recording(probe)) throw new AssertionError("Paused movie resumed capture");
                mode.set(0); await(() -> prefs.getBoolean("microphone", false), "Second resume failed");
                if (!recording(probe)) throw new AssertionError("Second recorder inactive");
                mode.set(1); await(() -> !prefs.getBoolean("microphone", true), "Second suspend failed");
                shell(probe, "input keyevent 224"); shell(probe, "wm dismiss-keyguard");
                shell(probe, "cmd statusbar expand-settings"); Thread.sleep(1000);
                shell(probe, "cmd statusbar click-tile " + component);
                await(() -> !prefs.getBoolean("listening", true), "Manual tile stop failed");
                mode.set(0); Thread.sleep(7000);
                if (ListeningService.isRunning(context) || recording(probe)) throw new AssertionError("Manual stop was undone");
                locks = shell(probe, "dumpsys power").split("Wake Locks:", 2)[1].split("\n\n", 2)[0];
                if (locks.contains("dunevoice:listening")) throw new AssertionError("Manual stop kept wake lock");
                if (failure.get() != null) throw new AssertionError("Fixture failure", failure.get());
            } finally {
                context.stopService(service); prefs.edit().remove("address").remove("token").commit();
                shell(probe, "cmd statusbar remove-tile " + component);
                shell(probe, originalTiles.equals("null") ? "settings delete secure sysui_qs_tiles" : "settings put secure sysui_qs_tiles " + originalTiles);
                shell(probe, "cmd statusbar collapse");
            }
        }
    }
}
