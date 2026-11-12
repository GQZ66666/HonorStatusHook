package com.harmony.batteryfix;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.View;
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
    private static final AtomicBoolean sFirstDispatchLogged = new AtomicBoolean(false);

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
        boolean tree = hookTree(cl);
        String status = "helper=" + helper + " battery=" + battery + " paint=" + paint
                + " dispatch=" + dispatch + " canvas=" + canvas + " tree=" + tree;
        XposedBridge.log(ModuleEntry.TAG + ": hooks " + status);
        Diag.report(ctx, "hooks", status);
    }

    /**
     * 按 Honor 原始几何（104×60）1:1 克隆电池，只把百分比数字换成我们画的。
     * 返回 true 表示已接管绘制（跳过系统绘制）。
     */
    private static boolean drawBattery(XC_MethodHook.MethodHookParam param, View view) {
        Object[] args = param.args;
        Canvas canvas = args.length > 0 && args[0] instanceof Canvas ? (Canvas) args[0] : null;
        if (canvas == null || sAppCtx == null) {
            return false;
        }
        Config cfg = PrefsClient.get(sAppCtx);
        if (!cfg.enabled) {
            return false;
        }
        Object obj = param.thisObject;
        int level = getInt(obj, "mLevel");
        if (level < 0 || level > 100) {
            return false;
        }
        boolean charging = getBool(obj, "mIsCharging");
        boolean show = getBool(obj, "mIsShowPercent");
        boolean inside = getBool(obj, "mIsShowPercentIn");
        if (!inside) {
            // 百分比在图标外时，交回系统绘制，避免丢掉外侧文字
            return false;
        }
        int tint = getInt(obj, "mTintColor");
        if (tint == 0) {
            tint = 0xFFFFFFFF;
        }

        int w = view.getWidth();
        int h = view.getHeight();
        if (w <= 0 || h <= 0) {
            return false;
        }

        param.setResult(null);

        float s = Math.min(w / 104f, h / 60f);
        float ox = (w - 104f * s) / 2f;
        float oy = (h - 60f * s) / 2f;

        RectF body = new RectF(ox + 10f * s, oy + 8f * s, ox + 89f * s, oy + 52f * s);
        float radius = 22f * s;

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);

        int outline = tint | 0xFF000000;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, 1.6f * s));
        p.setColor(outline);
        canvas.drawRoundRect(body, radius, radius, p);

        RectF nub = new RectF(ox + 92f * s, oy + 22f * s, ox + 98f * s, oy + 38f * s);
        canvas.drawRoundRect(nub, 3f * s, 3f * s, p);

        int fill = charging ? 0xFF34C759 : (level <= 20 ? 0xFFFF9F0A : outline);
        float inset = 2.4f * s;
        float fillW = (body.width() - 2f * inset) * level / 100f;
        if (fillW > 0.5f) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(fill);
            RectF fr = new RectF(body.left + inset, body.top + inset,
                    body.left + inset + fillW, body.bottom - inset);
            float frr = Math.min((body.height() - 2f * inset) / 2f, Math.max(1f, fillW / 2f));
            canvas.drawRoundRect(fr, frr, frr, p);
        }

        int textColor = luminance(fill) > 0.6 ? 0xFF000000 : 0xFFFFFFFF;
        if (charging && !show) {
            drawBolt(canvas, body.centerX(), body.centerY(), s, textColor, p);
        }
        if (show) {
            float size = Math.max(6f, body.height() * 0.60f);
            drawPercent(canvas, level, body.centerX(), body.centerY(), size, textColor, cfg);
        }

        if (sFirstBatteryDrawLogged.compareAndSet(false, true)) {
            Diag.report(sAppCtx, "custombattery", "level=" + level + " charging=" + charging);
        }
        return true;
    }

    private static void drawPercent(Canvas c, int level, float cx, float cy,
                                    float size, int color, Config cfg) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        String text = String.valueOf(level);
        String ref = CenterMath.referenceDigits(text);
        Rect r = new Rect();
        p.getTextBounds(ref, 0, ref.length(), r);
        float offset = cfg.offsetDp * sAppCtx.getResources().getDisplayMetrics().density;
        float baseline = cy - (r.top + r.bottom) / 2f + offset;
        c.drawText(text, cx, baseline, p);
    }

    private static void drawBolt(Canvas c, float cx, float cy, float s, int color, Paint p) {
        Path path = new Path();
        path.moveTo(cx + 2.0f * s, cy - 7.0f * s);
        path.lineTo(cx - 3.5f * s, cy + 0.8f * s);
        path.lineTo(cx - 0.5f * s, cy + 0.8f * s);
        path.lineTo(cx - 2.0f * s, cy + 7.0f * s);
        path.lineTo(cx + 3.5f * s, cy - 0.8f * s);
        path.lineTo(cx + 0.5f * s, cy - 0.8f * s);
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawPath(path, p);
    }

    private static int luminance(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    private static int getInt(Object obj, String name) {
        Object v = field(obj, name);
        return v instanceof Integer ? (Integer) v : 0;
    }

    private static boolean getBool(Object obj, String name) {
        Object v = field(obj, name);
        return v instanceof Boolean && (Boolean) v;
    }

    private boolean hookTree(ClassLoader cl) {        String[] roots = {
                "com.android.systemui.statusbar.phone.PhoneStatusBarView",
                "com.android.systemui.statusbar.phone.HwPhoneStatusBarView"
        };
        for (String name : roots) {
            Class<?> c = XposedHelpers.findClassIfExists(name, cl);
            if (c == null) {
                continue;
            }
            XposedBridge.hookAllMethods(c, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        StringBuilder sb = new StringBuilder();
                        walk((View) param.thisObject, sb, 0);
                        Diag.report(sAppCtx, "tree", sb.toString());
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            });
            XposedBridge.log(ModuleEntry.TAG + ": hooked tree root " + name);
            return true;
        }
        return false;
    }

    private static void walk(View v, StringBuilder sb, int depth) {
        if (sb.length() > 700 || depth > 8) {
            return;
        }
        String cls = v.getClass().getSimpleName();
        String id = "";
        try {
            if (v.getId() != View.NO_ID) {
                id = v.getResources().getResourceEntryName(v.getId());
            }
        } catch (Throwable ignored) {
        }
        sb.append(cls);
        if (!id.isEmpty()) {
            sb.append('#').append(id);
        }
        if (v.getVisibility() != View.VISIBLE) {
            sb.append('(').append(v.getVisibility()).append(')');
        }
        sb.append(' ');
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), sb, depth + 1);
            }
        }
    }

    // ------------------------------------------------------------ 路径 3：dispatchDraw 期间的 Canvas 文字

    private boolean hookBatteryDispatch(ClassLoader cl) {
        final Class<?> battery = XposedHelpers.findClassIfExists(
                "com.android.systemui.battery.HwStatusBarBatteryView", cl);
        if (battery == null) {
            XposedBridge.log(ModuleEntry.TAG + ": HwStatusBarBatteryView not found (canvas path skipped)");
            return false;
        }
        Method dispatch = null;
        for (Class<?> c = battery; c != null && dispatch == null; c = c.getSuperclass()) {
            try {
                dispatch = c.getDeclaredMethod("dispatchDraw", Canvas.class);
            } catch (NoSuchMethodException ignored) {
            }
        }
        if (dispatch == null) {
            XposedBridge.log(ModuleEntry.TAG + ": dispatchDraw not found");
            return false;
        }
        dispatch.setAccessible(true);
        XposedBridge.hookMethod(dispatch, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!battery.isInstance(param.thisObject)) {
                    return;
                }
                IN_BATTERY_DISPATCH.set(Boolean.TRUE);
                if (sFirstDispatchLogged.compareAndSet(false, true)) {
                    StringBuilder sb = new StringBuilder();
                    try {
                        View v = (View) param.thisObject;
                        if (v instanceof android.view.ViewGroup) {
                            android.view.ViewGroup g = (android.view.ViewGroup) v;
                            for (int i = 0; i < g.getChildCount(); i++) {
                                View child = g.getChildAt(i);
                                sb.append(child.getClass().getSimpleName())
                                        .append(':').append(child.getVisibility()).append(' ');
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    String info = "fired children=" + sb;
                    XposedBridge.log(ModuleEntry.TAG + ": [dispatch] " + info
                            + " w=" + ((View) param.thisObject).getWidth()
                            + " h=" + ((View) param.thisObject).getHeight());
                    Diag.report(sAppCtx, "dispatch", info);
                }
                try {
                    drawBattery(param, (View) param.thisObject);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (battery.isInstance(param.thisObject)) {
                    IN_BATTERY_DISPATCH.remove();
                }
            }
        });
        XposedBridge.log(ModuleEntry.TAG + ": hooked HwStatusBarBatteryView.dispatchDraw (" + dispatch + ")");
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
        if (text == null || sAppCtx == null) {
            return;
        }
        if (param.args.length <= Math.max(yIndex, paintIndex)) {
            return;
        }
        Object paintObj = param.args[paintIndex];
        if (!(paintObj instanceof Paint)) {
            return;
        }
        Paint paint = (Paint) paintObj;
        float y = (Float) param.args[yIndex];
        if (!looksLikeBatteryPercent(text, y, paint)) {
            return;
        }
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

    private static volatile int sStatusBarHeight;

    private static boolean looksLikeBatteryPercent(String text, float y, Paint paint) {
        if (text.isEmpty() || text.length() > 3) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        float size = paint.getTextSize();
        if (size < 8f || size > 90f) {
            return false;
        }
        if (sStatusBarHeight == 0) {
            try {
                int id = sAppCtx.getResources().getIdentifier(
                        "status_bar_height", "dimen", "android");
                sStatusBarHeight = id > 0 ? sAppCtx.getResources().getDimensionPixelSize(id) : 96;
            } catch (Throwable t) {
                sStatusBarHeight = 96;
            }
        }
        return y > -sStatusBarHeight && y < sStatusBarHeight * 2.5f;
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
