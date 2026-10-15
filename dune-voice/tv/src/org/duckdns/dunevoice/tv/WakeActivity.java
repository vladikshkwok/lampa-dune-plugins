package org.duckdns.dunevoice.tv;

import android.app.Activity;
import android.os.*;

/** A visible Android activity may wake the screen without root/ADB. */
public final class WakeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 27) setTurnScreenOn(true);
        else getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) new Handler(Looper.getMainLooper()).postDelayed(this::finish, 200);
    }
}
