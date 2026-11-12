package com.harmony.batteryfix;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.TextPaint;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 状态栏电池百分比有两条绘制路径，这里全部接管：
 *
 * <p><b>路径 1（充电/胶囊态）</b>：{@code CenteredTextView.onDraw} 委托
 * {@code VerticalCenteredTextHelper.optimizeDraw}，原公式用“当前数字”的包围盒算垂直偏移。
 * 我们替换为“完整数字集合”的固定基准 + 整像素对齐 + 手动偏移。</p>
 *
 * <p><b>路径 2（非充电态）</b>：数字由 {@code BatteryTintView.onDraw} 直接
 * {@code Canvas.drawText} 画出，同样用当前数字的包围盒算基线，且完全不经过上面的 helper。
 * 我们的做法是在这帧 onDraw 期间拦截 {@code Paint.getTextBounds}，把返回的垂直包围盒
 * 换成“完整数字集合”的固定包围盒（含手动偏移），从而让原绘制代码算出稳定的基线。</p>
 */
public final class CenterHook {

    private static final String[] HELPER_CANDIDATES = {
            "com.android.systemui.view.VerticalCenteredTextHelper",
            "com.hihonor.systemui.view.VerticalCenteredTextHelper"
    };
    private static final String BATTERY_TINT_VIEW = "com.android.systemui.tint.BatteryTintView";

    /** 只在 BatteryTintView.onDraw 的 TextPaint.getTextBounds 调用上生效 */
    private static final ThreadLocal<Boolean> IN_BATTERY_TEXT_DRAW = new ThreadLocal<>();
    /** 在 HwStatusBarBatteryView.dispatchDraw 期间生效，用于拦截 Canvas 文字绘制 */
    private static final ThreadLocal<Boolean> IN_BATTERY_DISPATCH = new ThreadLocal<>();

    private static Context sAppCtx;
    private static volatile boolean sPaintHooked;
    private static volatile boolean sCanvasHooked;
    private static final AtomicBoolean sFirstDrawLogged = new AtomicBoolean(false);
    private static final AtomicBoolean sFirstBatteryDrawLogged = new AtomicBoolean(false);
    private static final AtomicBoolean sFirstCanvasTextLogged = new AtomicBoolean(false);

    private final Context ctx;

    public CenterHook(Context ctx) {
        this.ctx = ctx;
        sAppCtx = ctx.getApplicationContext();
    }

    public void install(ClassLoader cl) {
        boolean helper = hookHelper(cl);
        boolean battery = hookBatteryTintView(cl);
        boolean paint = hookTextBounds();
        boolean dispatch = hookBatteryDispatch(cl);
        boolean canvas = hookCanvasText();
        String status = "helper=" + helper + " battery=" + battery + " paint=" + paint
                + " dispatch=" + dispatch + " canvas=" + canvas;
        XposedBridge.log(ModuleEntry.TAG + ": hooks " + status);
        Diag.report(ctx, "hooks", status);
    }

    // ------------------------------------------------------------ 路径 3：dispatchDraw 期间的 Canvas 文字

