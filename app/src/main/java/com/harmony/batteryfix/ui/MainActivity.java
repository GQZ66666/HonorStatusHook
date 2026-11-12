package com.harmony.batteryfix.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.harmony.batteryfix.Config;
import com.harmony.batteryfix.Prefs;
import com.harmony.batteryfix.PrefsProvider;
import com.harmony.batteryfix.R;
import com.harmony.batteryfix.util.SystemUiUtil;

/**
 * 一个页面：开关 / dp 微调 / 多字体预览 / 模拟电量 / 诊断 / 保存 / root 重启系统界面。
 */
public final class MainActivity extends Activity {

    /** SeekBar 0..60 → -3.0dp .. +3.0dp（0.1dp 步进） */
    private static final int OFFSET_STEPS = 60;
    private static final int OFFSET_ZERO = OFFSET_STEPS / 2;

    private static final Typeface[] FONTS = {
            Typeface.DEFAULT,
            Typeface.MONOSPACE,
            Typeface.SERIF,
            Typeface.DEFAULT_BOLD,
            Typeface.create("HnChinese-demibold", Typeface.NORMAL)
    };
    private static final int[] FONT_NAMES = {
            R.string.font_system,
            R.string.font_mono,
            R.string.font_serif,
            R.string.font_bold,
            R.string.font_hn
    };

    private Config cfg;
    private Switch swEnable;
    private SeekBar sbOffset;
    private TextView tvOffset;
    private Button btnFont;
    private PreviewView preview;
    private SeekBar sbLevel;
    private TextView tvTestLevel;
    private TextView tvDiag;
    private int fontIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cfg = Prefs.load(this);
        swEnable = findViewById(R.id.swEnable);
        sbOffset = findViewById(R.id.sbOffset);
        tvOffset = findViewById(R.id.tvOffset);
        btnFont = findViewById(R.id.btnFont);
        preview = findViewById(R.id.preview);
        sbLevel = findViewById(R.id.sbLevel);
        tvTestLevel = findViewById(R.id.tvTestLevel);
        tvDiag = findViewById(R.id.tvDiag);

        bindValues();
        attachListeners();

        TextView hint = findViewById(R.id.tvHint);
        hint.setText(getString(R.string.hint_body, SystemUiUtil.detect(this)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDiag();
    }

    private void bindValues() {
        swEnable.setChecked(cfg.enabled);
        sbOffset.setProgress(Math.round(cfg.offsetDp * 10f) + OFFSET_ZERO);
        updateOffsetLabel();
        preview.setOffsetDp(cfg.offsetDp);
        preview.setStable(cfg.enabled);
        preview.setTypeface(FONTS[fontIndex]);
        updateFontButton();
        updateTestLevel();
    }

    private void attachListeners() {
        swEnable.setOnCheckedChangeListener((button, checked) -> {
            cfg.enabled = checked;
            preview.setStable(checked);
        });

        sbOffset.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                cfg.offsetDp = (progress - OFFSET_ZERO) * 0.1f;
                updateOffsetLabel();
                preview.setOffsetDp(cfg.offsetDp);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        findViewById(R.id.btnReset).setOnClickListener(v -> sbOffset.setProgress(OFFSET_ZERO));

        btnFont.setOnClickListener(v -> {
            fontIndex = (fontIndex + 1) % FONTS.length;
            preview.setTypeface(FONTS[fontIndex]);
            updateFontButton();
        });

        findViewById(R.id.btnSave).setOnClickListener(v -> {
            Prefs.save(this, cfg);
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnRestart).setOnClickListener(v -> askRestart());

        sbLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateTestLevel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        findViewById(R.id.btnApplyLevel).setOnClickListener(v ->
                runRoot(() -> SystemUiUtil.setLevel(sbLevel.getProgress())));
        findViewById(R.id.btnCharging).setOnClickListener(v ->
                runRoot(() -> SystemUiUtil.setCharging(true)));
        findViewById(R.id.btnUnplug).setOnClickListener(v ->
                runRoot(() -> SystemUiUtil.setCharging(false)));
        findViewById(R.id.btnResetBattery).setOnClickListener(v ->
                runRoot(SystemUiUtil::resetBattery));
        findViewById(R.id.btnRefreshDiag).setOnClickListener(v -> refreshDiag());
    }

    private void updateTestLevel() {
        tvTestLevel.setText(getString(R.string.test_level, sbLevel.getProgress()));
    }

    private void runRoot(java.util.concurrent.Callable<String> task) {
        new Thread(() -> {
            String result;
            try {
                result = task.call();
            } catch (Throwable t) {
                result = "失败：" + t;
            }
            final String msg = result;
            runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
        }, "batteryfix-root").start();
    }

    private void refreshDiag() {
        StringBuilder sb = new StringBuilder();
        try {
            Bundle b = getContentResolver().call(Prefs.URI, PrefsProvider.METHOD_DIAG, null, null);
            if (b != null) {
                appendDiag(sb, b, "hooks", "注入");
                appendDiag(sb, b, "textview", "TextView 路径");
                appendDiag(sb, b, "battery", "BatteryTintView 路径");
                appendDiag(sb, b, "paint", "Paint 拦截");
                appendDiag(sb, b, "dispatch", "电池 dispatch");
                appendDiag(sb, b, "canvastext", "Canvas 文字");
                appendDiag(sb, b, "tree", "状态栏 View 树");
                appendDiag(sb, b, "custombattery", "自绘电池");
            }
        } catch (Throwable ignored) {
        }
        tvDiag.setText(sb.length() == 0 ? getString(R.string.diag_empty) : sb.toString().trim());
    }

    private static void appendDiag(StringBuilder sb, Bundle b, String key, String label) {
        String value = b.getString(key);
        if (value != null) {
            sb.append(label).append(": ").append(value).append('\n');
        }
    }

    private void updateOffsetLabel() {
        tvOffset.setText(getString(R.string.offset_value, cfg.offsetDp));
    }

    private void updateFontButton() {
        btnFont.setText(getString(R.string.btn_font, getString(FONT_NAMES[fontIndex])));
    }

    private void askRestart() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.restart_title)
                .setMessage(R.string.restart_msg)
                .setPositiveButton(R.string.restart_ok, (dialog, which) -> restartSystemUi())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void restartSystemUi() {
        Toast.makeText(this, R.string.restart_running, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final String result = SystemUiUtil.restart(this);
            runOnUiThread(() -> Toast.makeText(this, result, Toast.LENGTH_LONG).show());
        }, "batteryfix-restart").start();
    }
}
