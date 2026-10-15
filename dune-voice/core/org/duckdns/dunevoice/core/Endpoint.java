package org.duckdns.dunevoice.core;

public final class Endpoint {
    public static final int PORT = 18766;
    private Endpoint() {}
    /** No arbitrary URLs, redirects, credentials, DNS names or public addresses. */
    public static String fromAddress(String input) {
        String value = input == null ? "" : input.trim();
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("Введите локальный IPv4-адрес Dune, например 192.168.1.20");
        int[] ip = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("[0-9]{1,3}")) throw new IllegalArgumentException("Некорректный адрес Dune");
            ip[i] = Integer.parseInt(parts[i]);
            if (ip[i] > 255 || !parts[i].equals(Integer.toString(ip[i]))) throw new IllegalArgumentException("Некорректный адрес Dune");
        }
        if (!(ip[0] == 10 || (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31) || (ip[0] == 192 && ip[1] == 168)))
            throw new IllegalArgumentException("Нужен адрес Dune в домашней сети");
        return "http://" + value + ":" + PORT;
    }
}
