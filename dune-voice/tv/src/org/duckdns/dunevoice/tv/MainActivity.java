package org.duckdns.dunevoice.tv;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import org.duckdns.dunevoice.Screen;

/** TV home: status on the left, D-pad actions on the right. */
public final class MainActivity extends Activity {
    private Screen.Status keyStatus, lampaStatus;
    private TextView message;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() { public void run() { refresh(); main.postDelayed(this, 1000); } };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        Screen screen = new Screen(this, true);
        Screen.Box r = screen.root;
        r.title("Голос для Dune");
        r.muted("Нажмите кнопку микрофона на пульте и скажите команду");
        Screen.Box columns = r.row();
        Screen.Box left = columns.column(), right = columns.column();

        Screen.Box state = left.card();
        state.caption("Состояние");
        keyStatus = state.status(""); lampaStatus = state.status("");
        message = state.muted("");

        Screen.Box tips = left.card();
        tips.caption("Что сказать");
        tips.text("«Найди фильм Зелёная миля»\n«Покажи сериал Друзья»\n«Найди в ютубе обзор айфона»\n«Пауза» · «Продолжи» · «Стоп»\n«Громче» · «Тише» · «Без звука»\n«Домой» · «Назад» · «Выключи приставку»");
        tips.muted("Фильмы ищутся в Lampa, видео — в SmartTube или YouTube. Что смотреть, вы выбираете сами.");

        Screen.Box actions = right.card();
        actions.caption("Действия");
        Button say = actions.primary("🎤  Сказать команду", v -> startActivity(new Intent(this, VoiceActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        actions.secondary("Включить кнопку микрофона", v -> openAccessibility());
        actions.secondary("Сделать помощником по умолчанию", v -> chooseAssistant());
        actions.muted("Если пульт по-прежнему открывает другого помощника, выберите здесь «Голос для Dune».");
        say.requestFocus();
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
        boolean keys = RemoteKeys.current != null;
        keyStatus.set(keys ? Screen.OK : Screen.WARN, keys ? "Кнопка микрофона включена" : "Кнопка микрофона выключена");
        boolean lampa;
        try { getPackageManager().getPackageInfo("top.rootu.lampa", 0); lampa = true; } catch (PackageManager.NameNotFoundException e) { lampa = false; }
        lampaStatus.set(lampa ? Screen.OK : Screen.BAD, lampa ? "Lampa установлена" : "Lampa не установлена — поиск недоступен");
    }
    @Override public void onResume() { super.onResume(); main.post(tick); }
    @Override public void onPause() { main.removeCallbacks(tick); super.onPause(); }
}
