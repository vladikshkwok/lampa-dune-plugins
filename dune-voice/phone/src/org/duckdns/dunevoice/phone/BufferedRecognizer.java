package org.duckdns.dunevoice.phone;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import org.json.*;
import org.vosk.*;
import org.duckdns.dunevoice.core.PcmWindow;

/** Reuses the recorder already owned by Vosk, retaining only a short in-memory window. */
final class BufferedRecognizer extends Recognizer {
    private final PcmWindow audio = new PcmWindow(16000 * 20);
    private final ArrayDeque<byte[]> utterances = new ArrayDeque<>();
    BufferedRecognizer(Model model) throws IOException { super(model, 16000.0f); setWords(true); }
    @Override public boolean acceptWaveForm(short[] samples, int length) {
        audio.append(samples, length); return super.acceptWaveForm(samples, length);
    }
    @Override public String getResult() {
        String result = super.getResult(); byte[] pcm = new byte[0];
        try {
            JSONArray words = new JSONObject(result).optJSONArray("result");
            if (words != null && words.length() > 0) {
                long start = Math.max(0, (long) ((words.getJSONObject(0).getDouble("start") - .25) * 16000));
                long end = Math.min(audio.size(), (long) ((words.getJSONObject(words.length() - 1).getDouble("end") + .5) * 16000));
                pcm = audio.copy(start, end);
            }
        } catch (JSONException invalid) { /* No clipped or guessed audio is sent. */ }
        synchronized (utterances) {
            if (utterances.size() == 3) Arrays.fill(utterances.removeFirst(), (byte) 0);
            utterances.addLast(pcm);
        }
        return result;
    }
    byte[] takeAudio() { synchronized (utterances) { return utterances.isEmpty() ? new byte[0] : utterances.removeFirst(); } }
    @Override public void close() {
        audio.clear();
        synchronized (utterances) { for (byte[] pcm : utterances) Arrays.fill(pcm, (byte) 0); utterances.clear(); }
        super.close();
    }
}
