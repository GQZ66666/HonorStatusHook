package com.harmony.batteryfix;

import android.content.Context;
import android.os.Bundle;

/**
 * 把系统界面里的 hook 状态回写到模块 App（通过只读 Provider 的 report 方法），
 * 这样设置页不用 adb 也能看到“到底哪条绘制路径被触发了”。
 */
public final class Diag {

    private Diag() {
    }

    public static void report(Context ctx, String key, String value) {
        if (ctx == null || key == null) {
            return;
        }
        try {
            Bundle b = new Bundle();
            b.putString(key, value);
            ctx.getContentResolver().call(Prefs.URI, PrefsProvider.METHOD_REPORT, null, b);
        } catch (Throwable ignored) {
        }
    }
}
