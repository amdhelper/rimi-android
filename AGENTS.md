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

## 预编译数据（首启加速）

`app/src/main/assets/shared/build/` 内置了在真机部署一次后拉回的编译产物
（`rimi.mobile.table.bin` 18MB / `rimi.prism.bin` / `rimi.mobile.reverse.bin` /
`luna_pinyin.*` / `stroke.*` / 编译后的 `default.yaml`、`*.schema.yaml`），
放在这里等价于 librime 的 `prebuilt_data_dir`（shared_data_dir/build），
有它时首启直接复用，不再花 ~40s 从 26MB 词库源码重编译。

重生成步骤（改了词库/方案或升级 librime 后必须做）：

```bash
adb shell rm -rf /sdcard/Android/data/com.rimi.ime.debug/files/rime/build
adb shell am broadcast -a com.rimi.ime.debug.action.DEPLOY   # 或 App 内点「部署」
adb pull /sdcard/Android/data/com.rimi.ime.debug/files/rime/build /tmp/rimi_prebuilt
cp /tmp/rimi_prebuilt/build/* app/src/main/assets/shared/build/
python3 ../rimi/scripts/build_mobile_assets.py --with-english   # 重新生成 checksums.json 前的资产
./gradlew :app:assembleDebug
```

- 词库源码（cn_dicts/en_dicts）仍随包发布，预编译产物失效时 librime 会按 checksum 自动回退重编译
- 首次部署细节：词库表用 checksum 判断（内容哈希），方案 YAML 用 mtime 判断；
  所以我们保留源码也不会触发 18MB 重编译
- 注意 APK 体积：预编译数据让 debug APK 从 30MB 涨到 48MB，release 需评估裁剪


## 日弥云同步（M3，2026-11-04）

手机端与桌面 rimid 共用一套 wumi 身份与 `/rimi/dict` 协议，同步的就是 RIME 自己学出来的
个人短语库（`user.userdb`），不另建一套词库。

- 账户登录：wumi 12/24 词助记词或 64 位十六进制 seed（桌面 `~/.config/rimi/ed25519.seed`）。
  `seed = SHA256(entropy)`（wumi 口径，不是 BIP39 PBKDF2），seed 用 AndroidKeyStore AES/GCM 落盘。
- 同步：读本地 userdb → 与上次快照比对 push 增量（含墓碑）→ pull 远端增量 → 直接写回 userdb
  → 有写入则 `updateConfig()` 重启引擎，让新词立刻参与候选排序。
- 触发：设置页「立即同步」；`RimiCloudSyncWork` 周期后台同步（默认 360 分钟，可调，≥15）。
  键盘可见时后台任务主动让路（`TrimeInputMethodService.isInputViewShowing`）。
- 代码地图（`app/src/main/java/com/osfans/trime/data/rimi/`）：
  `RimiCloud`（认证 + HTTP）、`RimiDictSync`（同步算法/快照）、`RimiUserDb`（JNI TSV 桥）、
  `RimiSeedVault`、`RimiBip39`；设置页 `ui/main/settings/RimiSyncFragment.kt`；
  周期任务 `worker/RimiCloudSyncWork.kt`。
- JNI：`app/src/main/jni/librime_jni/rimi_sync.cc` 直接开 userdb 写 `c=1 d=<weight> t=0`。
  **不要用 librime levers 的 import_user_dict 代替**：它只写 commits、`dee` 恒为 0，拉下来的词权重丢失。
- ⚠️ 新增 native 源文件必须显式写进 `librime_jni/CMakeLists.txt`：`aux_source_directory` 的结果在
  CMake configure 时固化，只放文件不重新 configure 不会进 so，运行期报 UnsatisfiedLinkError。
- ⚠️ Kotlin 块注释可嵌套：KDoc 里写 `/rimi/dict/*` 会开启嵌套注释，编译报 "Unclosed comment"。
- 实测（PLR-AL30 / Android 12）：seed 登录 → 云端 push `云同步测试` → 手机同步（拉取 1 应用 1）
  → 打 `yuntongbuceshi` 候选出现该词；手机提交候选生成 `你好` → 同步上传 1 → 云端 `/rimi/dict/status` = 2 条。

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
