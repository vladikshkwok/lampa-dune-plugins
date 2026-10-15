package org.duckdns.dunevoice.core;

/** A second transcript may improve a search, but cannot turn it into a device action. */
public final class SearchRefinement {
    private SearchRefinement() {}
    public static boolean eligible(String text) {
        try { Command command = Commands.parse(text); return command.action.equals("search") || command.action.equals("youtube") && !command.argument.isEmpty(); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    public static String choose(String original, String transcript) {
        if (!eligible(original) || transcript == null || transcript.trim().isEmpty()) return original;
        String stripped = WakeGate.stripOriginal(transcript);
        String candidate = stripped == null ? transcript.trim() : stripped;
        try {
            Command before = Commands.parse(original), after = Commands.parse(candidate);
            if (before.action.equals(after.action) && !after.argument.isEmpty()) return candidate;
        } catch (IllegalArgumentException invalid) { /* Keep the first transcript. */ }
        return original;
    }
}
