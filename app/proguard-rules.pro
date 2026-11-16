# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# disable obfuscation
-dontobfuscate

# Keep JNI interface
-keep class com.osfans.trime.core.* { *; }

# 雨燕手写引擎（BSD-3，vendored）：libyuyanwriting.so 按包名查 JNI 符号，
# 类名不可变不可 shrink。-dontobfuscate 已防改名，这里双保险防 R8 收缩。
-keep class com.yuyan.inputmethod.core.HandWriting { *; }
-keep class com.yuyan.inputmethod.core.HandWriting$* { *; }

# remove kotlin null checks
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkNotNull(...);
    static void checkExpressionValueIsNotNull(...);
    static void checkNotNullExpressionValue(...);
    static void checkReturnedValueIsNotNull(...);
    static void checkFieldIsNotNull(...);
    static void checkParameterIsNotNull(...);
    static void checkNotNullParameter(...);
}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# 2026-11-05 release 包（R8）首次开启：net.i2p.crypto.eddsa 引用了 JDK 内部的
# sun.security.x509.X509Key（Android 上不存在），只在签名实现里做类型判断，缺类不影响运行。
-dontwarn sun.security.**
