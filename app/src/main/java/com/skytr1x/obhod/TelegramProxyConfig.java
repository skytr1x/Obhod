package com.skytr1x.obhod;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

public final class TelegramProxyConfig {
    public static final String HOST = "127.0.0.1";
    public static final int PORT = 1443;

    private static final String PREFS = "telegram_mtproxy";
    private static final String SECRET_KEY = "secret";

    private TelegramProxyConfig() {}

    public static synchronized String getOrCreateSecret(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String current = preferences.getString(SECRET_KEY, null);
        if (current != null && current.matches("[0-9a-fA-F]{32}")) {
            return current.toLowerCase();
        }

        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder secret = new StringBuilder(32);
        for (byte value : bytes) {
            secret.append(String.format("%02x", value & 0xff));
        }
        String generated = secret.toString();
        preferences.edit().putString(SECRET_KEY, generated).apply();
        return generated;
    }

    public static String getTelegramLink(Context context) {
        return "tg://proxy?server=" + HOST + "&port=" + PORT +
               "&secret=dd" + getOrCreateSecret(context);
    }
}
