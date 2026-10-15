package org.duckdns.dunevoice.tv;

import android.content.*;
import android.media.AudioManager;
import android.os.*;
import android.view.KeyEvent;
import java.net.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.duckdns.dunevoice.Net;
import org.duckdns.dunevoice.core.Command;

final class Actions {
    private static volatile int localPort;
    private Actions() {}
    static JSONObject status() throws Exception {
        if (localPort != 0) {
            try { return request(localPort, "status"); } catch (Exception ignored) { localPort = 0; }
        }
        for (int port : new int[]{80, 11080}) {
            try { JSONObject info = request(port, "status"); localPort = port; return info; } catch (Exception ignored) {}
        }
        throw new IllegalStateException("Локальное управление Dune недоступно");
    }
    private static JSONObject request(int port, String command) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/cgi-bin/do?cmd=" + command + "&result_syntax=json&timeout=2").openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(700); connection.setReadTimeout(3000);
        try {
            if (connection.getResponseCode() != 200) throw new IllegalStateException("Dune не отвечает");
            JSONObject result = new JSONObject(Net.read(connection.getInputStream(), 65536));
            if (!result.has("protocol_version") || !"ok".equals(result.optString("command_status"))) throw new IllegalStateException("Команда недоступна в этом режиме Dune");
            return result;
        } finally { connection.disconnect(); }
    }
    private static void mainThread(Runnable runnable) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) { runnable.run(); return; }
        FutureTask<Void> task = new FutureTask<>(runnable, null);
        new Handler(Looper.getMainLooper()).post(task);
        try { task.get(5, TimeUnit.SECONDS); }
        catch (ExecutionException failed) { throw failed.getCause() instanceof Exception ? (Exception) failed.getCause() : failed; }
    }
    static synchronized String execute(Context context, Command command) throws Exception {
        Context app = context.getApplicationContext();
        if (command.action.equals("cancel")) return "Отменено";
        PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        if (command.action.equals("standby") && !power.isInteractive()) return "Dune уже в режиме ожидания";
        if (!command.action.equals("standby") && !power.isInteractive()) {
            mainThread(() -> app.startActivity(new Intent(app, WakeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            for (int i = 0; i < 50 && !power.isInteractive(); i++) Thread.sleep(100);
            if (!power.isInteractive()) throw new IllegalStateException("Dune не проснулась");
        }
        if (command.action.equals("wake")) return "Dune включена";
        if (command.action.equals("search") || command.action.equals("lampa")) {
            mainThread(() -> {
                Intent intent = new Intent(command.action.equals("search") ? Intent.ACTION_SEARCH : Intent.ACTION_MAIN);
                intent.setClassName("top.rootu.lampa", "top.rootu.lampa.MainActivity");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                if (command.action.equals("search")) intent.putExtra("query", command.argument);
                app.startActivity(intent);
            });
            return command.action.equals("search") ? "Ищу: " + command.argument : "Открываю Lampa";
        }
        if (command.action.equals("back")) {
            final boolean[] success = {false}; mainThread(() -> success[0] = RemoteKeys.back());
            if (!success[0]) throw new IllegalStateException("Включите службу кнопки микрофона");
            return "Назад";
        }
        if (command.action.equals("home")) { status(); request(localPort, "main_screen"); return "Главный экран"; }
        if (command.action.equals("standby")) { status(); request(localPort, "standby"); return "Режим ожидания"; }
        AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (command.action.equals("louder") || command.action.equals("quieter") || command.action.equals("mute") || command.action.equals("unmute")) {
            int adjust = command.action.equals("louder") ? AudioManager.ADJUST_RAISE : command.action.equals("quieter") ? AudioManager.ADJUST_LOWER : command.action.equals("mute") ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
            mainThread(() -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjust, AudioManager.FLAG_SHOW_UI));
            return "Звук изменён";
        }
        if (!command.action.equals("pause") && !command.action.equals("resume") && !command.action.equals("stop")) throw new IllegalArgumentException("Неизвестная команда");
        JSONObject info = status();
        if (info.optString("player_state").endsWith("_playback")) {
            request(localPort, command.action.equals("stop") ? "playback_action&action=stop" : "set_playback_state&speed=" + (command.action.equals("pause") ? "0" : "256"));
        } else {
            int key = command.action.equals("pause") ? KeyEvent.KEYCODE_MEDIA_PAUSE : command.action.equals("resume") ? KeyEvent.KEYCODE_MEDIA_PLAY : KeyEvent.KEYCODE_MEDIA_STOP;
            mainThread(() -> {
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
            });
        }
        return command.action.equals("pause") ? "Пауза" : command.action.equals("resume") ? "Продолжаю" : "Остановлено";
    }
}
