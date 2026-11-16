#!/usr/bin/env python3
"""生成手写部件组合表 app/src/main/assets/hw/ids_lr.txt。

数据源：cjkvi/cjkvi-ids 的 ids.txt（https://github.com/cjkvi/cjkvi-ids，
IDS 表为公共领域数据，维护者 CJKVI）。只取恰好 ⿰XY（左右结构、两部件各为
单字符）且字头在 PP-OCRv5 字典（ocrv5.txt）里的条目——组合识别只能产出
模型认得的字。部件本身不要求在字典里（亻/氵 等偏旁用于整字候选分类）。

用法：python3 scripts/build_hw_ids.py <ids.txt 路径>
输出：每行 字\t左部件\t右部件（约 9260 条，111KB）。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DICT = ROOT / "app/src/main/assets/hw/ocrv5.txt"
OUT = ROOT / "app/src/main/assets/hw/ids_lr.txt"


def is_cjk(c: str) -> bool:
    o = ord(c)
    return (
        0x4E00 <= o <= 0x9FFF
        or 0x3400 <= o <= 0x4DBF
        or 0xF900 <= o <= 0xFAFF
        or 0x2E80 <= o <= 0x2EFF  # 康熙部首
        or 0x31C0 <= o <= 0x31EF  # 笔画部件
        or c == "〇"
    )


def main() -> None:
    ids_path = Path(sys.argv[1])
    dict_chars = {l.strip() for l in DICT.read_text(encoding="utf-8").splitlines() if l.strip()}
    out, seen = [], set()
    for line in ids_path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        ch, ids = parts[1], parts[2]
        if len(ch) != 1 or ch not in dict_chars or ch in seen:
            continue
        m = re.fullmatch(r"⿰(.)(.)", ids)
        if not m:
            continue
        left, right = m.group(1), m.group(2)
        if not (is_cjk(left) and is_cjk(right)):
            continue
        seen.add(ch)
        out.append(f"{ch}\t{left}\t{right}")
    OUT.write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"entries={len(out)} -> {OUT}")


if __name__ == "__main__":
    main()