# 用 GitHub Actions 生成 APK（不用装 Android Studio）

## 1. 建仓库

GitHub → New repository → 名字随意（如 `battery-percent-fix`）→ Private 也可以
（Actions 每月有免费额度）→ Create。

## 2. 上传文件

把 `BatteryPercentFix` 目录里的**内容**传到仓库根目录，要求仓库根目录能直接看到
`settings.gradle.kts`、`app/`、`.github/`。

### 方式一：显示隐藏文件后整体拖拽（推荐）

1. Windows 资源管理器 → 查看 → 显示 → 勾选「隐藏的项目」；
2. 进入 `BatteryPercentFix`，`Ctrl+A` 全选（含 `.github`）；
3. 拖到 GitHub 仓库页的 "uploading an existing file" 区域；
4. Commit changes。

### 方式二：没有 `.github` 时手动建工作流

1. 先拖入除 `.github` 外的所有文件并提交；
2. 仓库页 → **Add file → Create new file**；
3. 文件名填 `.github/workflows/build-apk.yml`（GitHub 会自动建目录）；
4. 粘贴仓库里 `build-apk.yml` 的内容（也放在本目录根部），Commit。

## 3. 跑构建

- 上传/提交后会自动触发 **Actions → Build APK**；
- 也可以手动：Actions → Build APK → Run workflow；
- 等 3~5 分钟，绿勾后点进这次运行，页面底部 **Artifacts → BatteryPercentFix-debug**；
- 下载得到 zip，解压出 `app-debug.apk`，传到手机安装。

## 4. 失败怎么办

把 Actions 里红色那一步的日志（尤其是 `Could not resolve de.robv.android.xposed:api:82`
或 Gradle 报错）复制给我，我改工作流/依赖。

## 5. 装好之后

LSPosed 启用「电池百分比居中」→ 作用域勾「系统界面」→ 模块里保存 → 重启系统界面。
临时 root 重启手机后需要重新 root 并再点一次「重启系统界面」。
