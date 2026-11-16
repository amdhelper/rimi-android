# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

find_package(Opencc REQUIRED)

aux_source_directory(. RIME_JNI_SOURCES)
# aux_source_directory 的结果在 CMake configure 时固化；新增文件若没改 CMakeLists 不会被收录，
# 这里显式列出日弥云同步 JNI，避免“代码写了但符号不在 so 里”的 UnsatisfiedLinkError。
list(APPEND RIME_JNI_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/rimi_sync.cc")
add_library(rime_jni SHARED ${RIME_JNI_SOURCES})
target_link_libraries(rime_jni rime-static ${Opencc_LIBRARY})
target_include_directories(rime_jni PRIVATE
  "${CMAKE_BINARY_DIR}/librime/src"
  "${CMAKE_SOURCE_DIR}/librime/src"
  "${Opencc_INCLUDE_PATH}"
)
