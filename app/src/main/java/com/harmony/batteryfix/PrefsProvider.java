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

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_CONFIG.equals(method) || !isTrustedCaller()) {
            return null;
        }
        Context ctx = getContext();
        if (ctx == null) {
            return null;
        }
        return Prefs.load(ctx).toBundle();
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
