package com.harmony.batteryfix;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/**
 * 跨进程只读配置通道（Android 11+ SELinux 下 XSharedPreferences 不可靠）。
 * 只允许本模块 / 系统界面读取，仅暴露 UI 偏好。
 */
public final class PrefsProvider extends ContentProvider {

    public static final String AUTHORITY = Prefs.PKG + ".prefs";
    public static final String METHOD_CONFIG = "config";
    public static final String METHOD_REPORT = "report";
    public static final String METHOD_DIAG = "diag";
    public static final String DIAG_FILE = "diag";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!isTrustedCaller()) {
            return null;
        }
        Context ctx = getContext();
        if (ctx == null) {
            return null;
        }
        if (METHOD_CONFIG.equals(method)) {
            return Prefs.load(ctx).toBundle();
        }
        if (METHOD_REPORT.equals(method)) {
            storeReport(ctx, extras);
            return null;
        }
        if (METHOD_DIAG.equals(method)) {
            return loadDiag(ctx);
        }
        return null;
    }

    private static void storeReport(Context ctx, Bundle extras) {
        if (extras == null) {
            return;
        }
        String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date());
        android.content.SharedPreferences.Editor e = ctx
                .getSharedPreferences(DIAG_FILE, Context.MODE_PRIVATE).edit();
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            if (value != null) {
                e.putString(key, value + "  @" + time);
            }
        }
        e.apply();
    }

    private static Bundle loadDiag(Context ctx) {
        Bundle b = new Bundle();
        android.content.SharedPreferences sp =
                ctx.getSharedPreferences(DIAG_FILE, Context.MODE_PRIVATE);
        for (java.util.Map.Entry<String, ?> entry : sp.getAll().entrySet()) {
            Object v = entry.getValue();
            if (v instanceof String) {
                b.putString(entry.getKey(), (String) v);
            }
        }
        return b;
    }

    private boolean isTrustedCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID) {
            return true;
        }
        Context ctx = getContext();
        if (ctx == null) {
            return false;
        }
        try {
            String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
            if (pkgs != null) {
                for (String p : pkgs) {
                    if (p == null) {
                        continue;
                    }
                    if (p.equals(Prefs.PKG) || p.endsWith("systemui") || p.endsWith(".systemui")) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