    private boolean hookBatteryDispatch(ClassLoader cl) {
        final Class<?> battery = XposedHelpers.findClassIfExists(
                "com.android.systemui.battery.HwStatusBarBatteryView", cl);
        if (battery == null) {
            XposedBridge.log(ModuleEntry.TAG + ": HwStatusBarBatteryView not found (canvas path skipped)");
            return false;
        }
        Method dispatch;
        try {
            dispatch = battery.getMethod("dispatchDraw", Canvas.class);
        } catch (NoSuchMethodException e) {
            XposedBridge.log(ModuleEntry.TAG + ": dispatchDraw not found");
            return false;
        }
        XposedBridge.hookMethod(dispatch, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (battery.isInstance(param.thisObject)) {
                    IN_BATTERY_DISPATCH.set(Boolean.TRUE);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                IN_BATTERY_DISPATCH.remove();
            }
        });
        XposedBridge.log(ModuleEntry.TAG + ": hooked HwStatusBarBatteryView.dispatchDraw");
        return true;
    }

    private static synchronized boolean hookCanvasText() {
        if (sCanvasHooked) {
            return true;
        }
        try {
            XposedHelpers.findAndHookMethod(Canvas.class, "drawText",
                    String.class, float.class, float.class, Paint.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            adjustCanvasText(param, (String) param.args[0], 2, 3);
                        }
                    });
            try {
                XposedHelpers.findAndHookMethod(Canvas.class, "drawTextRun",
                        char[].class, int.class, int.class, int.class, int.class,
                        float.class, float.class, boolean.class, Paint.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                char[] chars = (char[]) param.args[0];
                                int index = (Integer) param.args[1];
                                int count = (Integer) param.args[2];
                                String text = new String(chars, index, count);
                                adjustCanvasText(param, text, 6, 8);
                            }
                        });
            } catch (Throwable ignored) {
            }
            try {
                XposedHelpers.findAndHookMethod(Canvas.class, "drawTextRun",
                        CharSequence.class, int.class, int.class, int.class, int.class,
                        float.class, float.class, boolean.class, Paint.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                CharSequence cs = (CharSequence) param.args[0];
                                int index = (Integer) param.args[1];
                                int count = (Integer) param.args[2];
                                String text = cs.subSequence(index, index + count).toString();
                                adjustCanvasText(param, text, 6, 8);
                            }
                        });
            } catch (Throwable ignored) {
            }
            sCanvasHooked = true;
            XposedBridge.log(ModuleEntry.TAG + ": hooked Canvas drawText/drawTextRun");
        } catch (Throwable t) {
            XposedBridge.log(ModuleEntry.TAG + ": Canvas text hook failed: " + t);
        }
        return sCanvasHooked;
    }

    private static void adjustCanvasText(XC_MethodHook.MethodHookParam param, String text,
                                         int yIndex, int paintIndex) {
        if (!Boolean.TRUE.equals(IN_BATTERY_DISPATCH.get()) || text == null || sAppCtx == null) {
            return;
        }
        if (!containsDigit(text) || param.args.length <= Math.max(yIndex, paintIndex)) {
            return;
        }
        Object paintObj = param.args[paintIndex];
        if (!(paintObj instanceof Paint)) {
            return;
        }
        Paint paint = (Paint) paintObj;
        Config cfg = PrefsClient.get(sAppCtx);
        if (!cfg.enabled) {
            return;
        }
        try {
            Rect cur = new Rect();
            paint.getTextBounds(text, 0, text.length(), cur);
            String ref = CenterMath.referenceDigits(text);
            Rect fixed = new Rect();
            paint.getTextBounds(ref, 0, ref.length(), fixed);

            float offsetPx = cfg.offsetDp * sAppCtx.getResources().getDisplayMetrics().density;
            float delta = (cur.top + cur.bottom) / 2f - (fixed.top + fixed.bottom) / 2f + offsetPx;
            float y = (Float) param.args[yIndex];
            param.args[yIndex] = y + delta;

            if (sFirstCanvasTextLogged.compareAndSet(false, true)) {
                XposedBridge.log(ModuleEntry.TAG + ": [canvastext] text=" + text
                        + " delta=" + delta + " y=" + y);
                Diag.report(sAppCtx, "canvastext", "text=" + text
                        + " delta=" + Math.round(delta));
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static boolean containsDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 路径 1：TextView helper

    private boolean hookHelper(ClassLoader cl) {
        for (String name : HELPER_CANDIDATES) {
            Class<?> clazz = XposedHelpers.findClassIfExists(name, cl);
            if (clazz == null) {
                continue;
            }
            int hooked = 0;
            for (Method m : clazz.getDeclaredMethods()) {
                if (!"optimizeDraw".equals(m.getName()) || !matchesHelper(m)) {
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
                return true;
            }
        }
        XposedBridge.log(ModuleEntry.TAG + ": VerticalCenteredTextHelper not found (path 1 skipped)");
        return false;
    }

    private static boolean matchesHelper(Method m) {
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
            XposedBridge.log(ModuleEntry.TAG + ": [textview] h=" + height
                    + " baseline=" + baseline + " refTop=" + bounds.top
                    + " refBottom=" + bounds.bottom + " text=" + text
                    + " offsetDp=" + cfg.offsetDp + " dy=" + rounded);
            Diag.report(ctx, "textview", "text=" + text + " dy=" + rounded);
        }
        return rounded;
    }

    // ------------------------------------------------------------ 路径 2：BatteryTintView 自绘

    private boolean hookBatteryTintView(ClassLoader cl) {
        Class<?> clazz = XposedHelpers.findClassIfExists(BATTERY_TINT_VIEW, cl);
        if (clazz == null) {
            XposedBridge.log(ModuleEntry.TAG + ": BatteryTintView not found (path 2 skipped)");
            return false;
        }
        Method onDraw;
        try {
            onDraw = clazz.getDeclaredMethod("onDraw", Canvas.class);
        } catch (NoSuchMethodException e) {
            XposedBridge.log(ModuleEntry.TAG + ": BatteryTintView.onDraw not found");
            return false;
        }
        XposedBridge.hookMethod(onDraw, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object percent = field(param.thisObject, "mInsidePercent");
                    if (percent instanceof TextView) {
                        IN_BATTERY_TEXT_DRAW.set(Boolean.TRUE);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                IN_BATTERY_TEXT_DRAW.remove();
            }
        });
        XposedBridge.log(ModuleEntry.TAG + ": hooked " + BATTERY_TINT_VIEW + ".onDraw");
        return true;
    }

    /**
     * 全局 hook Paint.getTextBounds(String,...)：只在 BatteryTintView.onDraw 这一帧里、
     * 且只对第一次调用生效，把垂直包围盒换成完整数字集合的固定包围盒。
     */
    private static synchronized boolean hookTextBounds() {
        if (sPaintHooked) {
            return true;
        }
        try {
            XposedHelpers.findAndHookMethod(Paint.class, "getTextBounds",
                    String.class, int.class, int.class, Rect.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!Boolean.TRUE.equals(IN_BATTERY_TEXT_DRAW.get())) {
                                return;
                            }
                            // 只处理本次电池文字测量，避免影响同一帧里的其它文本
                            IN_BATTERY_TEXT_DRAW.remove();
                            try {
                                String text = (String) param.args[0];
                                Rect out = (Rect) param.args[3];
                                if (text == null || out == null || text.isEmpty() || sAppCtx == null) {
                                    return;
                                }
                                Config cfg = PrefsClient.get(sAppCtx);
                                if (!cfg.enabled) {
                                    return;
                                }
                                String ref = CenterMath.referenceDigits(text);
                                Rect fixed = new Rect();
                                XposedBridge.invokeOriginalMethod(param.method, param.thisObject,
                                        new Object[]{ref, 0, ref.length(), fixed});

                                float density = sAppCtx.getResources().getDisplayMetrics().density;
                                int offsetPx = Math.round(cfg.offsetDp * density);
                                if (offsetPx != 0) {
                                    fixed.offset(0, -offsetPx);
                                }
                                out.set(fixed);

                                if (sFirstBatteryDrawLogged.compareAndSet(false, true)) {
                                    XposedBridge.log(ModuleEntry.TAG + ": [batteryview] text=" + text
                                            + " refTop=" + fixed.top + " refBottom=" + fixed.bottom
                                            + " offsetDp=" + cfg.offsetDp);
                                    Diag.report(sAppCtx, "battery", "text=" + text
                                            + " top=" + fixed.top + " bottom=" + fixed.bottom);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(t);
                            }
                        }
                    });
            sPaintHooked = true;
            XposedBridge.log(ModuleEntry.TAG + ": hooked Paint.getTextBounds");
        } catch (Throwable t) {
            XposedBridge.log(ModuleEntry.TAG + ": Paint.getTextBounds hook failed: " + t);
        }
        return sPaintHooked;
    }

    // ------------------------------------------------------------ utils

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

    private static Object field(Object obj, String name) {
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
