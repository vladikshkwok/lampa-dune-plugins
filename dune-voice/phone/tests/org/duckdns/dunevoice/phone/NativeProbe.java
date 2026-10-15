package org.duckdns.dunevoice.phone;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import org.vosk.*;
import java.io.InputStream;
import java.util.Arrays;
import org.duckdns.dunevoice.core.WakeGate;

/** Included only by `build.sh phone probe`, never in the distributed APK. */
public final class NativeProbe extends Instrumentation {
    private boolean discovery;
    private boolean viewing;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); discovery = arguments != null && "true".equals(arguments.getString("discovery")); viewing = arguments != null && "true".equals(arguments.getString("viewing")); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (viewing) { ViewingProbe.run(this); result.putString("result", "PASS native microphone release/resume with screen off, startup during playback, unknown/paused state, manual stop; authenticated status only"); finish(-1, result); return; }
            if (discovery) { DiscoveryProbe.run(this); result.putString("result", "PASS mDNS discovery, resolved IPv4, HTTP identity validation, lifecycle cancellation; no pairing or commands"); finish(-1, result); return; }
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            String acoustic = "";
            try (Model model = new Model(ModelFiles.prepare(getTargetContext())); Recognizer recognizer = new Recognizer(model, 16000.0f)) {
                byte[] silence = new byte[32000];
                for (int i = 0; i < 3; i++) recognizer.acceptWaveForm(silence, silence.length);
                if (!new JSONObject(recognizer.getFinalResult()).optString("text").isEmpty()) throw new AssertionError("silence became a command");
                if (Arrays.asList(getTargetContext().getAssets().list("")).contains("wake-test.pcm")) {
                    recognizer.reset(); StringBuilder text = new StringBuilder();
                    try (InputStream input = getTargetContext().getAssets().open("wake-test.pcm")) {
                        byte[] buffer = new byte[4096]; int count;
                        while ((count = input.read(buffer)) != -1)
                            if (recognizer.acceptWaveForm(buffer, count)) text.append(new JSONObject(recognizer.getResult()).optString("text", "")).append(' ');
                    }
                    text.append(new JSONObject(recognizer.getFinalResult()).optString("text", ""));
                    String command = new WakeGate().accept(text.toString().trim(), 10000);
                    if (!"пауза".equals(command)) throw new AssertionError("Synthetic wake phrase decoded as: " + text);
                    acoustic = "; synthetic Russian wake phrase -> pause";
                }
            }
            result.putString("result", "PASS native Vosk, packaged Russian model, silence does not trigger command" + acoustic);
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("failure", error.getClass().getSimpleName() + ": " + error.getMessage()); finish(1, result);
        }
    }
}
