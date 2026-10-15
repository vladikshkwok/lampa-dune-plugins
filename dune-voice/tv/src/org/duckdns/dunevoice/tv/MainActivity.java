package org.duckdns.dunevoice.tv;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.*;
import java.net.*;
import java.util.*;
import org.duckdns.dunevoice.Screen;

/** TV home: status on the left, D-pad actions on the right, big pairing code when asked. */
public final class MainActivity extends Activity {
    private Screen screen;
    private Screen.Status keyStatus, phoneStatus, lampaStatus, batteryStatus;
    private Screen.Box codePanel;
    private TextView code, codeHint, codeTimer, message;
    private Button pairButton, unpairButton;
    private long codeUntil;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() { public void run() { refresh(); main.postDelayed(this, 1000); } };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        screen = new Screen(this, true);
        Screen.Box r = screen.root;
        r.title("Голос для Dune");
        r.muted("Кнопка микрофона на пульте или «Эй, Дюна» с телефона");
        codePanel = r.card();
        codePanel.caption("Код для телефона");
        code = codePanel.label("", 54, Screen.TEXT, true); code.setLetterSpacing(0.12f); code.setGravity(Gravity.CENTER);
        codeTimer = codePanel.muted(""); codeTimer.setGravity(Gravity.CENTER);
        codeHint = codePanel.text(""); codeHint.setGravity(Gravity.CENTER);
        codePanel.visible(false);
        Screen.Box columns = r.row();
        Screen.Box left = columns.column(), right = columns.column();

        Screen.Box state = left.card();
        state.caption("Состояние");
        keyStatus = state.status(""); phoneStatus = state.status(""); lampaStatus = state.status(""); batteryStatus = state.status("");
        message = state.muted("");


        Screen.Box tips = left.card();
        tips.caption("Что сказать");
        tips.text("«Найди фильм Зелёная миля»\n«Покажи сериал Друзья»\n«Найди в ютубе обзор айфона»\n«Пауза» · «Продолжи» · «Стоп»\n«Громче» · «Тише» · «Без звука»\n«Домой» · «Назад» · «Выключи приставку»");
        tips.muted("Фильмы ищутся в Lampa, видео — в SmartTube или YouTube. Что смотреть, вы выбираете сами.");

        Screen.Box actions = right.card();
        actions.caption("Действия");
        Button say = actions.primary("🎤  Сказать команду", v -> startActivity(new Intent(this, VoiceActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        pairButton = actions.secondary("📱  Подключить телефон", v -> pair());
        unpairButton = actions.secondary("Отключить телефон", v -> { Gateway g = RemoteKeys.gateway(); if (g != null) g.disconnectPhone(); codeUntil = 0; refresh(); });
        actions.secondary("Включить кнопку микрофона", v -> openAccessibility());
        actions.secondary("Сделать помощником по умолчанию", v -> chooseAssistant());
        actions.muted("Если пульт по-прежнему открывает другого помощника, выберите здесь «Голос для Dune».");
        say.requestFocus();
    }

    private void pair() {
        Gateway gateway = RemoteKeys.gateway();
        if (gateway == null) { say("Сначала включите кнопку микрофона — без неё телефон не сможет подключиться."); openAccessibility(); return; }
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (!power.isIgnoringBatteryOptimizations(getPackageName())) {
            say("Разрешите фоновую работу, затем снова нажмите «Подключить телефон».");
            try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + getPackageName()))); }
            catch (ActivityNotFoundException missing) { say("Не удалось открыть разрешение фоновой работы"); }
            return;
        }
        String value = gateway.openPairing();
        code.setText(value.substring(0, 4) + " " + value.substring(4));
        codeHint.setText("На телефоне откройте «Эй, Дюна», выберите эту приставку или введите адрес:\n" + addresses());
        codeUntil = SystemClock.elapsedRealtime() + 180000;
        codePanel.visible(true); screen.scroll.smoothScrollTo(0, 0); refresh();
    }
    private void openAccessibility() {
        for (String action : new String[]{Settings.ACTION_ACCESSIBILITY_SETTINGS, "android.settings.ACCESSIBILITY_TV_OEM_LINK", Settings.ACTION_SETTINGS}) {
            try { startActivity(new Intent(action)); say("Включите службу «Кнопка микрофона Dune» в разделе специальных возможностей."); return; }
            catch (ActivityNotFoundException | SecurityException next) { /* try the next screen */ }
        }
        say("Откройте Настройки → Специальные возможности и включите «Кнопка микрофона Dune».");
    }
    private void chooseAssistant() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager roles = getSystemService(RoleManager.class);
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) { startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), 2); return; }
            }
            startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));
        } catch (ActivityNotFoundException missing) { say("Выберите «Голос для Dune» в настройках приложений по умолчанию"); }
    }
    private void say(String text) { message.setText(text); }

    private void refresh() {
        Gateway gateway = RemoteKeys.gateway();
        boolean keys = gateway != null, phone = keys && gateway.paired();
        keyStatus.set(keys ? Screen.OK : Screen.WARN, keys ? "Кнопка микрофона включена" : "Кнопка микрофона выключена");
        phoneStatus.set(phone ? Screen.OK : Screen.MUTED, phone ? "Телефон подключён" : "Телефон не подключён");
        boolean lampa;
        try { getPackageManager().getPackageInfo("top.rootu.lampa", 0); lampa = true; } catch (PackageManager.NameNotFoundException e) { lampa = false; }
        lampaStatus.set(lampa ? Screen.OK : Screen.BAD, lampa ? "Lampa установлена" : "Lampa не установлена — поиск недоступен");
        boolean battery = ((PowerManager) getSystemService(POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName());
        batteryStatus.set(battery ? Screen.OK : Screen.MUTED, battery ? "Фоновая работа разрешена" : "Фоновая работа не разрешена (нужна для телефона)");
        unpairButton.setVisibility(phone ? android.view.View.VISIBLE : android.view.View.GONE);
        pairButton.setText(phone ? "📱  Подключить другой телефон" : "📱  Подключить телефон");
        long left = codeUntil - SystemClock.elapsedRealtime();
        if (codeUntil != 0 && (left <= 0 || gateway == null || !gateway.pairingOpen())) {
            codeUntil = 0; codePanel.visible(false);
            say(phone && left > 0 ? "✓ Телефон подключён. Скажите на телефоне «Эй, Дюна»." : "Код больше не действует. Нажмите «Подключить телефон», чтобы получить новый.");
        }
        if (codeUntil != 0) codeTimer.setText(String.format(Locale.ROOT, "действует ещё %d:%02d", left / 60000, left / 1000 % 60));
    }
    private String addresses() {
        ArrayList<String> values = new ArrayList<>();
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp() || iface.isLoopback()) continue;
                for (InetAddress address : Collections.list(iface.getInetAddresses()))
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) values.add(address.getHostAddress());
            }
        } catch (Exception ignored) {}
        return values.isEmpty() ? "смотрите в настройках сети Dune" : android.text.TextUtils.join(" / ", values);
    }
    @Override public void onResume() { super.onResume(); main.post(tick); }
    @Override public void onPause() { main.removeCallbacks(tick); super.onPause(); }
}
