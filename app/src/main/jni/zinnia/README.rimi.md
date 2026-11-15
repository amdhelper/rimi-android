# zinnia (vendored, recognition-only subset)

Upstream: https://github.com/taku910/zinnia (zinnia 0.07, BSD-3-Clause, see COPYING)
Vendored: 2026-11-15 for rimi handwriting input (手写输入).

Only the recognition core is included (no trainer / C API / CLI):
character / feature / param / recognizer / sexp / svm.
`config.h` is a minimal Android replacement for the autoconf output.
`trainer.cpp` is deliberately excluded (uses std::random_shuffle, removed in C++17).
`svm.cpp` carries one portability patch: the training solver's std::random_shuffle
is replaced by Fisher-Yates + rand() (never called on the recognition path).

Model: Tegaki simplified Chinese (light), LGPL-2.1, bundled at
app/src/main/assets/handwriting/handwriting-zh_CN.model
(https://github.com/tegaki/tegaki/releases/tag/v0.3).
