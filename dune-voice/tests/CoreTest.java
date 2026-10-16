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
        command("выключи приставку", "standby", "");
        command("открой лампу", "lampa", "");
        command("найди в ютубе котиков", "youtube", "котиков");
        command("Найди на YouTube обзор айфона", "youtube", "обзор айфона");
        command("покажи в ютубе рецепт борща", "youtube", "рецепт борща");
        command("найди видео про космос в ютубе", "youtube", "космос");
        command("в ютубе Лучшие голы 2024", "youtube", "Лучшие голы 2024");
        command("Дюна, найди в YouTube видео про Марс", "youtube", "Марс");
        command("открой ютуб", "youtube", ""); command("YouTube", "youtube", "");
        command("найди фильм Ютуберы", "search", "Ютуберы");
        for (String bad : new String[]{"", "   ", "найди", "найди фильм", "найди !!!", "найди Дюна во встроенном поиске", "найди в ютубе", new String(new char[301])}) {
            boolean rejected = false; try { Commands.parse(bad); } catch (IllegalArgumentException e) { rejected = true; } ok(rejected);
        }
        ResultGate results = new ResultGate(); long a = results.start(); long b = results.start();
        ok(!results.claim(a)); ok(results.claim(b)); ok(!results.claim(b));
        a = results.start(); results.cancel(); ok(!results.claim(a));
        System.out.println("PASS " + checks + " command and voice result checks");
    }
}
