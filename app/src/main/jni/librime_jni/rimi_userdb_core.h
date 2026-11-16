// SPDX-FileCopyrightText: 2026 Rimi community
// SPDX-License-Identifier: GPL-3.0-or-later
//
// 日弥词库云同步 · userdb 读写核心（纯 C++，无 JNI/NAPI 依赖）。
// 安卓（librime_jni/rimi_sync.cc）与鸿蒙（platforms/harmonyos/native/rime_napi.cc）
// 共用这一份实现——数据口径的单一事实源，禁止在桥接层另写一套。
//
// 与桌面端 rimid/userdb.rs 保持同一数据口径：
//   key   = "<code> \t<term>"
//   value = "c=<commits> d=<dee> t=<tick>"
// 候选排序用的是 dee（见 UserDictionary::TranslateWord），所以云端下发的
// weight 必须写进 dee，不能用 lever 的 import（那条路只会写 commits，权重丢失）。

#ifndef RIMI_USERDB_CORE_H_
#define RIMI_USERDB_CORE_H_

#include <string>

namespace rimi {

// 读 userdb（经 UserDb::Require("userdb")，要求本进程引擎已初始化）。
// 导出 TSV：term \t code \t commits \t dee（墓碑不导出）。
// 返回 true=成功（out 可为空串=空库）；false=库打不开。
// 调用方必须区分「空库」与「打不开」，否则会把读取失败当成用户删光了词。
bool UserDbRead(const std::string& dict_name, std::string* out_tsv);

// 按绝对路径只读打开 LevelDb（引擎占用 userdb 时的无锁快照副本读 / 停引擎后直读）。
bool UserDbReadAtPath(const std::string& db_dir, std::string* out_tsv);

// 写 TSV：term \t code \t weight \t deleted(0/1)。
// 返回实际写入/删除条数；-1=库打不开。weight 写进 dee（c=1 d=<weight> t=0）。
int UserDbApply(const std::string& dict_name, const std::string& tsv);

// 按绝对路径写（引擎已停、Rime Service 不可用时用；不经 UserDb::Require）。
// 全新库会自动补 metadata（/db_name 等），否则 librime 之后认不出这是 userdb。
int UserDbApplyAtPath(const std::string& db_dir, const std::string& tsv);

}  // namespace rimi

#endif  // RIMI_USERDB_CORE_H_