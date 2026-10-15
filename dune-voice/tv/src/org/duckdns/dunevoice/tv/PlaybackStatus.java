package org.duckdns.dunevoice.tv;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONObject;

/** Read-only native-player status. Never wakes Dune or requests audio focus. */
final class PlaybackStatus {
    private long checked = -10000;
    private JSONObject cached;
    synchronized JSONObject read(Context context) throws Exception {
        long now = SystemClock.elapsedRealtime();
        if (cached != null && now - checked < 2000) return cached;
        Boolean viewing = null;
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (!power.isInteractive()) viewing = false;
        else {
            try {
                String state = Actions.status().optString("player_state");
                if (state.endsWith("_playback")) viewing = true; // includes paused playback
                else if (state.equals("navigator")) viewing = false;
            } catch (Exception unavailable) { /* Unknown must not unmute a paired phone. */ }
        }
        cached = new JSONObject().put("ok", true).put("known", viewing != null);
        if (viewing != null) cached.put("viewing", viewing);
        checked = SystemClock.elapsedRealtime();
        return cached;
    }
}
