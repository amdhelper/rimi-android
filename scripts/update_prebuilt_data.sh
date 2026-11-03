#!/usr/bin/env bash
# 从真机拉取 RIME 部署产物到 assets/shared/build（预编译数据）
# 适用：改了方案/词库、或升级 librime 之后。用法：bash scripts/update_prebuilt_data.sh
set -euo pipefail
PKG="${PKG:-com.rimi.ime.debug}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/shared/build"
DEV="/sdcard/Android/data/$PKG/files"

echo "[1/4] 清掉设备上的旧编译产物"
adb shell "rm -rf $DEV/rime/build"

echo "[2/4] 启动 App 并触发部署（App 需先安装并启用）"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 2
adb shell am broadcast -a "$PKG.action.DEPLOY" >/dev/null

echo "[3/4] 等待部署完成（user.yaml 的 last_build_time 变化）"
sql_before=$(adb shell "grep last_build_time $DEV/rime/user.yaml 2>/dev/null" || true)
for i in $(seq 1 120); do
  cur=$(adb shell "grep last_build_time $DEV/rime/user.yaml 2>/dev/null" || true)
  if [ -n "$cur" ] && [ "$cur" != "$sql_before" ]; then break; fi
  sleep 1
done
adb shell "ls $DEV/rime/build/rimi.schema.yaml" >/dev/null

echo "[4/4] 拉回并合并到 assets/shared/build"
rm -rf /tmp/rimi_prebuilt && mkdir -p /tmp/rimi_prebuilt
adb pull "$DEV/rime/build/." /tmp/rimi_prebuilt/ >/dev/null
mkdir -p "$OUT"
# 合并而非清空：设备上没重建的词库表（.table.bin）要继续沿用现有预编译文件
cp -r /tmp/rimi_prebuilt/. "$OUT"/
du -sh "$OUT"
