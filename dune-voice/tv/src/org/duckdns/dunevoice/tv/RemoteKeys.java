package org.duckdns.dunevoice.tv;

import android.accessibilityservice.*;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public final class RemoteKeys extends AccessibilityService {
    static volatile RemoteKeys current;
    private Gateway gateway;
    @Override public void onServiceConnected() {
        current = this;
        AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);
        gateway = new Gateway(this); gateway.start();
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
    @Override public void onDestroy() { current = null; if (gateway != null) gateway.close(); super.onDestroy(); }
    static boolean back() { RemoteKeys service = current; return service != null && service.performGlobalAction(GLOBAL_ACTION_BACK); }
    static Gateway gateway() { RemoteKeys service = current; return service == null ? null : service.gateway; }
}
