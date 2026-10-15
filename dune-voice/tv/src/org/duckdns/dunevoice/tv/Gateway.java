package org.duckdns.dunevoice.tv;

import android.content.*;
import android.os.SystemClock;
import android.os.PowerManager;
import android.util.Base64;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.duckdns.dunevoice.core.*;

/** Optional paired-phone API. Only fixed commands; no files, shell or URLs. */
final class Gateway implements AutoCloseable {
    private final Context context;
    private final SharedPreferences prefs;
    private final Announcement announcement;
    private final PairGate pairing = new PairGate();
    private final PlaybackStatus playback = new PlaybackStatus();
    private final SecureRandom random = new SecureRandom();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4));
    private final LinkedHashMap<String, JSONObject> completed = new LinkedHashMap<>();
    private ServerSocket server;
    private volatile boolean closed;
    Gateway(Context value) { context = value; prefs = value.getSharedPreferences("phone", 0); announcement = new Announcement(value); }
    void start() {
        new Thread(() -> {
            try {
                // A restarted service may race the previous socket's close: retry briefly.
                for (int attempt = 0; ; attempt++) {
                    try {
                        ServerSocket socket = new ServerSocket(); socket.setReuseAddress(true);
                        socket.bind(new InetSocketAddress(Endpoint.PORT), 8); server = socket; break;
                    } catch (IOException busy) { if (closed || attempt >= 20) throw busy; Thread.sleep(500); }
                }
                if (closed) { server.close(); return; }
                synchronized (announcement) { if (!closed) announcement.start(); }
                while (!closed) {
                    Socket socket = server.accept();
                    if (!local(socket.getInetAddress())) { socket.close(); continue; }
                    try { workers.execute(() -> handle(socket)); } catch (RejectedExecutionException full) { socket.close(); }
                }
            } catch (IOException | InterruptedException ignored) { /* UI reports a missing connection; no request contents in logs. */ }
        }, "dune-voice-lan").start();
    }
    private static boolean local(InetAddress address) {
        byte[] bytes = address.getAddress();
        return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress() ||
            (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc);
    }
    String openPairing() {
        String code = String.format(Locale.ROOT, "%08d", random.nextInt(100000000));
        pairing.open(code, SystemClock.elapsedRealtime()); return code;
    }
    void disconnectPhone() { pairing.close(); prefs.edit().remove("token").commit(); synchronized (completed) { completed.clear(); } }
    boolean pairingOpen() { return pairing.isOpen(SystemClock.elapsedRealtime()); }
    boolean paired() { return !prefs.getString("token", "").isEmpty(); }
    private static String line(InputStream input, int[] remaining, long deadline) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); int value;
        while (SystemClock.elapsedRealtime() < deadline && (value = input.read()) != -1) {
            if (--remaining[0] < 0) throw new IOException("header limit");
            if (value == '\n') {
                byte[] bytes = out.toByteArray();
                if (bytes.length == 0 || bytes[bytes.length - 1] != '\r') throw new IOException("line ending");
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
            }
            out.write(value);
        }
        throw new EOFException();
    }
    private void handle(Socket socket) {
        PowerManager.WakeLock lock = ((PowerManager) context.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dunevoice:request");
        lock.acquire(15000);
        try (Socket connection = socket) {
            socket.setSoTimeout(3500);
            long deadline = SystemClock.elapsedRealtime() + 10000;
            InputStream input = socket.getInputStream(); int[] limit = {8192};
            String request = line(input, limit, deadline);
            Map<String, String> headers = new HashMap<>();
            for (;;) {
                String header = line(input, limit, deadline); if (header.isEmpty()) break;
                int colon = header.indexOf(':'); if (colon < 1) throw new IOException("header");
                String name = header.substring(0, colon).toLowerCase(Locale.ROOT);
                if (headers.put(name, header.substring(colon + 1).trim()) != null) throw new IOException("duplicate header");
            }
            if (headers.containsKey("transfer-encoding")) { reply(socket, 403, error("Запрос отклонён")); return; }
            int length;
            try { length = Integer.parseInt(headers.getOrDefault("content-length", "-1")); } catch (NumberFormatException e) { length = -1; }
            if (length < 2 || length > 2048) { reply(socket, 400, error("Некорректный запрос")); return; }
            byte[] bytes = new byte[length]; int read = 0;
            while (read < length) { if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("timeout"); int count = input.read(bytes, read, length - read); if (count < 0) throw new EOFException(); read += count; }
            if (!request.equals("POST /pair HTTP/1.1") && !request.equals("POST /command HTTP/1.1") && !request.equals("POST /discover HTTP/1.1") && !request.equals("POST /playback HTTP/1.1")) { reply(socket, 404, error("Не найдено")); return; }
            if (headers.containsKey("origin")) { reply(socket, 403, error("Запрос отклонён")); return; }
            JSONObject body;
            try { body = new JSONObject(new String(bytes, StandardCharsets.UTF_8)); }
            catch (Exception e) { reply(socket, 400, error("Некорректный запрос")); return; }
            if (request.startsWith("POST /discover ")) {
                reply(socket, 200, new JSONObject().put("ok", true).put("service", "dune-voice").put("version", 1).put("name", Announcement.name())); return;
            }
            if (request.startsWith("POST /pair ")) {
                if (!pairing.claim(body.optString("code", ""), SystemClock.elapsedRealtime())) { reply(socket, 403, error("Код неверен или истёк. Откройте новый код на Dune.")); return; }
                byte[] secret = new byte[32]; random.nextBytes(secret);
                String token = Base64.encodeToString(secret, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
                if (!prefs.edit().putString("token", token).commit()) { reply(socket, 503, error("Не удалось сохранить подключение")); return; }
                synchronized (completed) { completed.clear(); }
                reply(socket, 200, new JSONObject().put("ok", true).put("token", token)); return;
            }
            String expected = prefs.getString("token", "");
            String supplied = headers.getOrDefault("authorization", "");
            if (expected.isEmpty() || !MessageDigest.isEqual(("Bearer " + expected).getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) { reply(socket, 403, error("Подключите телефон заново в настройках Dune")); return; }
            if (request.startsWith("POST /playback ")) {
                reply(socket, 200, playback.read(context)); return;
            }
            String id = body.optString("id", "");
            if (!id.matches("[a-zA-Z0-9-]{16,64}") || !(body.opt("text") instanceof String)) { reply(socket, 400, error("Некорректная команда")); return; }
            JSONObject result;
            // Serialize actions and cache both outcomes; a repeated request
            // after a lost HTTP response must not issue the action twice.
            synchronized (completed) {
                result = completed.get(id);
                if (result == null) {
                    try { result = new JSONObject().put("ok", true).put("message", Actions.execute(context, Commands.parse(body.getString("text")))); }
                    catch (Exception e) { result = error(e instanceof android.content.ActivityNotFoundException ? "Установите Lampa на Dune" : (e instanceof IllegalArgumentException || e instanceof IllegalStateException) && e.getMessage() != null ? e.getMessage() : "Команда недоступна в текущем режиме Dune"); }
                    completed.put(id, result);
                    if (completed.size() > 64) completed.remove(completed.keySet().iterator().next());
                }
            }
            reply(socket, result.optBoolean("ok") ? 200 : 400, result);
        } catch (Exception ignored) { /* A bad or disconnected client has no effect. */ }
        finally { if (lock.isHeld()) lock.release(); }
    }
    private static JSONObject error(String message) {
        try { return new JSONObject().put("ok", false).put("error", message); } catch (Exception impossible) { throw new AssertionError(impossible); }
    }
    private static void reply(Socket socket, int status, JSONObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        response.write(("HTTP/1.1 " + status + " Response\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        response.write(bytes); out.write(response.toByteArray()); out.flush();
    }
    @Override public void close() { closed = true; announcement.close(); pairing.close(); try { if (server != null) server.close(); } catch (IOException ignored) {} workers.shutdownNow(); }
}
