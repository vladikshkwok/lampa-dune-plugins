package org.duckdns.dunevoice.phone;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;

/** Brief visible launch lets Android grant microphone access to the new FGS. */
public final class TileStartActivity extends Activity {
    private boolean launched;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        TextView text = new TextView(this); text.setText("Включаю «Эй, Дюна»…"); text.setTextSize(20); text.setPadding(24, 24, 24, 24); setContentView(text);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused || launched) return;
        launched = true;
        if (getSharedPreferences("phone", 0).getString("token", "").isEmpty() || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startActivity(new Intent(this, MainActivity.class)); finish(); return;
        }
        try { startForegroundService(new Intent(this, ListeningService.class)); }
        catch (RuntimeException denied) {
            getSharedPreferences("phone", 0).edit().putString("state", "Не удалось включить микрофон. Проверьте разрешения приложения.").apply();
            startActivity(new Intent(this, MainActivity.class));
        }
        getWindow().getDecorView().postDelayed(this::finish, 400);
    }
}
