package org.duckdns.dunevoice.tv;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.speech.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.duckdns.dunevoice.Screen;
import org.duckdns.dunevoice.core.*;

public final class VoiceActivity extends Activity implements RecognitionListener {
    private SpeechRecognizer recognizer;
    private TextView message;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ResultGate results = new ResultGate();
    private long generation;
    private boolean executing;
    private long listeningStarted;
    private final Runnable deadline = () -> fail("Не расслышал. Нажмите микрофон ещё раз.");
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 27) setTurnScreenOn(true);
        else getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        Screen screen = new Screen(this, true);
        screen.root.view.setGravity(android.view.Gravity.CENTER);
        Screen.Box card = screen.root.card();
        card.view.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        ((android.widget.LinearLayout.LayoutParams) card.view.getLayoutParams()).width = screen.dp(620);
        ((android.widget.LinearLayout.LayoutParams) card.view.getLayoutParams()).gravity = android.view.Gravity.CENTER_HORIZONTAL;
        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(R.drawable.ic_voice); card.add(icon, screen.dp(88), screen.dp(8));
        message = card.label("Подключаю микрофон…", 30, Screen.TEXT, true); message.setGravity(android.view.Gravity.CENTER);
        card.muted("«Найди фильм…» · «Пауза» · «Продолжи» · «Громче» · «Домой»").setGravity(android.view.Gravity.CENTER);
        card.quiet("Отмена", v -> finish());
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        else listen();
    }
    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (executing || SystemClock.elapsedRealtime() - listeningStarted < 800) return;
        handler.removeCallbacks(deadline); results.cancel();
        if (recognizer != null) { recognizer.destroy(); recognizer = null; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] granted) {
        super.onRequestPermissionsResult(request, permissions, granted);
        if (granted.length > 0 && granted[0] == PackageManager.PERMISSION_GRANTED) listen(); else fail("Нужно разрешение на микрофон");
    }
    private void listen() {
        listeningStarted = SystemClock.elapsedRealtime();
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { fail("В прошивке нет сервиса распознавания речи"); return; }
        generation = results.start();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this); recognizer.setRecognitionListener(this);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU"); intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try { recognizer.startListening(intent); handler.postDelayed(deadline, 20000); }
        catch (RuntimeException error) { fail("Не удалось запустить распознавание"); }
    }
    private void fail(String text) {
        if (executing || isFinishing()) return;
        results.cancel(); handler.removeCallbacks(deadline);
        if (recognizer != null) recognizer.cancel(); message.setText(text);
    }
    @Override public void onResults(Bundle bundle) {
        ArrayList<String> choices = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (choices == null || choices.isEmpty()) { fail("Не расслышал команду"); return; }
        Command command;
        try { command = Commands.parse(choices.get(0)); } catch (IllegalArgumentException error) { fail(error.getMessage()); return; }
        if (!results.claim(generation)) return;
        executing = true; handler.removeCallbacks(deadline);
        // Return to the previous screen before BACK or player commands execute.
        Context app = getApplicationContext(); finish();
        handler.postDelayed(() -> new Thread(() -> {
            try { String reply = Actions.execute(app, command); handler.post(() -> Toast.makeText(app, reply, Toast.LENGTH_LONG).show()); }
            catch (Exception error) { handler.post(() -> Toast.makeText(app, error instanceof ActivityNotFoundException ? "Установите Lampa на Dune" : "Команда не выполнена: " + safeMessage(error), Toast.LENGTH_LONG).show()); }
        }, "dune-voice-command").start(), 250);
    }
    private static String safeMessage(Exception error) { return error.getMessage() == null ? "Dune недоступна" : error.getMessage(); }
    @Override public void onReadyForSpeech(Bundle info) { message.setText("Слушаю…"); }
    @Override public void onBeginningOfSpeech() { message.setText("Говорите…"); }
    @Override public void onEndOfSpeech() { message.setText("Распознаю…"); }
    @Override public void onError(int error) { if (!executing) fail(error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ? "Нет связи с сервисом распознавания речи" : error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? "Нет доступа к микрофону" : "Не расслышал. Нажмите микрофон ещё раз."); }
    @Override public void onPartialResults(Bundle partial) {
        ArrayList<String> values = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (values != null && !values.isEmpty()) message.setText(values.get(0));
    }
    @Override public void onRmsChanged(float value) {}
    @Override public void onBufferReceived(byte[] value) {}
    @Override public void onEvent(int type, Bundle data) {}
    @Override public void onStop() { if (!executing) results.cancel(); if (recognizer != null) recognizer.cancel(); super.onStop(); }
    @Override public void onDestroy() { handler.removeCallbacks(deadline); if (recognizer != null) recognizer.destroy(); super.onDestroy(); }
}
