package org.duckdns.dunevoice.phone;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.net.*;
import java.util.UUID;
import org.json.JSONObject;
import org.duckdns.dunevoice.Net;
import org.duckdns.dunevoice.core.Endpoint;

/** One command to the paired Dune; returns Dune's own reply ("Ищу: …"). Call off the main thread. */
final class Remote {
    private Remote() {}
    static Boolean viewing(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("phone", 0);
        String token = prefs.getString("token", "");
        if (token.isEmpty()) throw new IllegalStateException("Сначала подключите телефон к Dune");
        JSONObject reply = Net.post(Endpoint.fromAddress(prefs.getString("address", "")), "/playback", token, new JSONObject());
        if (!reply.optBoolean("known") || !(reply.opt("viewing") instanceof Boolean)) return null;
        return reply.getBoolean("viewing");
    }
    static String send(Context context, String text) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("phone", 0);
        String token = prefs.getString("token", "");
        if (token.isEmpty()) throw new IllegalStateException("Сначала подключите телефон к Dune");
        JSONObject request = new JSONObject().put("id", UUID.randomUUID().toString()).put("text", text);
        JSONObject reply = Net.post(Endpoint.fromAddress(prefs.getString("address", "")), "/command", token, request);
        return reply.optString("message", "Готово");
    }
    static String explain(Exception error) {
        if (error instanceof ConnectException || error instanceof SocketTimeoutException || error instanceof NoRouteToHostException)
            return "Dune не отвечает. Она включена и в той же сети?";
        if (error instanceof IllegalStateException || (error instanceof IOException && error.getMessage() != null && !error.getMessage().isEmpty()))
            return error.getMessage();
        return "Нет связи с Dune";
    }
}
