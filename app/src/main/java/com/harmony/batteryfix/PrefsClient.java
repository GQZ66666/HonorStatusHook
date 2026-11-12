package com.harmony.batteryfix;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * 系统界面进程内的配置读取：优先 ContentProvider，失败退回 XSharedPreferences。
 * 带 1.2s 缓存，避免每次绘制都跨进程。
 */
public final class PrefsClient {

    private static final long TTL_MS = 1200L;
    private static final long PROVIDER_RETRY_MS = 5000L;

    private static volatile Config sCached;
    private static volatile long sCachedAt;
    private static volatile long sNextProviderTry;

    private PrefsClient() {
    }

    public static void init(Context ctx) {
        invalidate();
    }

    public static void invalidate() {
        sCachedAt = 0L;
    }

    public static Config get(Context ctx) {
        long now = SystemClock.uptimeMillis();
        Config c = sCached;
        if (c != null && sCachedAt != 0L && now - sCachedAt < TTL_MS) {
            return c;
        }
        Config loaded = null;
        if (now >= sNextProviderTry) {
            loaded = fromProvider(ctx);
        }
        if (loaded == null) {
            loaded = fromLegacy();
        }
        if (loaded == null) {
            loaded = new Config();
        }
        sCached = loaded;
        sCachedAt = now;
        return loaded;
    }

    private static Config fromProvider(Context ctx) {
        try {
            Bundle b = ctx.getContentResolver()
                    .call(Prefs.URI, PrefsProvider.METHOD_CONFIG, null, null);
            if (b == null) {
                sNextProviderTry = SystemClock.uptimeMillis() + PROVIDER_RETRY_MS;
                return null;
            }
            sNextProviderTry = 0L;
            return Config.fromBundle(b);
        } catch (Throwable t) {
            sNextProviderTry = SystemClock.uptimeMillis() + PROVIDER_RETRY_MS;
            XposedBridge.log(ModuleEntry.TAG + ": provider read failed: " + t);
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static Config fromLegacy() {
        try {
            XSharedPreferences p = new XSharedPreferences(Prefs.PKG, Prefs.FILE);
            p.reload();
            return Config.fromPrefs(p);
        } catch (Throwable t) {
            return null;
        }
    }
}
