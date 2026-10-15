package org.duckdns.dunevoice.phone;

import android.content.Intent;
import android.os.*;
import android.speech.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Lab-only recognizer, included in probe manifests only. Does not open a microphone. */
public final class ReplayFixture extends RecognitionService {
    static volatile int mode;
    static volatile byte[] received;
    static final AtomicInteger cancelled = new AtomicInteger();
    static final AtomicReference<Throwable> failure = new AtomicReference<>();
    private ParcelFileDescriptor source;
    @Override protected void onStartListening(Intent intent, Callback callback) {
        try {
            source = intent.getParcelableExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, ParcelFileDescriptor.class);
            if (source == null || !"web_search".equals(intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
                || intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 0) != 16000
                || intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 0) != 1)
                throw new AssertionError("Missing PCM or search configuration");
            callback.readyForSpeech(new Bundle());
            if (mode == 1) { callback.error(SpeechRecognizer.ERROR_NETWORK); clear(); return; }
            if (mode == 2) return; // Deliberately blocked until the client cancels.
            ParcelFileDescriptor input = source;
            new Thread(() -> {
                try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(input); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096]; int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    received = out.toByteArray();
                    Bundle result = new Bundle();
                    result.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, new ArrayList<>(Arrays.asList("Эй, Дюна, найди Человек-паук: Враг в отражении")));
                    callback.results(result);
                } catch (Exception error) { failure.set(error); }
            }, "replay-fixture").start();
        } catch (Throwable error) { failure.set(error); }
    }
    private void clear() { try { if (source != null) source.close(); } catch (IOException ignored) {} source = null; }
    @Override protected void onCancel(Callback callback) { cancelled.incrementAndGet(); clear(); }
    @Override protected void onStopListening(Callback callback) { clear(); }
    @Override public void onDestroy() { clear(); super.onDestroy(); }
}
