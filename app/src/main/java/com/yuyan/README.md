# vendored：雨燕输入法手写核心（BSD-3-Clause）

出处 https://github.com/gurecn/YuyanIme 子模块 yuyansdk（commit 54b1e3b3）。
本目录 Kotlin 文件字节级原样保留（包名 com.yuyan 不可改：
jniLibs 里的 libyuyanwriting.so 按 `Java_com_yuyan_inputmethod_core_HandWriting_*`
查 JNI 符号，改包名 = UnsatisfiedLinkError）。

许可文件：assets/hw/LICENSE。集成说明：assets/hw/README.md。
适配层在 com.osfans.trime.data.handwriting.HandwritingRecognizer。
