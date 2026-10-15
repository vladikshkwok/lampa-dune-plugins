package org.duckdns.dunevoice.phone;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.os.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import org.vosk.Model;

final class ReplayProbe {
    static String run(Instrumentation probe) throws Exception {
        if (!Build.HARDWARE.equals("ranchu") && !Build.HARDWARE.equals("goldfish")) throw new IllegalStateException("Emulator only");
        byte[] clip;
        try (InputStream in = probe.getTargetContext().getAssets().open("wake-test.pcm"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            clip = out.toByteArray();
        }
        // The same short[] method SpeechService uses: silence wraps the ring before speech.
        int captured = 0;
        try (Model model = new Model(ModelFiles.prepare(probe.getTargetContext())); BufferedRecognizer recognizer = new BufferedRecognizer(model)) {
            short[] silence = new short[3200];
            for (int i = 0; i < 120; i++) if (recognizer.acceptWaveForm(silence, silence.length)) { recognizer.getResult(); recognizer.takeAudio(); }
            short[] audio = new short[clip.length / 2 + 32000];
            for (int i = 0; i < clip.length / 2; i++) audio[i] = (short) ((clip[i * 2] & 255) | (clip[i * 2 + 1] << 8));
            for (int offset = 0; offset < audio.length; offset += 1600) {
                short[] chunk = Arrays.copyOfRange(audio, offset, Math.min(audio.length, offset + 1600));
                if (recognizer.acceptWaveForm(chunk, chunk.length)) {
                    JSONObject result = new JSONObject(recognizer.getResult()); byte[] saved = recognizer.takeAudio();
                    if (!result.optString("text").isEmpty()) {
                        if (saved.length < 16000 || saved.length > clip.length + 32000) throw new AssertionError("Incomplete/untrimmed Vosk utterance: " + saved.length);
                        captured++; Arrays.fill(saved, (byte) 0);
                    }
                }
            }
        }
        if (captured == 0) throw new AssertionError("No buffered native speech result");
        ComponentName fixture = new ComponentName(probe.getTargetContext(), ReplayFixture.class);
        for (int mode : new int[]{0, 1, 2}) {
            ReplayFixture.mode = mode; ReplayFixture.received = null; ReplayFixture.failure.set(null);
            byte[] audio = new byte[320000]; new Random(42).nextBytes(audio); byte[] expected = audio.clone();
            CountDownLatch done = new CountDownLatch(1); AtomicReference<String> result = new AtomicReference<>();
            AtomicInteger callbacks = new AtomicInteger(); AtomicReference<ReplaySpeech> session = new AtomicReference<>();
            probe.runOnMainSync(() -> session.set(new ReplaySpeech(probe.getTargetContext(), audio, text -> { callbacks.incrementAndGet(); result.set(text); done.countDown(); }, fixture)));
            if (mode == 2) {
                Thread.sleep(800); probe.runOnMainSync(() -> session.get().close()); Thread.sleep(800);
                if (callbacks.get() != 0) throw new AssertionError("Cancelled replay delivered a command");
            } else {
                if (!done.await(20, TimeUnit.SECONDS)) throw new AssertionError("Replay did not finish");
                if (mode == 0 && (!Arrays.equals(expected, ReplayFixture.received) || result.get() == null)) throw new AssertionError("PCM data/transcript mismatch");
                if (mode == 1 && result.get() != null) throw new AssertionError("Failed replay returned a command");
                if (callbacks.get() != 1) throw new AssertionError("Multiple replay callbacks");
            }
            if (ReplayFixture.failure.get() != null) throw new AssertionError("Recognition fixture failure", ReplayFixture.failure.get());
            for (byte sample : audio) if (sample != 0) throw new AssertionError("Retained command PCM after cleanup");
        }
        // Actual configured provider: report support, without treating synthetic speech as accuracy proof.
        probe.getUiAutomation().executeShellCommand("input keyevent 224").close();
        probe.startActivitySync(new Intent(probe.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> system = new AtomicReference<>();
        probe.runOnMainSync(() -> new ReplaySpeech(probe.getTargetContext(), clip, text -> { system.set(text); done.countDown(); }));
        if (!done.await(20, TimeUnit.SECONDS)) throw new AssertionError("Default-provider deadline failed");
        return "PASS bounded native utterance capture, PCM replay, failure/cancel cleanup; default provider: "
            + (system.get() == null ? "no result (unsupported/unavailable)" : system.get()) + "; synthetic/fixture checks do not measure real speech accuracy";
    }
}
