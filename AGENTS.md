# rimi-android（日弥输入法 · 安卓版）

fork 自 [osfans/trime](https://github.com/osfans/trime)（GPL-3.0），用于把日弥输入法的
方案/词库/品牌带到 Android / HarmonyOS(兼容 APK)。上游远程名 `upstream`。

## 构建（本机）

```bash
# 关键：必须逃出 Hermes worker cgroup（约 3G 内存上限，Gradle 会被 OOM 杀掉）
systemd-run --user --scope -p MemoryMax=12G -p MemorySwapMax=2G -- bash -lc '
  cd ~/pj/zen/1/apps/rimi-android
  export ANDROID_HOME=$HOME/Android/Sdk ANDROID_SDK_ROOT=$HOME/Android/Sdk BUILD_ABI=arm64-v8a
  ./gradlew :app:assembleDebug --max-workers=8 \
    -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=1g" \
    -Dkotlin.compiler.execution.strategy=in-process
'
```

- 工具链：JDK 21 / compileSdk 37 / build-tools 37.0.0 / NDK 30.0.16248370 / CMake 4.1.2
- lua 补丁（上游 Makefile 的 `patch-apply`）：
  `git apply --directory=app/src/main/jni/librime-lua-deps patches/lua.patch`
- `app/boost-1.92.0.tar.xz` 必须是完整 107878596 字节；截断会让 cmake configure 报
  `Truncated tar archive`。校验：`xz -t app/boost-1.92.0.tar.xz`

## 内置日弥数据（重要）

- 由 `~/pj/zen/1/apps/rimi/scripts/build_mobile_assets.py --with-english` 生成到
  `app/src/main/assets/shared/`：`rimi.schema.yaml`、`rimi.mobile.dict.yaml`（8105+基础+四专业库）、
  `lua/`、`en_dicts/`、`melt_eng.dict.yaml`、`symbols_v.yaml`
- 不能把 emoji 符号表放进 `assets/shared/opencc`：Gradle 的 `installOpenCCData` 会整目录重建。
  日弥的 `emoji.json/emoji.txt/others.txt` 放 `assets/shared/rimi_opencc`，
  运行期 `DataManager.syncExtraOpenCC()` 合并进 `shared/opencc`
- 默认方案列表在 `UserDataManager.SCHEMA_LIST_CUSTOM_PATCH`（rimi 第一，luna_pinyin 兜底）

## 品牌与主题

- applicationId `com.rimi.ime`（debug 变体 `.debug`）；namespace 暂留 `com.osfans.trime`
  （避免全量改包名，后续里程碑再评估）
- 默认配色已改深色：`assets/shared/trime.yaml` 的 `preset_color_schemes/default`
  = Catppuccin Mocha（与桌面 rimi_dark 同色板）
- 图标：白色「日」+ mauve 中横，`res/drawable/ic_app_icon_foreground.xml`

## 真机验证（HarmonyOS 4.3 / Android 12，PLR-AL30）

```bash
adb install -r app/build/outputs/apk/debug/com.rimi.ime-*-debug.apk
adb shell ime enable com.rimi.ime.debug/com.osfans.trime.ime.core.TrimeInputMethodService
adb shell ime set    com.rimi.ime.debug/com.osfans.trime.ime.core.TrimeInputMethodService
adb shell am broadcast -a com.rimi.ime.debug.action.DEPLOY
```

- 首次部署会在真机编译 `rimi.mobile`（30-60s）；部署期间键盘可能空白，
  部署完成后 `am force-stop com.rimi.ime.debug` 再聚焦输入框即可
- 输入框里预编辑残留 RIME 内部缓冲，清空输入框不会清 RIME 组合，需按退格键清掉
