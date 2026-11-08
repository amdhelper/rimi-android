// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

/**
 * The bundled theme is the last resort when the data dirs cannot be read (direct
 * boot / external storage not mounted yet). If the shipped trime.yaml ever stopped
 * decoding, that fallback would be dead code and the input method would crash on
 * boot again, so the real asset is decoded here instead of a fixture.
 */
class ThemeLoaderBundledTest :
    BehaviorSpec({
        fun assetReader(): (String) -> String? = { path ->
            // Unit tests run either from the module dir or from the repo root.
            listOf(File("src/main/assets"), File("app/src/main/assets"))
                .map { File(it, path) }
                .firstOrNull { it.isFile }
                ?.readText()
        }

        Given("the theme shipped inside the APK") {
            Then("it decodes from assets alone, without any data dir") {
                val result = ThemeLoader.loadBundledTheme("trime", assetReader())
                result.shouldBeInstanceOf<ThemeLoader.ThemeLoadResult.Success>()
                val theme = (result as ThemeLoader.ThemeLoadResult.Success).theme
                theme.presetColorSchemes.isNotEmpty() shouldBe true
                theme.presetColorSchemes.containsKey("default") shouldBe true
                // 爸爸要求键盘默认深色：内置 trime.yaml 的 default 方案是 Catppuccin Mocha 深色板。
                theme.presetColorSchemes.getValue("default")["name"] shouldBe "Zoom Dark"
            }

            Then("a missing asset is reported as a failure instead of throwing") {
                val result = ThemeLoader.loadBundledTheme("does-not-exist", assetReader())
                result.shouldBeInstanceOf<ThemeLoader.ThemeLoadResult.Failure>()
                result.error.shouldBeInstanceOf<ThemeLoader.ThemeLoadError.FileUnreadable>()
            }
        }
    })
