package org.duckdns.dunevoice.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Commands {
    private Commands() {}
    public static Command parse(String text) {
        if (text == null || text.length() > 300) throw new IllegalArgumentException("Слишком длинная команда");
        String original = text.trim().replaceAll("\\s+", " ");
        if (original.isEmpty()) throw new IllegalArgumentException("Не расслышал команду");
        String control = original.toLowerCase(Locale.ROOT).replace('ё', 'е')
            .replaceAll("[.!?,;:«»\"“”]+", " ").trim().replaceAll("\\s+", " ")
            .replaceAll("^пожалуйста | пожалуйста$", "");
        if (control.matches("отмена|отменить|не надо|выключи микрофон|хватит слушать")) return new Command("cancel", "");
        if (control.matches("пауза|на паузу|поставь на паузу")) return new Command("pause", "");
        if (control.matches("продолжи|продолжай|продолжи просмотр|сними с паузы|возобнови")) return new Command("resume", "");
        if (control.matches("стоп|останови(?: фильм|видео|воспроизведение)?")) return new Command("stop", "");
        if (control.matches("(?:сделай )?(?:громче|погромче|прибавь(?: звук|громкость)?)")) return new Command("louder", "");
        if (control.matches("(?:сделай )?(?:тише|потише|убавь(?: звук|громкость)?)")) return new Command("quieter", "");
        if (control.matches("без звука|выключи звук|отключи звук")) return new Command("mute", "");
        if (control.matches("включи звук|верни звук")) return new Command("unmute", "");
        if (control.matches("домой|на главную|главная|главный экран")) return new Command("home", "");
        if (control.matches("назад|вернись|вернись назад")) return new Command("back", "");
        if (control.matches("(?:включи|разбуди)(?: дюну| dune| приставку)|проснись")) return new Command("wake", "");
        if (control.matches("(?:выключи|выключить|усыпи)(?: дюну| dune|приставку)?|режим ожидания")) return new Command("standby", "");
        if (control.matches("(?:открой|запусти|включи)(?: приложение)? (?:лампу|лампа|lampa)")) return new Command("lampa", "");
        if (control.matches("(?:найди|поищи|покажи|поставь)(?: фильм|кино|сериал)?")) throw new IllegalArgumentException("Назовите фильм для поиска");
        Matcher prefix = Pattern.compile("^(?:(?:дюна|dune)[, ]+)?(?:пожалуйста[, ]+)?(?:найди|поищи|покажи|включи|запусти|поставь|открой|хочу посмотреть|давай посмотрим)(?:те)?[, ]+", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(original);
        String query = original;
        if (prefix.find()) {
            query = original.substring(prefix.end()).replaceFirst("(?iu)^(?:мне|нам) +", "")
                .replaceFirst("(?iu)^в (?:лампе|lampa)[, ]+", "")
                .replaceFirst("(?iu)^(?:фильм|кино|сериал|мультфильм|мультик|аниме) +", "")
                .replaceFirst("(?iu)^под названием +", "").replaceFirst("(?iu)[, ]+(?:в лампе|в lampa|пожалуйста)[.!?]*$", "").trim();
            if (query.matches("(?iu)(?:во? встроенном поиске.*|.* во? встроенном поиске[.!?]*)"))
                throw new IllegalArgumentException("Встроенный поиск пока не подключён. Можно искать в Lampa.");
        }
        // Preserve titles and proper names exactly; never run linguistic guesses
        // or silently substitute another recognition alternative.
        if (query.length() > 1 && ((query.startsWith("«") && query.endsWith("»")) ||
                (query.startsWith("\"") && query.endsWith("\"")) || (query.startsWith("“") && query.endsWith("”")))) {
            query = query.substring(1, query.length() - 1).trim();
        }
        if (!Pattern.compile("[\\p{L}\\p{N}]").matcher(query).find()) throw new IllegalArgumentException("Не расслышал название");
        return new Command("search", query);
    }
}
