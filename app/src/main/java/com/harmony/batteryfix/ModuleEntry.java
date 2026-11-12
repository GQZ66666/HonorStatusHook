package com.harmony.batteryfix;

import android.app.Application;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 入口：只注入系统界面进程。
 */
public final class ModuleEntry implements IXposedHookLoadPackage {

    public static final String TAG = "HarmonyBattFix";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        final String pkg = lpparam.packageName;
        if (!"com.android.systemui".equals(pkg) && !"com.hihonor.systemui".equals(pkg)) {
            return;
        }
        XposedBridge.log(TAG + ": loading into " + pkg);

        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Context ctx = (Context) param.args[0];
                        if (ctx == null || !pkg.equals(ctx.getPackageName())) {
                            return;
                        }
                        try {
                            install(ctx, lpparam.classLoader);
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }
                    }
                });
    }

    private void install(Context ctx, ClassLoader cl) {
        PrefsClient.init(ctx);
        try {
            ctx.getContentResolver().registerContentObserver(Prefs.URI, false,
                    new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            PrefsClient.invalidate();
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": observer failed: " + t);
        }
        new CenterHook(ctx).install(cl);
    }
}
