package org.duckdns.dunevoice.tv;

import android.accessibilityservice.*;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public final class RemoteKeys extends AccessibilityService {
    static volatile RemoteKeys current;
    @Override public void onServiceConnected() {
        current = this;
        AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);
    }
    @Override protected boolean onKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key != KeyEvent.KEYCODE_SEARCH && key != KeyEvent.KEYCODE_ASSIST && key != KeyEvent.KEYCODE_VOICE_ASSIST) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            startActivity(new Intent(this, VoiceActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }
        return true;
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
    @Override public void onDestroy() { current = null; super.onDestroy(); }
    static boolean back() { RemoteKeys service = current; return service != null && service.performGlobalAction(GLOBAL_ACTION_BACK); }
}
