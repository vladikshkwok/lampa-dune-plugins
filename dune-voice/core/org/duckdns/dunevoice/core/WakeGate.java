package org.duckdns.dunevoice.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Accepts final utterances only. Wake phrase arms one command for eight seconds.
 *
 * The bundled small Vosk model rarely spells the phrase exactly: real outputs
 * include «эйдена», «эй диона», «все диона», «диона». The phrase therefore
 * matches a set of observed spellings at the start of an utterance; the
 * name alone («дюна») stays exact so common names like «Дина» do not wake it.
 */
public final class WakeGate {
    private static final String LEAD = "(?:эй|ей|хей|эх|ай|и|все|всей)";
    private static final String NAME = "(?:дюн[аеуы]|дьюн[аы]|дион[аеы]|дьон[аы])";
    private static final String NEAR = "(?:" + NAME + "|ден[аы]|дон[аы]|дун[аяы]|дин[аы])";
    private static final Pattern WAKE = Pattern.compile(
        "^(?:" + LEAD + " " + NEAR + "|(?:эй|ей|хей)" + NEAR + "|" + NAME + ")(?: |$)");
    private long armedUntil;

    public static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace('ё', 'е')
            .replaceAll("[.!?,;:«»\"“”-]+", " ").trim().replaceAll("\\s+", " ");
    }
    /** Text after the wake phrase, "" for the phrase alone, null when absent. */
    public static String strip(String text) {
        String value = normalize(text);
        Matcher match = WAKE.matcher(value);
        return match.find() ? value.substring(match.end()).trim() : null;
    }
    /** Same wake matcher, preserving punctuation/case/numbers in the remaining command. */
    public static String stripOriginal(String text) {
        if (text == null) return null;
        Matcher wake = WAKE.matcher(normalize(text));
        if (!wake.find()) return null;
        int words = wake.group().trim().split(" +").length;
        Matcher tokens = Pattern.compile("[\\p{L}\\p{N}]+").matcher(text);
        for (int i = 0; i < words; i++) if (!tokens.find()) return null;
        return text.substring(tokens.end()).replaceFirst("^[\\s,.!?:;—–-]+", "").trim();
    }
    public synchronized String accept(String text, long now) {
        String value = normalize(text);
        String rest = strip(value);
        if (rest != null && rest.isEmpty()) { armedUntil = now + 8000; return null; }
        if (rest != null) { armedUntil = 0; return rest; }
        if (armedUntil > now && !value.isEmpty()) { armedUntil = 0; return value; }
        if (armedUntil <= now) armedUntil = 0;
        return null;
    }
    public synchronized void arm(long now) { armedUntil = now + 8000; }
    public synchronized boolean armed(long now) { return armedUntil > now; }
    public synchronized void reset() { armedUntil = 0; }
}
