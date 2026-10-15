package org.duckdns.dunevoice.tv;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import org.duckdns.dunevoice.Net;

/** Lab-only build: pair, authenticate and disconnect without device commands. */
public final class GatewayProbe extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); Gateway gateway = null; boolean owned = false, touched = false; int status = 1;
        try {
            gateway = RemoteKeys.gateway();
            if (gateway == null) { gateway = new Gateway(getTargetContext()); gateway.start(); owned = true; }
            if (gateway.paired()) throw new IllegalStateException("Skip lab test on an already paired device");
            touched = true;
            Thread.sleep(400);
            String endpoint = "http://127.0.0.1:18766";
            String code = gateway.openPairing();
            String token = Net.post(endpoint, "/pair", "", new JSONObject().put("code", code)).getString("token");
            if (!token.matches("[A-Za-z0-9_-]{43}")) throw new AssertionError("token format");
            boolean playbackRejected = false;
            try { Net.post(endpoint, "/playback", "", new JSONObject()); } catch (Exception expected) { playbackRejected = true; }
            if (!playbackRejected) throw new AssertionError("anonymous playback status accepted");
            JSONObject playback = Net.post(endpoint, "/playback", token, new JSONObject());
            if (!(playback.opt("known") instanceof Boolean)) throw new AssertionError("missing playback status");
            boolean rejected = false;
            try { Net.post(endpoint, "/pair", "", new JSONObject().put("code", code)); } catch (Exception expected) { rejected = true; }
            if (!rejected) throw new AssertionError("reused pairing code");
            JSONObject invalid = new JSONObject().put("id", "00000000-0000-0000-0000-000000000001").put("text", "");
            for (int i = 0; i < 2; i++) {
                rejected = false;
                try { Net.post(endpoint, "/command", token, invalid); } catch (Exception expected) { rejected = true; }
                if (!rejected) throw new AssertionError("empty command executed");
            }
            gateway.disconnectPhone(); rejected = false;
            try { Net.post(endpoint, "/command", token, invalid); } catch (Exception expected) { rejected = true; }
            if (!rejected) throw new AssertionError("disconnected token accepted");
            rejected = false;
            try { Net.post(endpoint, "/playback", token, new JSONObject()); } catch (Exception expected) { rejected = true; }
            if (!rejected) throw new AssertionError("disconnected playback token accepted");
            result.putString("result", "PASS LAN pairing, single-use code, authenticated parsing, disconnect; no device commands");
            status = -1;
        } catch (Throwable error) {
            result.putString("failure", error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally { if (gateway != null && touched) gateway.disconnectPhone(); if (owned && gateway != null) gateway.close(); }
        finish(status, result);
    }
}
