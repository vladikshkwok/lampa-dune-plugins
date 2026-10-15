package org.duckdns.dunevoice.core;

/** Prevent duplicate final results and callbacks from cancelled recognizers. */
public final class ResultGate {
    private long generation;
    private boolean pending;
    public synchronized long start() { pending = true; return ++generation; }
    public synchronized boolean claim(long candidate) {
        if (!pending || candidate != generation) return false;
        pending = false; return true;
    }
    public synchronized void cancel() { pending = false; generation++; }
}
