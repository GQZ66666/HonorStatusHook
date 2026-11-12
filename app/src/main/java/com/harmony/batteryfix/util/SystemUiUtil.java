package com.harmony.batteryfix.util;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * 识别系统界面包名，并用 root 重启它，让 LSPosed 重新注入。
 */
public final class SystemUiUtil {

    private static final String[] CANDIDATES = {
            "com.android.systemui",
            "com.hihonor.systemui"
    };

    private SystemUiUtil() {
    }

    public static String detect(Context c) {
        PackageManager pm = c.getPackageManager();
        for (String p : CANDIDATES) {
            try {
                pm.getPackageInfo(p, 0);
                return p;
            } catch (Throwable ignored) {
            }
        }
        return CANDIDATES[0];
    }

    public static String restart(Context c) {
        String pkg = detect(c);
        try {
            String pattern = pkg.replace(".", "\\.");
            String cmd = "pkill -f '^" + pattern + "$' || killall " + pkg;
            Process proc = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            int code = proc.waitFor();
            if (code == 0) {
                return "已重启系统界面（" + pkg + "），稍等片刻状态栏会重新出现";
            }
            return "root 命令返回 " + code + "，请用其它方式重启系统界面";
        } catch (Throwable t) {
            String msg = t.getMessage();
            return "重启失败：" + (msg == null ? t.getClass().getSimpleName() : msg);
        }
    }
}
