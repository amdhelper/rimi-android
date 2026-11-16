// SPDX-FileCopyrightText: 2026 Rimi community
// SPDX-License-Identifier: GPL-3.0-or-later
//
// 日弥词库云同步 JNI 桥（安卓侧）。
// 核心逻辑在共享的 rimi_userdb_core.cc（鸿蒙 NAPI 桥共用同一份），
// 本文件只做 JNIEnv ↔ C++ 的参数/返回值转换，禁止在这里写数据口径。

#include <jni.h>

#include <string>

#include "jni-utils.h"
#include "rimi_userdb_core.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_osfans_trime_data_rimi_RimiUserDb_nativeReadUserDict(JNIEnv* env,
                                                             jclass /*clazz*/,
                                                             jstring dict_name) {
  std::string name = *CString(env, dict_name);
  std::string out;
  if (!rimi::UserDbRead(name, &out))
    return nullptr;
  return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_osfans_trime_data_rimi_RimiUserDb_nativeReadUserDictAtPath(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring db_path) {
  std::string db_dir = *CString(env, db_path);
  std::string out;
  if (!rimi::UserDbReadAtPath(db_dir, &out))
    return nullptr;
  return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_osfans_trime_data_rimi_RimiUserDb_nativeApplyUserDict(JNIEnv* env,
                                                              jclass /*clazz*/,
                                                              jstring dict_name,
                                                              jstring tsv) {
  std::string name = *CString(env, dict_name);
  std::string data = *CString(env, tsv);
  return rimi::UserDbApply(name, data);
}

// 按绝对路径写 userdb：引擎已停（Rime Service 也被销毁）时走这里。
// withEngineReleased 窗口内 UserDb::Require("userdb") 拿不到组件，
// nativeApplyUserDict 必然返回 -1；这里直接用 LevelDb 打开路径，不依赖 Service。
extern "C" JNIEXPORT jint JNICALL
Java_com_osfans_trime_data_rimi_RimiUserDb_nativeApplyUserDictAtPath(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring db_path,
    jstring tsv) {
  std::string db_dir = *CString(env, db_path);
  std::string data = *CString(env, tsv);
  return rimi::UserDbApplyAtPath(db_dir, data);
}