package org.duckdns.dunevoice.core;

public final class PairGate {
    private String code;
    private long expires;
    private int attempts;
    public synchronized void open(String value, long now) {
        if (!value.matches("[0-9]{8}")) throw new IllegalArgumentException("code");
        code = value; expires = now + 180000; attempts = 0;
    }
    public synchronized boolean claim(String candidate, long now) {
        if (code == null || now >= expires || attempts >= 5) { close(); return false; }
        attempts++;
        if (!code.equals(candidate)) return false;
        close(); return true;
    }
    public synchronized boolean isOpen(long now) { return code != null && now < expires && attempts < 5; }
    public synchronized void close() { code = null; expires = 0; attempts = 0; }
}
