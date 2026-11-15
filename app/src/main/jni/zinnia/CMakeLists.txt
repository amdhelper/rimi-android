# SPDX-FileCopyrightText: 2026 Rimi community
# SPDX-License-Identifier: GPL-3.0-or-later
#
# zinnia 手写识别引擎（识别核心，BSD-3-Clause，见 COPYING）。
# 只编识别所需源码；trainer.cpp 依赖 C++17 已删除的 random_shuffle，不编。
add_library(zinnia STATIC
  character.cpp
  feature.cpp
  param.cpp
  recognizer.cpp
  sexp.cpp
  svm.cpp
)
target_compile_options(zinnia PRIVATE -std=c++14 -fexceptions -frtti)
# mmap.h/param.cpp 靠 HAVE_CONFIG_H 才会包含 config.h（HAVE_FCNTL_H 等门控）。
target_compile_definitions(zinnia PRIVATE HAVE_CONFIG_H)
target_include_directories(zinnia PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
