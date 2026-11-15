/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.handwriting

/**
 * zinnia 手写识别 JNI 桥（实现在 jni/librime_jni/rimi_handwriting.cc）。
 * 符号编在 librime_jni.so 里，与词库同步 JNI 同一个库。
 */
object HandwritingNative {
    init {
        System.loadLibrary("rime_jni")
    }

    /** 打开模型文件，返回 Recognizer 句柄；失败返回 0。 */
    external fun nativeOpenModel(path: String): Long

    /**
     * 识别。points = [x0,y0,...] 视图像素坐标，strokeLengths = 每笔画点数，
     * width/height = 画板尺寸（zinnia 内部归一化）。返回候选字数组，失败 null。
     */
    external fun nativeRecognize(
        handle: Long,
        width: Int,
        height: Int,
        points: FloatArray,
        strokeLengths: IntArray,
        nbest: Int,
    ): Array<String>?

    external fun nativeCloseModel(handle: Long)
}