# 日弥输入法 · 手机版路线图

形态：fork Trime（Android 输入法框架 + librime），换品牌 + 内置日弥方案/词库 + 接 wumi 词库云同步。

## M1 — 跑通真机（已完成 2026-11-04）

- [x] fork osfans/trime → amdhelper/rimi-android，本机工具链跑通（SDK37 / NDK30 / CMake4.1.2 / Gradle 9.8.1）
- [x] 品牌化：applicationId `com.rimi.ime`、应用名「日弥输入法」、日字图标
- [x] 内置日弥方案与词库（8105 字表 + 基础词库 + 医学/易经/紫微八字/术数四库 + 英文 melt_eng）
- [x] 深色键盘主题（Catppuccin Mocha，与桌面 rimi_dark 同色板）
- [x] HarmonyOS 4.3 真机（PLR-AL30）安装、启用、部署、nihao→你好 / baizhu→白术 实测

## M2 — 首启体验

- [ ] 预置编译产物（`assets/shared/build/*.bin`）：把真机编译一次的 table.bin/prism.bin 固化，首启秒开
- [ ] 默认方案必为「日弥」：首次部署完成后写 `user.yaml` 的 `var/previously_selected_schema`
- [ ] 键盘布局品牌化（空格键标「日弥」、候选栏样式、按键配色细节）
- [ ] 引导页文案去 Trime 化（「同文输入法」→「日弥输入法」）

## M3 — wumi 账户与词库同步

- [ ] wumi 登录（Ed25519 challenge/sign → JWT，与桌面 rimid 同一套密钥体系）
- [ ] 用户词库双向同步（RIME userdb ↔ `/rimi/dict/push|pull`，错误码 32xxxx）
- [ ] 打字记忆备份/恢复

## M4 — 语音输入

- [ ] 长按麦克风键录音 → 上传/本地识别 → 候选或直接上屏
- [ ] 复用桌面 ASR 通道（paraformer-large）与医学术语矫正

## M5 — 发布

- [ ] release 签名（keystore 走 CI secret）
- [ ] GitHub Actions 出 arm64 APK + 更新检查
- [ ] 隐私说明 / 开源合规（GPL-3.0 衍生，附源码获取方式）
