package org.duckdns.dunevoice.core;

/** Microphone stays closed until the player is known idle; unknown is never an exit. */
public final class ViewingGate {
    private volatile boolean allowed;
    private long idleSince = -1;
    private int idleSamples;
    public boolean allowsMicrophone() { return allowed; }
    public void sample(Boolean viewing, long now) {
        if (viewing == null) { idleSince = -1; idleSamples = 0; return; }
        if (viewing) { allowed = false; idleSince = -1; idleSamples = 0; return; }
        if (allowed) return;
        if (idleSince < 0) idleSince = now;
        if (++idleSamples >= 2 && now - idleSince >= 5000) allowed = true;
    }
}
