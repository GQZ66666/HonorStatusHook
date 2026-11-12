package com.harmony.batteryfix;

import android.content.SharedPreferences;
import android.os.Bundle;

/**
 * 配置快照：是否启用修复 + 手动偏移（dp）。
 */
public final class Config {

    public static final float OFFSET_MIN = -3.0f;
    public static final float OFFSET_MAX = 3.0f;

    public boolean enabled = true;
    /** 正数向下、负数向上，单位 dp */
    public float offsetDp = 0f;

    public void normalize() {
        if (Float.isNaN(offsetDp) || Float.isInfinite(offsetDp)) {
            offsetDp = 0f;
        }
        if (offsetDp < OFFSET_MIN) {
            offsetDp = OFFSET_MIN;
        }
        if (offsetDp > OFFSET_MAX) {
            offsetDp = OFFSET_MAX;
        }
    }

    public Config copy() {
        Config c = new Config();
        c.enabled = enabled;
        c.offsetDp = offsetDp;
        return c;
    }

    public void writeTo(SharedPreferences.Editor e) {
        e.putBoolean(Prefs.K_ENABLED, enabled)
                .putFloat(Prefs.K_OFFSET, offsetDp);
    }

    public static Config fromPrefs(SharedPreferences p) {
        Config c = new Config();
        if (p != null) {
            c.enabled = p.getBoolean(Prefs.K_ENABLED, true);
            c.offsetDp = p.getFloat(Prefs.K_OFFSET, 0f);
        }
        c.normalize();
        return c;
    }

    public Bundle toBundle() {
        Bundle b = new Bundle();
        b.putBoolean(Prefs.K_ENABLED, enabled);
        b.putFloat(Prefs.K_OFFSET, offsetDp);
        return b;
    }

    public static Config fromBundle(Bundle b) {
        Config c = new Config();
        if (b != null) {
            c.enabled = b.getBoolean(Prefs.K_ENABLED, true);
            c.offsetDp = b.getFloat(Prefs.K_OFFSET, 0f);
        }
        c.normalize();
        return c;
    }
}
