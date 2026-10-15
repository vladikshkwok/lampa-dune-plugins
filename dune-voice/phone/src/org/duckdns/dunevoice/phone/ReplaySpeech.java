package org.duckdns.dunevoice.phone;

import android.content.*;
import android.media.AudioFormat;
import android.os.*;
import android.speech.*;
import android.system.*;
import java.io.*;
import java.util.*;

/** Android 13+ re-recognition of one complete utterance. No new mic, files or custom server. */
final class ReplaySpeech implements AutoCloseable {
    interface Result { void complete(String text); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Result result;
    private final byte[] pcm;
    private SpeechRecognizer recognizer;
    private ParcelFileDescriptor read, write;
    private volatile boolean closed;
    private final Runnable deadline = () -> fail(SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
    ReplaySpeech(Context context, byte[] audio, Result callback) { this(context, audio, callback, null); }
    ReplaySpeech(Context context, byte[] audio, Result callback, ComponentName component) {
        pcm = audio; result = callback;
        try {
            if (Build.VERSION.SDK_INT < 33) throw new IllegalStateException("Audio source requires Android 13");
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe(); read = pipe[0]; write = pipe[1];
            recognizer = component == null ? SpeechRecognizer.createSpeechRecognizer(context) : SpeechRecognizer.createSpeechRecognizer(context, component);
            recognizer.setRecognitionListener(new RecognitionListener() {
                public void onResults(Bundle bundle) {
                    ArrayList<String> choices = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (choices == null || choices.isEmpty()) fail(SpeechRecognizer.ERROR_NO_MATCH); else finish(choices.get(0));
                }
                public void onError(int error) { fail(error); }
                public void onReadyForSpeech(Bundle info) {}
                public void onBeginningOfSpeech() {}
                public void onRmsChanged(float rms) {}
                public void onBufferReceived(byte[] bytes) {}
                public void onEndOfSpeech() {}
                public void onPartialResults(Bundle partial) {}
                public void onEvent(int type, Bundle info) {}
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU").putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000);
            recognizer.startListening(intent); main.postDelayed(deadline, 15000);
            ParcelFileDescriptor output = write;
            Thread writer = new Thread(() -> {
                try {
                    FileDescriptor fd = output.getFileDescriptor();
                    Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) | OsConstants.O_NONBLOCK);
                    for (int offset = 0; !closed && offset < pcm.length; ) {
                        try { offset += Os.write(fd, pcm, offset, Math.min(16384, pcm.length - offset)); }
                        catch (ErrnoException busy) {
                            if (busy.errno != OsConstants.EAGAIN) throw busy;
                            Thread.sleep(10); // A service ignoring AUDIO_SOURCE must not strand a writer.
                        }
                    }
                } catch (ErrnoException | IOException | InterruptedException | RuntimeException unavailable) { main.post(() -> fail(SpeechRecognizer.ERROR_AUDIO)); }
                finally { try { output.close(); } catch (IOException ignored) {} Arrays.fill(pcm, (byte) 0); }
            }, "dune-voice-refine"); writer.setDaemon(true); writer.start();
        } catch (IOException | RuntimeException unavailable) { main.post(() -> fail(SpeechRecognizer.ERROR_CLIENT)); }
    }
    private void fail(int error) {
        if (closed) return;
        android.util.Log.i("DuneSpeech", "System replay error=" + error); // No audio or utterance text.
        finish(null);
    }
    private void finish(String text) { if (closed) return; close(); result.complete(text); }
    @Override public void close() {
        if (closed) return;
        closed = true; main.removeCallbacks(deadline);
        if (recognizer != null) { try { recognizer.destroy(); } catch (RuntimeException ignored) {} recognizer = null; }
        try { if (read != null) read.close(); } catch (IOException ignored) {}
        try { if (write != null) write.close(); } catch (IOException ignored) {}
        Arrays.fill(pcm, (byte) 0);
    }
}
