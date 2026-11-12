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

    // ------------------------------------------------------------ 模拟电量（root）

    public static String setLevel(int level) {
        int v = Math.max(0, Math.min(100, level));
        String r = su("dumpsys battery set level " + v);
        return "ok".equals(r) ? ("已模拟电量 " + v + "%") : ("设置失败：" + r);
    }

    public static String setCharging(boolean charging) {
        String r = charging
                ? su("dumpsys battery set ac 1; dumpsys battery set status 2")
                : su("dumpsys battery unplug; dumpsys battery set status 3");
        if (!"ok".equals(r)) {
            return "切换失败：" + r;
        }
        return charging ? "已切换为充电状态（AC）" : "已切换为不充电状态";
    }

    public static String resetBattery() {
        String r = su("dumpsys battery reset");
        return "ok".equals(r) ? "已恢复真实电量" : "恢复失败：" + r;
    }

    private static String su(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            int code = p.waitFor();
            return code == 0 ? "ok" : ("exit " + code);
        } catch (Throwable t) {
            String msg = t.getMessage();
            return "fail: " + (msg == null ? t.getClass().getSimpleName() : msg);
        }
    }
}
