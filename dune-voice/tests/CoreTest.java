import org.duckdns.dunevoice.core.*;

public final class CoreTest {
    private static int checks;
    private static void ok(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static void command(String input, String action, String argument) {
        Command c = Commands.parse(input); ok(c.action.equals(action)); ok(c.argument.equals(argument));
    }
    public static void main(String[] args) {
        command("Найди фильм Зелёная миля", "search", "Зелёная миля");
        command("Найди в лампе Зелёная миля", "search", "Зелёная миля");
        command("Найди фильм Зелёная миля в лампе", "search", "Зелёная миля");
        command("Дюна, найди фильм «2+1»", "search", "2+1");
        command("включи фильм Дюна", "search", "Дюна");
        command("включи дюну", "wake", "");
        command("Пауза!", "pause", ""); command("продолжи", "resume", "");
        command("Отмена", "cancel", "");
        command("сделай потише", "quieter", ""); command("выключи звук", "mute", "");
        command("выключи дюну", "standby", ""); command("Зелёная миля", "search", "Зелёная миля");
        command("открой лампу", "lampa", "");
        for (String bad : new String[]{"", "   ", "найди", "найди фильм", "найди !!!", "найди Дюна во встроенном поиске", new String(new char[301])}) {
            boolean rejected = false; try { Commands.parse(bad); } catch (IllegalArgumentException e) { rejected = true; } ok(rejected);
        }
        WakeGate wake = new WakeGate();
        ok(wake.accept("найди фильм", 0) == null);
        ok(wake.accept("разговор про эй дюна", 0) == null);
        ok(wake.accept("эй дюна", 100) == null); ok(wake.armed(101));
        ok("пауза".equals(wake.accept("пауза", 102))); ok(!wake.armed(103));
        ok(wake.accept("продолжи", 104) == null);
        ok("найди фильм дюна".equals(wake.accept("эй дюна найди фильм дюна", 105)));
        wake.accept("эй дюна", 200); ok(wake.accept("пауза", 8200) == null);
        wake.accept("эй дюна", 9000); wake.reset(); ok(wake.accept("пауза", 9001) == null);
        // Spellings the bundled small model actually produced for the phrase.
        for (String heard : new String[]{"эйдена найди фильм игра престолов", "эй диона найди фильм игра престолов", "все диона найди фильм игра престолов", "всей диона найди фильм игра престолов", "Эй, Дюна! Найди фильм игра престолов", "диона найди фильм игра престолов"})
            ok("найди фильм игра престолов".equals(wake.accept(heard, 20000)));
        for (String heard : new String[]{"эйдена", "эй диона", "диона", "дюна"}) { ok(wake.accept(heard, 30000) == null); ok(wake.armed(30001)); wake.reset(); }
        for (String other : new String[]{"привет как дела у дины", "дина пошла домой", "дуня иди сюда", "да не на столе", "дюнам", "найди фильм дюна", "эйден пришел"})
            { ok(wake.accept(other, 40000) == null); ok(!wake.armed(40001)); }
        PairGate pair = new PairGate(); pair.open("12345678", 0);
        for (int i = 0; i < 5; i++) ok(!pair.claim("00000000", 1));
        ok(!pair.claim("12345678", 2)); pair.open("12345678", 10);
        ok(pair.claim("12345678", 11)); ok(!pair.claim("12345678", 12));
        pair.open("12345678", 10); ok(!pair.claim("12345678", 180010));
        ResultGate results = new ResultGate(); long a = results.start(); long b = results.start();
        ok(!results.claim(a)); ok(results.claim(b)); ok(!results.claim(b));
        a = results.start(); results.cancel(); ok(!results.claim(a));
        ok(Endpoint.fromAddress("192.168.1.20").equals("http://192.168.1.20:18766"));
        for (String bad : new String[]{"8.8.8.8", "192.168.1.1:80", "localhost", "192.168.1.1/path", "http://192.168.1.1", "192.168.01.1", "172.32.1.1", "192.168.1.256"}) {
            boolean rejected = false; try { Endpoint.fromAddress(bad); } catch (IllegalArgumentException e) { rejected = true; } ok(rejected);
        }
        System.out.println("PASS " + checks + " command, wake phrase, pairing and callback checks");
    }
}
