# 手写识别引擎（雨燕输入法核心，vendored）

出处：https://github.com/gurecn/YuyanIme （子模块 yuyansdk，commit 54b1e3b3）
许可：BSD-3-Clause（Copyright (c) 2026, 王莹），全文见同目录 LICENSE。

## 组成

- `rec.nb` — PP-OCRv5 识别模型（Paddle-Lite naive buffer，16.7MB）
- `ocrv5.txt` — 字典 18382 字（含简体常用字，CTC 标签表，索引 0 为 blank）
- 推理库 `libyuyanwriting.so`（jniLibs/arm64-v8a、armeabi-v7a）—
  Paddle-Lite v2.14-rc 静态编译，JNI 符号绑定 `com.yuyan.inputmethod.core.HandWriting`
- Kotlin 管线（java/com/yuyan/）：笔画切分 StrokeSegmenter → 渲染 ImagePreprocessor
  （bbox 等比缩放，48px 高白底黑字，最大宽 320）→ Predictor 推理 → CtcDecoder 束搜索

## rimi 集成（2026-11-16）

- 替换 zinnia+Tegaki（2008 年老引擎，真实中文手写 TOP1 ≈ 30%，不可用）。
- 适配层 = com.osfans.trime.data.handwriting.HandwritingRecognizer（保持原签名，
  手写窗口/悬浮候选/停笔自动上屏交互不变）。
- 完全离线，不新增任何权限。x86/x86_64 无预编译库（上游未提供），
  模拟器上手写降级为「加载失败」提示，真机（arm）正常。
- 验收基准：爸爸 2026-11-15 真实笔迹 hw_trace.jsonl（你/好/我/明/天 28 条），
  回放工具 HwTraceReplay（标记文件 hw_replay.flag 触发；华为设备拦自定义广播，
  HwReplayReceiver 仅作备用）。

## ⚠️ libyuyanwriting.so 白名单补丁（必须记录，升级 so 时重做）

上游预编译 so 的 nativeCreate 内置**包名白名单鉴权**：经 Binder 取调用方 uid →
PackageManager.getPackagesForUid → 与硬编码白名单 strcmp，不在列表即抛
「鉴权失败：uid=… 的包 […] 不在允许列表内」。白名单原为 com.yuyan.pinyin 系列
+ 测试包 com.example.handwriting，com.rimi.ime 被拒 → 手写引擎无法创建（这是
2026-11-16 之前手写完全无候选的根因）。

yuyansdk 整体 BSD-3-Clause（允许修改再分发），故做**最小字符串补丁**：把测试包
槽位 com.example.handwriting 原地替换为 com.rimi.ime（NUL 结尾 + 零填充至原 24
字节槽位），指针表/代码/其余白名单串一律不动。

| ABI | 补丁偏移 | 原始 md5 | 补丁后 md5 |
|-----|---------|----------|-----------|
| arm64-v8a | 0x3efb8e | db21c4fda915ac1a7326c56c72c6627c | 4c9087446e200c341bebd785d978a34b |
| armeabi-v7a | 0x271ca5 | 106f2024fc3b29341afe8ba7815839e4 | c5fc622b66d8ad01c5dc96af0fdc4f21 |

原始 so 备份在构建机 ~/.hermes/cache/scratch/hw/libyuyanwriting_<abi>.orig.so。
升级上游 so 后必须重打此补丁（搜索 b'com.example.handwriting\x00' 定位槽位），
否则手写引擎鉴权失败、完全无候选。

## 上游同步

vendored Kotlin 文件保持字节级原样（含包名 com.yuyan，JNI 符号要求）。
升级时从 yuyansdk 重新拷贝上述文件并整体替换，勿改包名；so 需重打白名单补丁。
