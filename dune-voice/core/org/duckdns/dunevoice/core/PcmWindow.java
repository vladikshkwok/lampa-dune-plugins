package org.duckdns.dunevoice.core;

import java.util.Arrays;

/** Bounded volatile PCM history. Absolute sample positions survive ring wrap. No files. */
public final class PcmWindow {
    private final short[] samples;
    private long total;
    public PcmWindow(int capacity) { samples = new short[capacity]; }
    public synchronized void append(short[] audio, int length) {
        for (int i = 0; i < length; i++) samples[(int) (total++ % samples.length)] = audio[i];
    }
    public synchronized long size() { return total; }
    public synchronized byte[] copy(long from, long to) {
        if (from < 0 || from < total - samples.length || to > total || from >= to) return new byte[0];
        byte[] pcm = new byte[(int) (to - from) * 2];
        for (int i = 0; i < pcm.length / 2; i++) {
            short value = samples[(int) ((from + i) % samples.length)];
            pcm[i * 2] = (byte) value; pcm[i * 2 + 1] = (byte) (value >> 8);
        }
        return pcm;
    }
    public synchronized void clear() { Arrays.fill(samples, (short) 0); total = 0; }
}
