// SPDX-FileCopyrightText: 2026 Rimi community
// SPDX-License-Identifier: GPL-3.0-or-later
//
// 日弥词库云同步 JNI：直接读写 librime 的个人短语库（userdb）。
//
// 与桌面端 rimid/userdb.rs 保持同一数据口径：
//   key   = "<code> \t<term>"
//   value = "c=<commits> d=<dee> t=<tick>"
// 候选排序用的是 dee（见 UserDictionary::TranslateWord），所以云端下发的
// weight 必须写进 dee，不能用 lever 的 import（那条路只会写 commits，权重丢失）。

#include <jni.h>

#include <rime/dict/db.h>
#include <rime/dict/db_utils.h>
#include <rime/dict/user_db.h>
#include <rime/service.h>

#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "jni-utils.h"

using namespace rime;

namespace {

std::string trim_right(std::string s) {
  while (!s.empty() &&
         (s.back() == ' ' || s.back() == '\t' || s.back() == '\r'))
    s.pop_back();
  return s;
}

std::vector<std::string> split_tab(const std::string& s) {
  std::vector<std::string> out;
  size_t start = 0;
  while (true) {
    size_t pos = s.find('\t', start);
    if (pos == std::string::npos) {
      out.push_back(s.substr(start));
      break;
    }
    out.push_back(s.substr(start, pos - start));
    start = pos + 1;
  }
  return out;
}

/// 把 userdb 的每条记录收集成 TSV：term \t code \t commits \t dee
class CollectSink : public DbSink {
 public:
  CollectSink(Db* db, std::string* out) : DbSink(db), out_(out) {}

  bool MetaPut(const std::string& /*key*/, const std::string& /*value*/) override {
    return true;  // 只读，不回写 metadata
  }

  bool Put(const std::string& key, const std::string& value) override {
    size_t tab = key.find('\t');
    if (tab == std::string::npos)
      return true;
    std::string code = trim_right(key.substr(0, tab));
    std::string term = key.substr(tab + 1);
    if (code.empty() || term.empty())
      return true;
    UserDbValue v(value);
    if (v.commits < 0)
      return true;  // 墓碑不导出
    char buf[64];
    snprintf(buf, sizeof(buf), "%.3f", v.dee);
    out_->append(term);
    out_->push_back('\t');
    out_->append(code);
    out_->push_back('\t');
    out_->append(std::to_string(v.commits));
    out_->push_back('\t');
    out_->append(buf);
    out_->push_back('\n');
    return true;
  }

 private:
  std::string* out_;
};

Db* create_user_db(const std::string& name) {
  auto* component = UserDb::Require("userdb");
  if (!component)
    return nullptr;
  return component->Create(name);
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_osfans_trime_data_rimi_RimiUserDb_nativeReadUserDict(JNIEnv* env,
                                                             jclass /*clazz*/,
                                                             jstring dict_name) {
  std::string name = *CString(env, dict_name);
  the<Db> db(create_user_db(name));
  if (!db)
    return nullptr;
  if (!db->OpenReadOnly())
    return nullptr;
  std::string out;
  {
    DbSource source(db.get());
    CollectSink sink(db.get(), &out);
    source.Dump(&sink);
  }
  db->Close();
  return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_osfans_trime_data_rimi_RimiUserDb_nativeApplyUserDict(JNIEnv* env,
                                                              jclass /*clazz*/,
                                                              jstring dict_name,
                                                              jstring tsv) {
  std::string name = *CString(env, dict_name);
  std::string data = *CString(env, tsv);
  the<Db> db(create_user_db(name));
  if (!db)
    return -1;
  if (!db->Open())
    return -1;
  // 全新库（还没打过字）没有 metadata，librime 之后会认不出这是 userdb
  if (!UserDbHelper(db.get()).IsUserDb()) {
    db->CreateMetadata();
  }
  int applied = 0;
  size_t start = 0;
  while (start < data.size()) {
    size_t end = data.find('\n', start);
    if (end == std::string::npos)
      end = data.size();
    std::string line = data.substr(start, end - start);
    start = end + 1;
    if (line.empty())
      continue;
    auto cols = split_tab(line);
    if (cols.size() < 4)
      continue;
    std::string term = cols[0];
    std::string code = trim_right(cols[1]);
    if (term.empty() || code.empty())
      continue;
    bool deleted = cols[3] == "1";
    std::string key = code + " \t" + term;
    if (deleted) {
      if (db->Erase(key))
        ++applied;
      continue;
    }
    double weight = 1.0;
    try {
      weight = std::stod(cols[2]);
    } catch (...) {
      weight = 1.0;
    }
    if (!std::isfinite(weight) || weight < 1.0)
      weight = 1.0;
    char buf[64];
    snprintf(buf, sizeof(buf), "c=1 d=%.3f t=0", weight);
    if (db->Update(key, buf))
      ++applied;
  }
  db->Close();
  return applied;
}
