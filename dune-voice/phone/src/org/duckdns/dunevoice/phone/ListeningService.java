package org.duckdns.dunevoice.phone;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.vosk.*;
import org.vosk.android.*;
import org.duckdns.dunevoice.core.*;

/**
 * Vosk listens for «Эй, Дюна» all the time. A command said in the same breath
 * uses Vosk's text; after the phrase alone the system recognizer (Google on
 * most phones, much better with titles) takes the next phrase, and Vosk
 * covers for it when it is missing or fails.
 */
public final class ListeningService extends Service implements RecognitionListener {
    public static final String STOP = "org.duckdns.dunevoice.STOP";
    private static final int NOTIFICATION = 81;
    /** What the microphone hears right now; shown only while the app is open, never stored. */
    static volatile String live = "";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final WakeGate wake = new WakeGate();
    private Model model;
    private Recognizer recognizer;
    private SpeechService microphone;
    private SpeechRecognizer system;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean closing;
    private boolean started, busy, systemFailed;
    private long lastCommand;
    static boolean isRunning(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(50))
            if (service.service.getClassName().equals(ListeningService.class.getName())) return true;
        return false;
    }
    private final Runnable armedTimeout = () -> { if (!closing && !busy && !wake.armed(SystemClock.elapsedRealtime())) state("Слушаю «Эй, Дюна»"); };
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && STOP.equals(intent.getAction())) { state("Прослушивание выключено"); stopSelf(); return START_NOT_STICKY; }
        if (started) return START_NOT_STICKY;
        started = true;
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("listening", "Голосовая активация", NotificationManager.IMPORTANCE_LOW));
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification("Подготавливаю русскую модель…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(NOTIFICATION, notification("Подготавливаю русскую модель…"));
        state("Подготавливаю русскую модель…");
        io.execute(() -> {
            try {
                LibVosk.setLogLevel(LogLevel.WARNINGS);
                Model loaded = new Model(ModelFiles.prepare(this));
                main.post(() -> {
                    if (closing) { loaded.close(); return; }
                    try {
                        model = loaded; recognizer = new Recognizer(model, 16000.0f);
                        microphone = new SpeechService(recognizer, 16000.0f);
                        wakeLock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dunevoice:listening");
                        wakeLock.acquire();
                        if (!microphone.startListening(this)) throw new IllegalStateException("microphone busy");
                        state("Слушаю «Эй, Дюна»");
                    } catch (Exception error) { state("Не удалось включить микрофон. Откройте приложение и попробуйте снова."); stopSelf(); }
                });
            } catch (Throwable error) {
                main.post(() -> { if (!closing) { state("Не удалось загрузить голосовую модель. Включите прослушивание снова."); stopSelf(); } });
            }
        });
        // Explicit user activation is required after reboot or process removal.
        return START_NOT_STICKY;
    }
    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ListeningService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "listening").setSmallIcon(org.duckdns.dunevoice.phone.R.drawable.ic_mic)
            .setContentTitle("Эй, Дюна").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE).addAction(new Notification.Action.Builder(null, "Выключить", stop).build()).build();
    }
    private void state(String text) {
        getSharedPreferences("phone", 0).edit().putString("state", text).putBoolean("listening", !closing && started).apply();
        if (!closing && started) getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(text));
    }
    private void buzz(int ms) {
        try { ((Vibrator) getSystemService(VIBRATOR_SERVICE)).vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)); }
        catch (RuntimeException ignored) {}
    }
    @Override public void onResult(String hypothesis) {
        if (closing || system != null) return;
        String text;
        try { text = new JSONObject(hypothesis).optString("text", ""); } catch (Exception e) { return; }
        if (!text.isEmpty()) live = text;
        long now = SystemClock.elapsedRealtime();
        boolean wasArmed = wake.armed(now);
        String command = wake.accept(text, now);
        if (command == null) {
            if (wake.armed(now) && !wasArmed) armed();
            return;
        }
        main.removeCallbacks(armedTimeout);
        execute(command);
    }
    /** Wake phrase alone: give the next phrase to the system recognizer if it works here. */
    private void armed() {
        buzz(60);
        if (!systemFailed && getSharedPreferences("phone", 0).getBoolean("system", true) && SpeechRecognizer.isRecognitionAvailable(this)) {
            wake.reset(); startSystem(); return;
        }
        state("Слушаю команду…");
        main.removeCallbacks(armedTimeout); main.postDelayed(armedTimeout, 8000);
    }
    private void startSystem() {
        microphone.stop(); // the system recognizer needs the microphone to itself
        state("Слушаю команду…");
        try {
            system = SpeechRecognizer.createSpeechRecognizer(this);
            system.setRecognitionListener(new android.speech.RecognitionListener() {
                public void onResults(Bundle bundle) {
                    ArrayList<String> choices = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = choices == null || choices.isEmpty() ? "" : choices.get(0);
                    endSystem(false);
                    if (text.isEmpty()) state("Не расслышал команду. Скажите «Эй, Дюна» ещё раз.");
                    else { live = text; execute(text); }
                }
                public void onError(int error) {
                    boolean silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                    endSystem(!silence);
                    if (silence) state("Не расслышал команду. Скажите «Эй, Дюна» ещё раз.");
                    else { wake.arm(SystemClock.elapsedRealtime()); state("Слушаю команду…"); main.postDelayed(armedTimeout, 8000); }
                }
                public void onPartialResults(Bundle bundle) {
                    ArrayList<String> values = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (values != null && !values.isEmpty() && !values.get(0).isEmpty()) live = values.get(0);
                }
                public void onReadyForSpeech(Bundle params) {}
                public void onBeginningOfSpeech() {}
                public void onRmsChanged(float rms) {}
                public void onBufferReceived(byte[] buffer) {}
                public void onEndOfSpeech() {}
                public void onEvent(int type, Bundle params) {}
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU").putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true).putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            system.startListening(intent);
        } catch (RuntimeException unavailable) {
            endSystem(true); wake.arm(SystemClock.elapsedRealtime()); state("Слушаю команду…"); main.postDelayed(armedTimeout, 8000);
        }
    }
    private void endSystem(boolean failed) {
        if (failed) systemFailed = true; // until the next start; Vosk handles commands meanwhile
        if (system != null) { try { system.destroy(); } catch (RuntimeException ignored) {} system = null; }
        if (!closing && microphone != null && !microphone.startListening(this)) { state("Микрофон занят другим приложением. Включите прослушивание заново."); stopSelf(); }
    }
    private void execute(String command) {
        if (command.matches("выключи (?:микрофон|прослушивание)|перестань слушать|хватит слушать")) {
            state("Прослушивание выключено голосовой командой"); stopSelf(); return;
        }
        long now = SystemClock.elapsedRealtime();
        if (busy || now - lastCommand < 1500) return;
        try { Commands.parse(command); }
        catch (IllegalArgumentException invalid) { state("Услышал «" + command + "» — " + invalid.getMessage()); return; }
        busy = true; lastCommand = now; state("Услышал «" + command + "». Передаю на Dune…");
        io.execute(() -> {
            String reply;
            try { reply = "«" + command + "» → " + Remote.send(this, command); buzz(30); }
            catch (Exception error) { reply = "«" + command + "» → " + Remote.explain(error); }
            String result = reply;
            main.post(() -> { busy = false; if (!closing) state(result); });
        });
    }
    @Override public void onPartialResult(String hypothesis) {
        try { String text = new JSONObject(hypothesis).optString("partial", ""); if (!text.isEmpty()) live = text; } catch (Exception ignored) {}
    }
    @Override public void onFinalResult(String text) { /* final at shutdown must not execute a command */ }
    @Override public void onError(Exception error) { if (!closing) { state("Микрофон недоступен. Включите прослушивание заново."); stopSelf(); } }
    @Override public void onTimeout() { if (!closing) stopSelf(); }
    @Override public void onDestroy() {
        closing = true; wake.reset(); live = "";
        main.removeCallbacks(armedTimeout);
        if (system != null) { try { system.destroy(); } catch (RuntimeException ignored) {} system = null; }
        if (microphone != null) { microphone.stop(); microphone.shutdown(); }
        if (recognizer != null) recognizer.close(); if (model != null) model.close();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        io.shutdownNow();
        SharedPreferences prefs = getSharedPreferences("phone", 0);
        SharedPreferences.Editor update = prefs.edit().putBoolean("listening", false);
        if (prefs.getString("state", "").startsWith("Слушаю")) update.putString("state", "Прослушивание выключено");
        update.apply();
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
