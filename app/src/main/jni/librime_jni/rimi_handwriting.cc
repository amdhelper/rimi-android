// SPDX-FileCopyrightText: 2026 Rimi community
// SPDX-License-Identifier: GPL-3.0-or-later
//
// 日弥手写输入 JNI：zinnia 识别引擎（BSD-3-Clause，见 jni/zinnia/COPYING）。
// 模型 = Tegaki 简体中文轻量版（LGPL-2.1，assets/handwriting/）。
// 完全离线：笔画坐标进，候选汉字出，不走网络。
//
// 线程安全由 Kotlin 侧 HandwritingRecognizer 的 synchronized 保证；
// zinnia::Recognizer::classify 本身对同一实例不可重入。

#include <jni.h>

#include <cmath>
#include <string>
#include <vector>

#include "zinnia.h"

namespace {

// 笔画点数组（x,y 交替）+ 每笔画点数 → zinnia Character。
// 坐标是画板视图像素，zinnia 内部按 width/height 归一化（feature.cpp）。
bool fillCharacter(zinnia::Character* ch,
                   jint width,
                   jint height,
                   const jfloat* pts,
                   jsize ptsLen,
                   const jint* strokeLens,
                   jsize strokeCount) {
  if (width <= 0 || height <= 0 || ptsLen % 2 != 0) return false;
  ch->clear();
  ch->set_width(static_cast<size_t>(width));
  ch->set_height(static_cast<size_t>(height));
  jsize cursor = 0;
  for (jsize s = 0; s < strokeCount; ++s) {
    const jint n = strokeLens[s];
    if (n <= 0 || cursor + 2 * n > ptsLen) return false;
    for (jint i = 0; i < n; ++i) {
      const int x = static_cast<int>(std::lround(pts[cursor + 2 * i]));
      const int y = static_cast<int>(std::lround(pts[cursor + 2 * i + 1]));
      if (!ch->add(static_cast<size_t>(s), x, y)) return false;
    }
    cursor += 2 * n;
  }
  return cursor == ptsLen;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_osfans_trime_data_handwriting_HandwritingNative_nativeOpenModel(
    JNIEnv* env, jobject /*thiz*/, jstring path) {
  const char* cpath = env->GetStringUTFChars(path, nullptr);
  if (!cpath) return 0;
  zinnia::Recognizer* rec = zinnia::Recognizer::create();
  const bool ok = rec && rec->open(cpath);
  env->ReleaseStringUTFChars(path, cpath);
  if (!ok) {
    delete rec;
    return 0;
  }
  return reinterpret_cast<jlong>(rec);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_osfans_trime_data_handwriting_HandwritingNative_nativeRecognize(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jint width,
    jint height,
    jfloatArray points,
    jintArray strokeLengths,
    jint nbest) {
  auto* rec = reinterpret_cast<zinnia::Recognizer*>(handle);
  if (!rec || !points || !strokeLengths || nbest <= 0) return nullptr;

  const jsize ptsLen = env->GetArrayLength(points);
  const jsize strokeCount = env->GetArrayLength(strokeLengths);
  if (ptsLen == 0 || strokeCount == 0) return nullptr;
  const jfloat* pts = env->GetFloatArrayElements(points, nullptr);
  const jint* lens = env->GetIntArrayElements(strokeLengths, nullptr);
  if (!pts || !lens) {
    if (pts) env->ReleaseFloatArrayElements(points, const_cast<jfloat*>(pts), JNI_ABORT);
    if (lens) env->ReleaseIntArrayElements(strokeLengths, const_cast<jint*>(lens), JNI_ABORT);
    return nullptr;
  }

  zinnia::Character* ch = zinnia::Character::create();
  const bool filled = ch && fillCharacter(ch, width, height, pts, ptsLen, lens, strokeCount);
  env->ReleaseFloatArrayElements(points, const_cast<jfloat*>(pts), JNI_ABORT);
  env->ReleaseIntArrayElements(strokeLengths, const_cast<jint*>(lens), JNI_ABORT);
  if (!filled) {
    delete ch;
    return nullptr;
  }

  zinnia::Result* result = rec->classify(*ch, static_cast<size_t>(nbest));
  delete ch;
  if (!result) return nullptr;

  const size_t n = result->size();
  jclass strClass = env->FindClass("java/lang/String");
  jobjectArray arr = env->NewObjectArray(static_cast<jsize>(n), strClass, nullptr);
  if (arr) {
    for (size_t i = 0; i < n; ++i) {
      jstring s = env->NewStringUTF(result->value(i));
      if (s) {
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
      }
    }
  }
  delete result;
  return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_osfans_trime_data_handwriting_HandwritingNative_nativeCloseModel(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
  delete reinterpret_cast<zinnia::Recognizer*>(handle);
}