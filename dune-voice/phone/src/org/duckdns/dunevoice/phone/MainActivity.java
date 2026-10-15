package org.duckdns.dunevoice.phone;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.util.*;
import org.json.JSONObject;
import org.duckdns.dunevoice.*;
import org.duckdns.dunevoice.core.Endpoint;

public final class MainActivity extends Activity {
    private Screen screen;
    private SharedPreferences prefs;
    private Button mic, searchButton, pairButton, systemButton, batteryButton, changeButton;
    private TextView micState, live, remoteReply, searchStatus;
    private Screen.Status device;
    private Screen.Box remote, pairing, devices;
    private EditText address, code, query;
    private DuneDiscovery discovery;
    private boolean pairingBusy, searched, showPairing;
    private Boolean painted;
    private int deviceCount;
    private String pickedName = "";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() { public void run() { showState(); main.postDelayed(this, 400); } };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); prefs = getSharedPreferences("phone", 0);
        screen = new Screen(this, false);
        Screen.Box r = screen.root;
        r.title("Эй, Дюна"); r.muted("Голосовое управление Dune с телефона");

        Screen.Box voice = r.card();
        voice.view.setGravity(Gravity.CENTER_HORIZONTAL);
        mic = Screen.roundToggle(voice, getDrawable(R.drawable.ic_mic).mutate(), v -> toggle());
        micState = voice.heading(""); micState.setGravity(Gravity.CENTER);
        live = voice.muted(""); live.setGravity(Gravity.CENTER);

        remote = r.card();
        remote.caption("Пульт");
        query = remote.field("Фильм, сериал или видео", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_SEARCH) { search("найди "); return true; } return false; });
        Screen.Box find = remote.row();
        find.primary("Найти в Lampa", v -> search("найди ")); find.secondary("В YouTube", v -> search("найди в ютубе "));
        Screen.Box row = remote.row();
        row.secondary("Пауза", v -> send("пауза")); row.secondary("Играть", v -> send("продолжи")); row.secondary("Стоп", v -> send("стоп"));
        row = remote.row();
        row.secondary("Тише", v -> send("тише")); row.secondary("Громче", v -> send("громче")); row.secondary("Без звука", v -> send("выключи звук"));
        row = remote.row();
        row.secondary("Назад", v -> send("назад")); row.secondary("Домой", v -> send("домой"));
        row = remote.row();
        row.secondary("Lampa", v -> send("открой лампу")); row.secondary("YouTube", v -> send("открой ютуб"));
        remoteReply = remote.muted(""); remoteReply.setGravity(Gravity.CENTER);

        Screen.Box box = r.card();
        box.caption("Приставка");
        device = box.status("");
        changeButton = box.quiet("Подключить другую Dune", v -> { showPairing = true; showState(); if (!searched) { searched = true; findDune(); } });
        pairing = box.column();
        pairing.muted("1. На Dune откройте «Голос для Dune» → «Подключить телефон».\n2. Выберите приставку ниже и введите код с экрана телевизора.");
        searchButton = pairing.secondary("Найти Dune в сети", v -> findDune());
        searchStatus = pairing.muted("");
        devices = pairing.column();
        address = pairing.field("IP-адрес Dune, например 192.168.1.20", prefs.getString("address", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        code = pairing.field("Код с экрана Dune — 8 цифр", "", InputType.TYPE_CLASS_NUMBER);
        code.setSaveEnabled(false); code.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        pairButton = pairing.primary("Подключить", v -> pair());

        Screen.Box settings = r.card();
        settings.caption("Настройки");
        systemButton = settings.secondary("", v -> { prefs.edit().putBoolean("system", !prefs.getBoolean("system", true)).apply(); showState(); });
        settings.muted("Фраза «Эй, Дюна» всегда распознаётся на телефоне. Команду после паузы точнее распознаёт системный сервис (обычно Google); если он недоступен, работает встроенная модель.");
        settings.secondary("Кнопка «Эй, Дюна» в шторке", v -> addTile());
        batteryButton = settings.secondary("", v -> {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            startActivity(new Intent(power.isIgnoringBatteryOptimizations(getPackageName()) ? Settings.ACTION_APPLICATION_DETAILS_SETTINGS : Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + getPackageName())));
        });

        Screen.Box help = r.card();
        help.caption("Что сказать");
        help.text("«Эй, Дюна, найди фильм Зелёная миля»\n«Эй, Дюна» … пауза … «покажи сериал Друзья»\n«Эй, Дюна, найди в ютубе обзор айфона»\n«Эй, Дюна, пауза» · «продолжи» · «стоп»\n«Эй, Дюна, громче» · «тише» · «выключи звук»\n«Эй, Дюна, домой» · «назад» · «выключи приставку»\n«Эй, Дюна, выключи микрофон»");
        help.muted("После «Эй, Дюна» телефон коротко вибрирует — тогда говорите команду. Выбор фильма и источника остаётся на телевизоре. Аудио и фразы не сохраняются; пока включено прослушивание, расходуется батарея.");
    }

    private boolean paired() { return !prefs.getString("token", "").isEmpty(); }
    private boolean running() { return ListeningService.isRunning(this); }

    private void toggle() {
        if (running()) { stopService(new Intent(this, ListeningService.class)); prefs.edit().putBoolean("listening", false).putString("state", "Прослушивание выключено").apply(); showState(); return; }
        if (!paired()) { notice("Сначала подключите приставку — ниже, в карточке «Приставка»"); showPairing = true; showState(); return; }
        ArrayList<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!permissions.isEmpty()) { requestPermissions(permissions.toArray(new String[0]), 1); return; }
        startListening();
    }
    private void startListening() {
        try { startForegroundService(new Intent(this, ListeningService.class)); prefs.edit().putString("state", "Включаю…").apply(); }
        catch (RuntimeException error) { notice("Не удалось включить микрофон. Проверьте разрешения приложения."); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] names, int[] results) {
        super.onRequestPermissionsResult(request, names, results);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening();
        else notice("Для «Эй, Дюна» нужно разрешение на микрофон");
    }

    private void search(String prefix) {
        String title = query.getText().toString().trim();
        if (title.isEmpty()) { remoteReply.setText("Введите название"); return; }
        send(prefix + title);
    }
    private void send(String text) {
        if (!paired()) { remoteReply.setText("Сначала подключите приставку"); return; }
        remoteReply.setText("Отправляю…");
        new Thread(() -> {
            String reply;
            try { reply = Remote.send(this, text); } catch (Exception error) { reply = Remote.explain(error); }
            String result = reply; main.post(() -> remoteReply.setText(result));
        }, "dune-remote").start();
    }

    private void pair() {
        if (pairingBusy) return;
        if (running()) { notice("Выключите микрофон, чтобы сменить приставку"); return; }
        final String ip = address.getText().toString().trim(), pin = code.getText().toString().trim();
        final String endpoint;
        try { endpoint = Endpoint.fromAddress(ip); if (!pin.matches("[0-9]{8}")) throw new IllegalArgumentException("Введите 8 цифр с экрана Dune"); }
        catch (IllegalArgumentException error) { searchStatus.setText(error.getMessage()); return; }
        stopSearch(); pairingBusy = true; pairButton.setEnabled(false); searchStatus.setText("Подключаюсь…");
        final String name = pickedName;
        new Thread(() -> {
            String failure = null;
            try {
                JSONObject result = Net.post(endpoint, "/pair", "", new JSONObject().put("code", pin));
                String token = result.getString("token");
                if (!token.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("Некорректный ответ Dune");
                if (!prefs.edit().putString("address", ip).putString("name", name).putString("token", token).putString("state", "Приставка подключена. Нажмите микрофон.").commit()) throw new IllegalStateException("Не удалось сохранить подключение");
            } catch (Exception error) { failure = error instanceof java.io.IOException && error.getMessage() != null && error.getMessage().startsWith("Код") ? error.getMessage() : "Не удалось подключиться. Проверьте адрес, код и что телефон в домашней сети."; }
            String message = failure;
            main.post(() -> {
                pairingBusy = false; pairButton.setEnabled(true);
                if (message == null) { code.setText(""); showPairing = false; searchStatus.setText(""); } else searchStatus.setText(message);
                showState();
            });
        }, "dune-pair").start();
    }
    private void findDune() {
        if (pairingBusy) return;
        stopSearch(); devices.view.removeAllViews(); deviceCount = 0;
        searchButton.setEnabled(false); searchStatus.setText("Ищу Dune в домашней сети…");
        discovery = new DuneDiscovery(this, new DuneDiscovery.Listener() {
            public void found(String name, String ip) {
                deviceCount++;
                devices.secondary(name + "  ·  " + ip, v -> { address.setText(ip); pickedName = name; code.requestFocus(); searchStatus.setText("Выбрана " + name + ". Введите код с экрана телевизора."); });
                if (deviceCount == 1 && address.getText().toString().trim().isEmpty()) { address.setText(ip); pickedName = name; }
                searchStatus.setText("Найдено: " + deviceCount + ". Выберите свою Dune и введите код.");
            }
            public void finished(boolean available) {
                searchButton.setEnabled(true); discovery = null;
                if (deviceCount == 0) searchStatus.setText(available ? "Dune не найдена автоматически. Если роутер разделяет сети, введите её IP-адрес вручную (он показан на экране подключения Dune)." : "Поиск недоступен. Введите IP-адрес Dune вручную.");
            }
        });
        discovery.start();
    }
    private void stopSearch() {
        if (discovery != null) { discovery.close(); discovery = null; }
        if (searchButton != null) searchButton.setEnabled(true);
    }
    private void addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                getSystemService(android.app.StatusBarManager.class).requestAddTileService(new ComponentName(this, ListeningTile.class), "Эй, Дюна", android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_mic), getMainExecutor(), result -> {
                    if (result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) notice("Кнопка «Эй, Дюна» есть в быстрых настройках");
                    else notice("Откройте шторку → карандаш → перетащите «Эй, Дюна»");
                }); return;
            } catch (RuntimeException unavailable) { /* Older or modified system UI: manual addition. */ }
        }
        notice("Дважды раскройте шторку → карандаш / «Изменить» → перетащите «Эй, Дюна»");
    }
    private void notice(String text) { prefs.edit().putString("state", text).apply(); showState(); }

    private void showState() {
        boolean on = running(), linked = paired();
        if (prefs.getBoolean("listening", false) && !on)
            prefs.edit().putBoolean("listening", false).putString("state", "Прослушивание остановлено системой. Нажмите микрофон.").apply();
        if (painted == null || on != painted) { Screen.paintToggle(screen, mic, on); painted = on; }
        String state = prefs.getString("state", "");
        micState.setText(on ? (state.isEmpty() ? "Слушаю «Эй, Дюна»" : state) : !linked ? "Подключите приставку" : state.isEmpty() || state.startsWith("Слушаю") ? "Микрофон выключен" : state);
        String heard = ListeningService.live;
        live.setText(on ? (heard.isEmpty() ? "Нажмите, чтобы выключить" : "Слышу: " + heard) : linked ? "Нажмите, чтобы слушать «Эй, Дюна»" : "");
        remote.visible(linked);
        String name = prefs.getString("name", ""), ip = prefs.getString("address", "");
        if (linked) device.set(Screen.OK, (name.isEmpty() ? "Dune" : name) + "  ·  " + ip); else device.set(Screen.WARN, "Приставка не подключена");
        pairing.visible(!linked || showPairing); changeButton.setVisibility(linked && !showPairing ? android.view.View.VISIBLE : android.view.View.GONE);
        systemButton.setText((prefs.getBoolean("system", true) ? "✓  " : "✗  ") + "Команда через системное распознавание");
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        batteryButton.setText(power.isIgnoringBatteryOptimizations(getPackageName()) ? "✓  Работа при погашенном экране разрешена" : "Разрешить работу при погашенном экране");
    }
    @Override public void onResume() {
        super.onResume(); main.post(refresh);
        if (!searched && !paired()) { searched = true; findDune(); }
    }
    @Override public void onPause() { stopSearch(); main.removeCallbacks(refresh); super.onPause(); }
}
