# 电池百分比居中（Battery Percent Fix）

一个**只做一件事**的 LSPosed 模块：修掉状态栏电池图标内百分比数字"有时候偏下、有时候正常"的问题，
并提供一个 dp 级手动微调。适用于荣耀 MagicOS 11 / Android 17（`com.android.systemui`），
也兼容 `com.hihonor.systemui`。

## 1. 为什么数字会偏

MagicOS 的百分比文字由 `CenteredTextView` 绘制，它把垂直居中委托给
`VerticalCenteredTextHelper.optimizeDraw()`，原逻辑是：

```java
paint.getTextBounds(currentText, 0, len, bounds);           // 当前这串数字的墨迹盒
float dy = height / 2f - getBaseline()
         - (bounds.top + bounds.bottom) / 2f;               // ← 随数字变化
canvas.translate(0f, dy);
super.onDraw(canvas);
```

也就是说，**文字位置取决于"这一帧显示的是哪几个数字"**。字体（`HnChinese-demibold` +
`pnum` + 字重 650）里不同数字组合的墨迹盒并不完全一致，再叠加 `getTextBounds` 的整数取整，
`dy` 会出现 0.5px 的差异；小字号栅格化后就是肉眼可见的 1px 上下跳。

## 2. 本模块怎么修

状态栏电池百分比其实有**两条绘制路径**，模块都接管了：

### 路径 1：充电 / 胶囊态（TextView）

`CenteredTextView.onDraw` → `VerticalCenteredTextHelper.optimizeDraw()`，原逻辑：

```java
paint.getTextBounds(currentText, 0, len, bounds);
float dy = height / 2f - getBaseline()
         - (bounds.top + bounds.bottom) / 2f;      // 随数字变化
canvas.translate(0f, dy);
```

模块替换为：

```java
String ref = 当前语言/字体的完整数字集合 "0123456789";
paint.getTextBounds(ref, 0, ref.length(), bounds);
float dy = height / 2f - getBaseline()
         - (bounds.top + bounds.bottom) / 2f
         + 手动偏移;                                   // dp
dy = Math.round(dy);
```

### 路径 2：非充电态（BatteryTintView 自绘）

非充电时数字**不是 TextView 画的**，而是 `BatteryTintView.onDraw` 里直接：

```java
paint.getTextBounds(percentText, 0, len, bounds);
canvas.drawText(percentText, x, height / 2f - (bounds.top + bounds.bottom) / 2f, paint);
```

所以只 hook 路径 1 会出现“只有充电时生效”。模块的做法是：在 `BatteryTintView.onDraw`
这一帧里拦截 `Paint.getTextBounds`，把返回的垂直包围盒换成完整数字集合的固定包围盒
（含手动偏移），原绘制代码（包括用于挖空的 Xfermode）完全不动，只让基线变稳定。

### 路径 3：dispatchDraw 里直接绘制

有些 ROM 版本把整块电池（含数字）放在 `HwStatusBarBatteryView.dispatchDraw` 的绘制里，
单看 View 的 `onDraw` 抓不到。模块在 `dispatchDraw` 期间置一个标记，拦截
`Canvas.drawText` / `drawTextRun`，只改文字的 y 坐标（不重绘电池外观）。

- 数字集合按当前文本的 Unicode 数字段生成，换语言（阿拉伯-印度数字等）也对得上；
- 基准只跟“字体 + 字号 + 数字集合”有关，**与当前显示的数字无关**；
- `Math.round` 消除半像素抖动；
- 关闭开关时两条路径都**完全复刻原逻辑**，保证“关掉 = 和系统一模一样”。

## 3. 构建

### 方式 A：GitHub Actions（不装任何环境，直接拿 APK）

本仓库自带 `.github/workflows/build-apk.yml`：

1. 新建一个 GitHub 仓库，把本目录（**连同 `.github` 隐藏目录**）上传；
2. 打开仓库的 **Actions → Build APK → Run workflow**；
3. 约 3~5 分钟后，在该次运行页面底部下载 **Artifacts → BatteryPercentFix-debug.zip**，
   解压得到 `app-debug.apk`，直接安装即可。

### 方式 B：Android Studio

Android Studio 打开本目录 → Sync → `Build → Build APK(s)`，产物
`app/build/outputs/apk/debug/app-debug.apk`。

### 方式 C：命令行

```bash
gradle :app:assembleDebug
# 或
gradle wrapper --gradle-version 8.7 && ./gradlew :app:assembleDebug
```

离线时把 `XposedBridgeAPI-82.jar` 放进 `app/libs/`，把
`compileOnly("de.robv.android.xposed:api:82")` 换成
`compileOnly(files("libs/XposedBridgeAPI-82.jar"))`。

## 4. 安装与启用

1. 安装 APK。
2. LSPosed → 模块 → 启用「电池百分比居中」。
3. 作用域勾选**「系统界面」**（MagicOS 11 的包名是 `com.android.systemui`，
   旧版荣耀是 `com.hihonor.systemui`，模块两个都声明了）。
4. 打开模块 →「保存并应用」→「重启系统界面」。

> 临时 root 重启手机后会失效：重新 root 后回到模块点一次「重启系统界面」即可。

## 5. 设置项

| 项 | 说明 |
| --- | --- |
| 启用居中修复 | 关掉后恢复系统原行为，可用于 A/B 对比 |
| 手动微调 | -3.0dp ~ +3.0dp，0.1dp 步进；负数向上、正数向下；「归零」恢复 0 |
| 预览字体 | 系统默认 / 等宽 / 衬线 / 粗体 / HnChinese（本机），用来确认不同字体下都对齐 |
| 预览 | 8 / 18 / 80 / 100 四组不同位数 + 大号 88 / 100，虚线是胶囊中线 |
| **模拟电量** | 滑杆选 0~100 →「应用这个电量」；「模拟充电 / 模拟不充电」直接改系统上报状态；「恢复真实电量」还原。需要 root，免去每次 adb 敲 `dumpsys battery` |
| **模块状态** | 显示系统界面回传的注入结果：`注入`（三条 hook 是否装上）、`TextView 路径`、`BatteryTintView 路径`、`Paint 拦截`。用于确认某条绘制路径到底有没有被触发 |

手动偏移的用途：不同 ROM 的电池图标基线略有差异，或你个人更喜欢偏上/偏下 0.5dp 的观感。
它只作用于模块的绘制平移，不改系统布局。

## 6. 兼容性与排错

- 只 hook `VerticalCenteredTextHelper.optimizeDraw`，签名不匹配时**静默跳过**，
  不会导致状态栏异常；
- 如果系统更新后类名/签名变化，模块自动 no-op（图标文字仍是系统原样）；
- 日志：

```bash
adb logcat -c && adb logcat -s HarmonyBattFix
```

看到 `hooked com.android.systemui.view.VerticalCenteredTextHelper` 说明注入成功；
看到 `first draw h=... baseline=... refTop=... refBottom=... dy=...` 是第一次绘制时
算出的基准（可用来确认偏移量是否符合预期）；
看到 `not found, no-op` 说明这个 ROM 的类名不同，把日志发我即可适配。

## 7. 安全说明

只修改系统界面里的文字绘制基线：不修改系统文件、不申请网络权限、不收集数据；
配置通过本模块的只读 ContentProvider 传给系统界面，只暴露"开关 + 偏移"两个值。
