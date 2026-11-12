package com.harmony.batteryfix;

/**
 * 与绘制共用的“稳定基准”计算。
 *
 * <p>关键点：不要用当前这串数字的墨迹盒（系统原来的做法），而是用同一字体、同一字号下
 * 完整数字集合 0–9 的并集墨迹盒。这样换任何数字，基准都不变。</p>
 *
 * <p>数字集合按当前文本所属的 Unicode 数字段生成（如阿拉伯-印度数字 ٠١٢…），
 * 因此切换语言后依然对得上。</p>
 */
public final class CenterMath {

    private CenterMath() {
    }

    public static String referenceDigits(CharSequence text) {
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (!Character.isDigit(ch)) {
                    continue;
                }
                int value = Character.digit(ch, 10);
                if (value < 0) {
                    continue;
                }
                char zero = (char) (ch - value);
                char[] out = new char[10];
                for (int k = 0; k < 10; k++) {
                    out[k] = (char) (zero + k);
                }
                return new String(out);
            }
        }
        return "0123456789";
    }
}
