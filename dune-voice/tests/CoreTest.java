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
        ok("Найди Человек-паук: Враг в отражении".equals(WakeGate.stripOriginal("Эй, Дюна! Найди Человек-паук: Враг в отражении")));
        ok("Найди «2+1»".equals(WakeGate.stripOriginal("эйдена, Найди «2+1»")));
        ok(WakeGate.stripOriginal("Найди Дюну") == null);
        ok(SearchRefinement.eligible("найди человека паук враг в отражениях"));
        ok(!SearchRefinement.eligible("пауза")); ok(!SearchRefinement.eligible("открой ютуб"));
        ok("найди Человек-паук: Враг в отражении".equals(SearchRefinement.choose("найди человека паук враг в отражениях", "Эй, Дюна, найди Человек-паук: Враг в отражении")));
        ok("Найди Дом дракона".equals(SearchRefinement.choose("найди дом драконах", "Найди Дом дракона")));
        ok("найди дом драконах".equals(SearchRefinement.choose("найди дом драконах", "Выключи приставку")));
        ok("найди дом драконах".equals(SearchRefinement.choose("найди дом драконах", "Эй Дюна")));
        ok("найди дом драконах".equals(SearchRefinement.choose("найди дом драконах", null)));
        ok("найди в ютубе Spider-Man 2".equals(SearchRefinement.choose("найди в ютубе спайдермен два", "Эй, Дюна, найди в ютубе Spider-Man 2")));
        PcmWindow pcm = new PcmWindow(4);
        pcm.append(new short[]{1, 256, -32768}, 3);
        ok(java.util.Arrays.equals(pcm.copy(0, 3), new byte[]{1, 0, 0, 1, 0, (byte) 128}));
        pcm.append(new short[]{-1, 5, 6}, 3);
        ok(pcm.size() == 6); ok(pcm.copy(0, 6).length == 0);
        ok(java.util.Arrays.equals(pcm.copy(2, 6), new byte[]{0, (byte) 128, -1, -1, 5, 0, 6, 0}));
        ok(pcm.copy(4, 7).length == 0); pcm.clear(); ok(pcm.size() == 0 && pcm.copy(0, 1).length == 0);
        ViewingGate viewing = new ViewingGate();
        ok(!viewing.allowsMicrophone()); // No brief microphone capture at startup during a film.
        viewing.sample(null, 0); ok(!viewing.allowsMicrophone());
        viewing.sample(false, 100); viewing.sample(false, 5099); ok(!viewing.allowsMicrophone());
        viewing.sample(false, 5100); ok(viewing.allowsMicrophone());
        viewing.sample(true, 5200); ok(!viewing.allowsMicrophone());
        viewing.sample(false, 5300); viewing.sample(null, 5400);
        viewing.sample(false, 11000); ok(!viewing.allowsMicrophone()); // Unknown breaks exit confirmation.
        viewing.sample(true, 12000); viewing.sample(false, 13000);
        viewing.sample(true, 18000); ok(!viewing.allowsMicrophone()); // Transient idle/seek is not an exit.
        viewing.sample(null, 25000); ok(!viewing.allowsMicrophone()); // Lost LAN never unmutes.
        viewing.sample(false, 30000); viewing.sample(false, 35000); ok(viewing.allowsMicrophone());
        viewing.sample(null, 36000); ok(viewing.allowsMicrophone()); // An idle network blip preserves mode.
        ok(Endpoint.fromAddress("192.168.1.20").equals("http://192.168.1.20:18766"));
        for (String bad : new String[]{"8.8.8.8", "192.168.1.1:80", "localhost", "192.168.1.1/path", "http://192.168.1.1", "192.168.01.1", "172.32.1.1", "192.168.1.256"}) {
            boolean rejected = false; try { Endpoint.fromAddress(bad); } catch (IllegalArgumentException e) { rejected = true; } ok(rejected);
        }
        System.out.println("PASS " + checks + " command, wake phrase, pairing and callback checks");
    }
}
