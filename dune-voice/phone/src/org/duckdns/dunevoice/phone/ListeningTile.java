package org.duckdns.dunevoice.phone;

import android.app.PendingIntent;
import android.content.*;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** User-controlled microphone switch in the system Quick Settings panel. */
public final class ListeningTile extends TileService {
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> update();
    @Override public void onStartListening() {
        super.onStartListening(); getSharedPreferences("phone", 0).registerOnSharedPreferenceChangeListener(changes); update();
    }
    @Override public void onStopListening() {
        getSharedPreferences("phone", 0).unregisterOnSharedPreferenceChangeListener(changes); super.onStopListening();
    }
    private void update() {
        Tile tile = getQsTile(); if (tile == null) return;
        boolean active = getSharedPreferences("phone", 0).getBoolean("listening", false) && ListeningService.isRunning(this);
        tile.setLabel("Эй, Дюна"); tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(active ? "Микрофон включён" : "Микрофон выключен");
        tile.updateTile();
    }
    @Override public void onClick() {
        super.onClick();
        if (ListeningService.isRunning(this)) {
            stopService(new Intent(this, ListeningService.class));
            getSharedPreferences("phone", 0).edit().putBoolean("listening", false).putString("state", "Прослушивание выключено").apply();
            update(); return;
        }
        Runnable start = () -> {
            Intent intent = new Intent(this, TileStartActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            else startActivityAndCollapse(intent);
        };
        if (isLocked()) unlockAndRun(start); else start.run();
    }
}
