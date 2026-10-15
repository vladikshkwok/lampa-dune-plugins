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
 * reuses the same audio for system recognition on Android 13+; after the phrase
 * alone the system recognizer takes the next phrase. Vosk covers failures.
 */
public final class ListeningService extends Service implements RecognitionListener {
    public static final String STOP = "org.duckdns.dunevoice.STOP";
    private static final int NOTIFICATION = 81;
    /** What the microphone hears right now; shown only while the app is open, never stored. */
    static volatile String live = "";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService monitor = Executors.newSingleThreadExecutor();
    private final WakeGate wake = new WakeGate();
    private final ViewingGate viewing = new ViewingGate();
    private Model model;
    private Recognizer recognizer;
    private SpeechService microphone;
    private SpeechRecognizer system;
    private ReplaySpeech refinement;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean closing;
    private boolean started, busy;
    private long retrySystemAfter;
    private volatile long captureGeneration;
    private long lastCommand;
    private final Runnable checkPlayback = new Runnable() { public void run() { monitor.execute(() -> {
        Boolean active = null;
        try { active = Remote.viewing(ListeningService.this); } catch (Exception unavailable) { /* Keep the last safe state. */ }
        Boolean sample = active;
        main.post(() -> {
            if (closing) return;
            viewing.sample(sample, SystemClock.elapsedRealtime());
            if (!viewing.allowsMicrophone()) {
                suspendCapture();
                state(Boolean.TRUE.equals(sample) ? "Просмотр: микрофон выключен до выхода из плеера" : sample == null ? "Микрофон выключен: нет статуса плеера. Проверьте связь и обновление Dune." : "Микрофон выключен. Подтверждаю выход из плеера…");
            } else if (model != null && microphone == null && system == null && refinement == null) resumeCapture();
            main.postDelayed(checkPlayback, 3000);
        });
    }); } };
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
        getSharedPreferences("phone", 0).edit().putBoolean("microphone", false).putBoolean("viewing", true).apply();
        state("Подготавливаю русскую модель…");
        wakeLock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dunevoice:listening");
        wakeLock.acquire(); // Also receives the end-of-viewing state with the screen off.
        main.post(checkPlayback);
        io.execute(() -> {
            try {
                LibVosk.setLogLevel(LogLevel.WARNINGS);
                Model loaded = new Model(ModelFiles.prepare(this));
                main.post(() -> {
                    if (closing) { loaded.close(); return; }
                    try {
                        model = loaded;
                        if (viewing.allowsMicrophone()) resumeCapture();
                        else state("Микрофон выключен. Проверяю состояние плеера…");
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
        SharedPreferences prefs = getSharedPreferences("phone", 0);
        if (text.equals(prefs.getString("state", "")) && prefs.getBoolean("listening", false) == (!closing && started)
            && prefs.getBoolean("viewing", false) == !viewing.allowsMicrophone()) return;
        prefs.edit().putString("state", text).putBoolean("listening", !closing && started)
            .putBoolean("viewing", !viewing.allowsMicrophone()).apply();
        if (!closing && started) getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(text));
    }
    private boolean accepts(long generation) { return !closing && viewing.allowsMicrophone() && generation == captureGeneration; }
    private void suspendCapture() {
        ++captureGeneration; wake.reset(); live = ""; main.removeCallbacks(armedTimeout);
        ReplaySpeech oldRefinement = refinement; refinement = null;
        if (oldRefinement != null) oldRefinement.close();
        SpeechRecognizer oldSystem = system; system = null;
        if (oldSystem != null) { try { oldSystem.destroy(); } catch (RuntimeException ignored) {} }
        if (microphone != null) { microphone.stop(); microphone.shutdown(); microphone = null; }
        if (recognizer != null) { recognizer.close(); recognizer = null; }
        getSharedPreferences("phone", 0).edit().putBoolean("microphone", false).apply();
    }
    private void resumeCapture() {
        if (closing || !viewing.allowsMicrophone() || model == null) return;
        try {
            long generation = ++captureGeneration;
            BufferedRecognizer buffered = new BufferedRecognizer(model);
            recognizer = buffered;
            microphone = new SpeechService(recognizer, 16000.0f);
            RecognitionListener listener = new RecognitionListener() {
                public void onResult(String value) {
                    byte[] audio = buffered.takeAudio();
                    if (accepts(generation)) heard(value, audio); else java.util.Arrays.fill(audio, (byte) 0);
                }
                public void onPartialResult(String value) { if (accepts(generation)) ListeningService.this.onPartialResult(value); }
                public void onFinalResult(String value) { /* Never execute at shutdown. */ }
                public void onError(Exception error) { if (accepts(generation)) ListeningService.this.onError(error); }
                public void onTimeout() { if (accepts(generation)) ListeningService.this.onTimeout(); }
            };
            if (!microphone.startListening(listener)) throw new IllegalStateException("microphone busy");
            getSharedPreferences("phone", 0).edit().putBoolean("microphone", true).apply();
            state("Слушаю «Эй, Дюна»");
        } catch (Exception error) { state("Не удалось включить микрофон. Откройте приложение и попробуйте снова."); stopSelf(); }
    }
    private void buzz(int ms) {
        try { ((Vibrator) getSystemService(VIBRATOR_SERVICE)).vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)); }
        catch (RuntimeException ignored) {}
    }
    @Override public void onResult(String hypothesis) {
        heard(hypothesis, new byte[0]);
    }
    private void heard(String hypothesis, byte[] audio) {
        if (closing || !viewing.allowsMicrophone() || system != null || refinement != null) { java.util.Arrays.fill(audio, (byte) 0); return; }
        String text;
        try { text = new JSONObject(hypothesis).optString("text", ""); } catch (Exception e) { java.util.Arrays.fill(audio, (byte) 0); return; }
        if (!text.isEmpty()) live = text;
        long now = SystemClock.elapsedRealtime();
        boolean wasArmed = wake.armed(now);
        String command = wake.accept(text, now);
        if (command == null) {
            java.util.Arrays.fill(audio, (byte) 0);
            if (wake.armed(now) && !wasArmed) armed();
            return;
        }
        main.removeCallbacks(armedTimeout);
        if (Build.VERSION.SDK_INT >= 33 && audio.length > 0 && SearchRefinement.eligible(command) && canUseSystem()) {
            refine(command, audio); return;
        }
        java.util.Arrays.fill(audio, (byte) 0);
        recognitionSource("Встроенное распознавание");
        execute(command);
    }
    private boolean canUseSystem() {
        return SystemClock.elapsedRealtime() >= retrySystemAfter && getSharedPreferences("phone", 0).getBoolean("system", true) && SpeechRecognizer.isRecognitionAvailable(this);
    }
    private void recognitionSource(String source) { getSharedPreferences("phone", 0).edit().putString("recognition_source", source).apply(); }
    private void refine(String original, byte[] audio) {
        suspendCapture();
        long generation = captureGeneration;
        state("Уточняю название системным распознаванием…");
        refinement = new ReplaySpeech(this, audio, transcript -> {
            if (!accepts(generation) || refinement == null) return;
            refinement = null;
            String improved = SearchRefinement.choose(original, transcript);
            if (transcript == null) retrySystemAfter = SystemClock.elapsedRealtime() + 30000;
            recognitionSource(transcript == null ? "Встроенное: системное уточнение недоступно" : "Системное распознавание полной фразы");
            resumeCapture();
            execute(improved);
        });
    }
    /** Wake phrase alone: give the next phrase to the system recognizer if it works here. */
    private void armed() {
        buzz(60);
        if (canUseSystem()) {
            wake.reset(); startSystem(); return;
        }
        state("Слушаю команду…");
        main.removeCallbacks(armedTimeout); main.postDelayed(armedTimeout, 8000);
    }
    private void startSystem() {
        microphone.stop(); // the system recognizer needs the microphone to itself
        state("Слушаю команду…");
        try {
            long generation = captureGeneration;
            system = SpeechRecognizer.createSpeechRecognizer(this);
            system.setRecognitionListener(new android.speech.RecognitionListener() {
                public void onResults(Bundle bundle) {
                    if (!accepts(generation) || system == null) return;
                    ArrayList<String> choices = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = choices == null || choices.isEmpty() ? "" : choices.get(0);
                    endSystem(false);
                    if (text.isEmpty()) state("Не расслышал команду. Скажите «Эй, Дюна» ещё раз.");
                    else { recognitionSource("Системное распознавание"); live = text; execute(text); }
                }
                public void onError(int error) {
                    if (!accepts(generation) || system == null) return;
                    boolean silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                    endSystem(!silence);
                    if (silence) state("Не расслышал команду. Скажите «Эй, Дюна» ещё раз.");
                    else { wake.arm(SystemClock.elapsedRealtime()); state("Слушаю команду…"); main.postDelayed(armedTimeout, 8000); }
                }
                public void onPartialResults(Bundle bundle) {
                    if (!accepts(generation) || system == null) return;
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
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU").putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            system.startListening(intent);
        } catch (RuntimeException unavailable) {
            endSystem(true); wake.arm(SystemClock.elapsedRealtime()); state("Слушаю команду…"); main.postDelayed(armedTimeout, 8000);
        }
    }
    private void endSystem(boolean failed) {
        if (failed) retrySystemAfter = SystemClock.elapsedRealtime() + 30000;
        // Recreate the Vosk recorder and invalidate callbacks from either old recognizer.
        suspendCapture();
        resumeCapture();
    }
    private void execute(String command) {
        if (closing || !viewing.allowsMicrophone()) return;
        if (command.matches("выключи (?:микрофон|прослушивание)|перестань слушать|хватит слушать")) {
            state("Прослушивание выключено голосовой командой"); stopSelf(); return;
        }
        long now = SystemClock.elapsedRealtime();
        if (busy || now - lastCommand < 1500) return;
        try { Commands.parse(command); }
        catch (IllegalArgumentException invalid) { state("Услышал «" + command + "» — " + invalid.getMessage()); return; }
        busy = true; lastCommand = now; state("Услышал «" + command + "». Передаю на Dune…");
        long generation = captureGeneration;
        io.execute(() -> {
            String reply;
            if (!accepts(generation)) { main.post(() -> busy = false); return; }
            try { reply = "«" + command + "» → " + Remote.send(this, command); buzz(30); }
            catch (Exception error) { reply = "«" + command + "» → " + Remote.explain(error); }
            String result = reply;
            main.post(() -> { busy = false; if (accepts(generation)) state(result); });
        });
    }
    @Override public void onPartialResult(String hypothesis) {
        if (closing || !viewing.allowsMicrophone()) return;
        try { String text = new JSONObject(hypothesis).optString("partial", ""); if (!text.isEmpty()) live = text; } catch (Exception ignored) {}
    }
    @Override public void onFinalResult(String text) { /* final at shutdown must not execute a command */ }
    @Override public void onError(Exception error) { if (!closing) { state("Микрофон недоступен. Включите прослушивание заново."); stopSelf(); } }
    @Override public void onTimeout() { if (!closing) stopSelf(); }
    @Override public void onDestroy() {
        closing = true;
        main.removeCallbacks(checkPlayback); suspendCapture();
        if (model != null) model.close();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        io.shutdownNow(); monitor.shutdownNow();
        SharedPreferences prefs = getSharedPreferences("phone", 0);
        SharedPreferences.Editor update = prefs.edit().putBoolean("listening", false).putBoolean("microphone", false).putBoolean("viewing", false);
        if (prefs.getString("state", "").startsWith("Слушаю")) update.putString("state", "Прослушивание выключено");
        update.apply();
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
