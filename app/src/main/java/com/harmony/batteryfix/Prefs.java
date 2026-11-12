package com.harmony.batteryfix;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;

public final class Prefs {

    public static final String PKG = "com.harmony.batteryfix";
    public static final String FILE = "battery_fix";

    public static final String K_ENABLED = "enabled";
    public static final String K_OFFSET = "offset_dp";

    public static final Uri URI =
            Uri.parse("content://" + PrefsProvider.AUTHORITY + "/config");

    private Prefs() {
    }

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static Config load(Context c) {
        return Config.fromPrefs(sp(c));
    }

    public static void save(Context c, Config cfg) {
        cfg.normalize();
        SharedPreferences.Editor e = sp(c).edit();
        cfg.writeTo(e);
        e.apply();
        makeWorldReadable(c);
        try {
            c.getContentResolver().notifyChange(URI, null);
        } catch (Throwable ignored) {
        }
    }

    /** 给仍使用 XSharedPreferences 的旧环境留后路 */
    public static void makeWorldReadable(Context c) {
        try {
            File dir = new File(c.getApplicationInfo().dataDir, "shared_prefs");
            File f = new File(dir, FILE + ".xml");
            //noinspection ResultOfMethodCallIgnored
            dir.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            dir.setExecutable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
        } catch (Throwable ignored) {
        }
    }
}
