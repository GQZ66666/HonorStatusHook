package com.harmony.batteryfix.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import com.harmony.batteryfix.CenterMath;

/**
 * 预览：用和模块完全相同的基准算法绘制不同位数、不同字体的百分比。
 * 关闭修复时会切回系统原来的“当前文本包围盒”算法，方便直观对比。
 */
public final class PreviewView extends View {

    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect ref = new Rect();

    private float offsetDp;
    private Typeface typeface = Typeface.DEFAULT;
    private boolean stable = true;

    public PreviewView(Context context) {
        this(context, null);
    }

    public PreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        card.setColor(0xFF141821);
        pill.setStyle(Paint.Style.STROKE);
        pill.setStrokeWidth(dp(1.4f));
        pill.setColor(0xFF3A4353);
        mid.setStyle(Paint.Style.STROKE);
        mid.setStrokeWidth(dp(0.8f));
        mid.setColor(0x33FFFFFF);
        mid.setPathEffect(new DashPathEffect(new float[]{dp(3f), dp(3f)}, 0f));
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(typeface);
    }

    public void setOffsetDp(float dp) {
        offsetDp = dp;
        invalidate();
    }

    public void setTypeface(Typeface tf) {
        typeface = tf == null ? Typeface.DEFAULT : tf;
        invalidate();
    }

    public void setStable(boolean stable) {
        this.stable = stable;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()),
                dp(16f), dp(16f), card);

        // 状态栏尺寸：1~3 位数字
        float y1 = dp(52f);
        String[] small = {"8", "18", "80", "100"};
        float pw = dp(54f);
        float ph = dp(24f);
        float gap = dp(10f);
        float x = dp(16f);
        for (String s : small) {
            drawPill(canvas, s, x, y1, pw, ph, dp(11f));
            x += pw + gap;
        }

        // 放大：检查像素级对齐
        float y2 = dp(160f);
        float bw = dp(120f);
        float bh = dp(56f);
        float bgap = dp(12f);
        float startX = (getWidth() - (bw * 2f + bgap)) / 2f;
        drawPill(canvas, "88", startX, y2, bw, bh, dp(24f));
        drawPill(canvas, "100", startX + bw + bgap, y2, bw, bh, dp(24f));

        // 底部说明由布局里的 TextView 提供，这里只画一点留白
    }

    private void drawPill(Canvas canvas, String s, float x, float cy,
                          float w, float h, float textSize) {
        RectF r = new RectF(x, cy - h / 2f, x + w, cy + h / 2f);
        canvas.drawRoundRect(r, h / 2f, h / 2f, pill);
        canvas.drawLine(r.left + dp(6f), cy, r.right - dp(6f), cy, mid);
        drawCentered(canvas, s, r.centerX(), cy, textSize);
    }

    private void drawCentered(Canvas canvas, String s, float cx, float cy, float size) {
        text.setTextSize(size);
        text.setTypeface(typeface);
        float dy;
        if (stable) {
            String digits = CenterMath.referenceDigits(s);
            text.getTextBounds(digits, 0, digits.length(), ref);
            dy = -(ref.top + ref.bottom) / 2f;
            dy = Math.round(dy + offsetDp * getResources().getDisplayMetrics().density);
        } else {
            text.getTextBounds(s, 0, s.length(), ref);
            dy = -(ref.top + ref.bottom) / 2f;
        }
        canvas.drawText(s, cx, cy + dy, text);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
