package com.harmony.batteryfix;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.text.TextPaint;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 接管 VerticalCenteredTextHelper.optimizeDraw()。
 *
 * <p>原实现：{@code dy = h/2 - baseline - (currentText.top + currentText.bottom)/2}，
 * currentText 决定偏移，数字一变就可能上下跳 1px。</p>
 *
 * <p>本实现：{@code dy = h/2 - baseline - (refDigits.top + refDigits.bottom)/2 + offset}，
 * refDigits 是同一字体的完整数字集合，再四舍五入到整数像素。所有数字共用同一条基线。</p>
 */
public final class CenterHook {

    private static final String[] CANDIDATES = {
            "com.android.systemui.view.VerticalCenteredTextHelper",
            "com.hihonor.systemui.view.VerticalCenteredTextHelper"
    };

    private final Context ctx;
    private static final AtomicBoolean sFirstDrawLogged = new AtomicBoolean(false);

    public CenterHook(Context ctx) {
        this.ctx = ctx;
    }

    public void install(ClassLoader cl) {
        for (String name : CANDIDATES) {
            Class<?> clazz = XposedHelpers.findClassIfExists(name, cl);
            if (clazz == null) {
                continue;
            }
            int hooked = 0;
            for (Method m : clazz.getDeclaredMethods()) {
                if (!"optimizeDraw".equals(m.getName()) || !matches(m)) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        try {
                            return draw(param);
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                            return null;
                        }
                    }
                });
                hooked++;
            }
            if (hooked > 0) {
                XposedBridge.log(ModuleEntry.TAG + ": hooked " + name + " x" + hooked);
                return;
            }
        }
        XposedBridge.log(ModuleEntry.TAG + ": VerticalCenteredTextHelper not found, no-op");
    }

    /**
     * 只挂签名完全匹配的方法，避免不同 ROM 版本上误伤。
     */
    private static boolean matches(Method m) {
        Class<?>[] p = m.getParameterTypes();
        if (p.length != 6) {
            return false;
        }
        if (p[0] != Canvas.class || p[1] != int.class) {
            return false;
        }
        if (!TextPaint.class.isAssignableFrom(p[2])) {
            return false;
        }
        if (!CharSequence.class.isAssignableFrom(p[3])) {
            return false;
        }
        if (!"kotlin.jvm.functions.Function0".equals(p[4].getName())) {
            return false;
        }
        return "kotlin.jvm.functions.Function0".equals(p[5].getName());
    }

    private Object draw(XC_MethodHook.MethodHookParam param) {
        Object[] a = param.args;
        if (a == null || a.length < 6) {
            return null;
        }
        Canvas canvas = a[0] instanceof Canvas ? (Canvas) a[0] : null;
        int height = asInt(a[1]);
        TextPaint paint = a[2] instanceof TextPaint ? (TextPaint) a[2] : null;
        CharSequence text = a[3] instanceof CharSequence ? (CharSequence) a[3] : "";
        Object baselineFn = a[4];
        Object drawFn = a[5];

        if (drawFn == null) {
            return null;
        }

        int baseline = -1;
        try {
            baseline = asInt(invoke(baselineFn));
        } catch (Throwable ignored) {
        }

        if (canvas == null || paint == null || height <= 0 || baseline <= 0) {
            invoke(drawFn);
            return null;
        }

        float dy;
        try {
            dy = computeDy(paint, text, baseline, height);
        } catch (Throwable t) {
            XposedBridge.log(t);
            invoke(drawFn);
            return null;
        }

        int save = canvas.save();
        try {
            canvas.translate(0f, dy);
            invoke(drawFn);
        } finally {
            canvas.restoreToCount(save);
        }
        return null;
    }

    private float computeDy(TextPaint paint, CharSequence text, int baseline, int height) {
        Config cfg = PrefsClient.get(ctx);
        Rect bounds = new Rect();
        if (!cfg.enabled) {
            // 关闭时完全复刻原行为，保证“开关关掉 = 和系统一模一样”
            paint.getTextBounds(text, 0, text.length(), bounds);
            return height / 2f - baseline - (bounds.top + bounds.bottom) / 2f;
        }
        String ref = CenterMath.referenceDigits(text);
        paint.getTextBounds(ref, 0, ref.length(), bounds);
        float density = ctx.getResources().getDisplayMetrics().density;
        float dy = height / 2f - baseline - (bounds.top + bounds.bottom) / 2f
                + cfg.offsetDp * density;
        float rounded = Math.round(dy);
        if (sFirstDrawLogged.compareAndSet(false, true)) {
            XposedBridge.log(ModuleEntry.TAG + ": first draw h=" + height
                    + " baseline=" + baseline + " refTop=" + bounds.top
                    + " refBottom=" + bounds.bottom + " text=" + text
                    + " offsetDp=" + cfg.offsetDp + " dy=" + rounded);
        }
        return rounded;
    }

    private static Object invoke(Object fn) {
        if (fn == null) {
            return null;
        }
        try {
            return XposedHelpers.callMethod(fn, "invoke");
        } catch (Throwable t) {
            XposedBridge.log(t);
            return null;
        }
    }

    private static int asInt(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }
}
