// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.yamlMap
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.util.get
import com.osfans.trime.util.mapping
import com.osfans.trime.util.pairs
import com.osfans.trime.util.string
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.io.File

class ThemeDslExpanderTest :
    BehaviorSpec({
        fun expand(
            yaml: String,
            resourceId: String = "theme",
            resources: Map<String, String> = emptyMap(),
        ): YamlNode = ThemeDslExpander.expand(
            resourceId,
            ThemeTestSupport.yaml.parseToYamlNode(yaml),
        ) { id ->
            resources[id]?.let { ThemeTestSupport.yaml.parseToYamlNode(it) }
        }

        Given("a theme with an __include to a local path") {
            val expanded = expand(
                """
                preset_keyboards:
                  default:
                    name: base
                    keys: [q, w]
                    nested: {a: 1, b: 2}
                letter:
                  __include: /preset_keyboards/default
                  ascii_mode: 1
                  nested: {b: 3}
                """.trimIndent(),
            )

            Then("the included keys are inherited and sibling keys override them") {
                val letter = expanded.pairs!!["letter"]!!.mapping!!
                letter.pairs["name"]!!.string shouldBe "base"
                letter.pairs["ascii_mode"]!!.string shouldBe "1"
            }

            Then("mappings merge recursively") {
                val nested = expanded.pairs!!["letter"]!!.mapping!!.pairs["nested"]!!.mapping!!
                nested.pairs["a"]!!.string shouldBe "1"
                nested.pairs["b"]!!.string shouldBe "3"
            }
        }

        Given("a theme including another resource") {
            val resources = mapOf(
                "base" to """
                    preset_keyboards:
                      default:
                        name: from_base
                        keys: [a]
                """.trimIndent(),
            )

            Then("the reference resolves through the resource loader") {
                val expanded = expand(
                    "keyboard:\n  __include: base.yaml:/preset_keyboards/default\n",
                    resources = resources,
                )
                expanded.pairs!!["keyboard"]!!.mapping!!.pairs["name"]!!.string shouldBe "from_base"
            }

            Then("a missing resource fails") {
                shouldThrow<ThemeDslExpander.UnresolvedReference> {
                    expand("keyboard:\n  __include: missing:/foo\n")
                }
            }

            Then("a missing resource is ignored when marked optional") {
                val expanded = expand("keyboard:\n  __include: missing:/foo?\n  own: 1\n")
                expanded.pairs!!["keyboard"]!!.mapping!!.pairs["own"]!!.string shouldBe "1"
            }

            Then("a missing patch is ignored when marked optional") {
                val expanded = expand("keyboard:\n  own: 1\n  __patch: \"missing:/patch?\"\n")
                expanded.pairs!!["keyboard"]!!.mapping!!.pairs["own"]!!.string shouldBe "1"
            }
        }

        Given("a theme with circular references") {
            Then("expansion fails") {
                shouldThrow<ThemeDslExpander.UnresolvedReference> {
                    expand("a: {__include: /b}\nb: {__include: /a}\n")
                }
            }
        }

        Given("a theme using unsupported DSL constructs") {
            Then("every construct outside the subset is refused") {
                listOf(
                    // __append / __merge directives
                    "a: {__append: [1]}",
                    "a: {__merge: {b: 1}}",
                    // path and list operators
                    "a: {keys/@next: 1}",
                    "a: {nested/key: 1}",
                    "a: {list/+: [1]}",
                    "a: {key/=: 1}",
                    // a __patch list, and a patch of the wrong type
                    "a: {__patch: [{k: 1}]}",
                    "s: scalar\na: {__patch: /s}",
                    // a non-scalar __include
                    "a: {__include: [/x, /y]}",
                    "a: {__include: {k: 1}}",
                ).forEach { yaml ->
                    shouldThrow<ThemeDslExpander.UnsupportedDsl> { expand(yaml) }
                }
            }

            Then("patching a non-mapping node is rejected") {
                shouldThrow<ThemeDslExpander.UnsupportedDsl> {
                    expand("s: scalar\na: {__include: /s, __patch: {k: 1}}\n")
                }
            }

            Then("merging a mapping into a non-mapping sibling is rejected") {
                shouldThrow<ThemeDslExpander.UnsupportedDsl> {
                    expand("base: {keys: []}\na: {__include: /base, keys: {b: 1}}\n")
                }
            }

            Then("patching a node without an include overwrites it") {
                expand("a: {__patch: {k: 1}}\n")["a"]!!.mapping!!.pairs["k"]!!.string shouldBe "1"
            }
        }

        Given("a sibling key of an __include") {
            val template = "base: {a: 1, b: 2}\n"

            Then("a key without a value leaves the included value untouched") {
                val a = expand(template + "x: {__include: /base, a: }\n")["x"]!!.mapping!!
                a.pairs["a"]!!.string shouldBe "1"
            }

            Then("an empty string replaces the included value") {
                val a = expand(template + "x: {__include: /base, a: \"\"}\n")["x"]!!.mapping!!
                a.pairs["a"]!!.string shouldBe ""
            }

            Then("librime's other null spellings leave the included value untouched too") {
                listOf("~", "null", "Null", "NULL").forEach { spelling ->
                    val a = expand(template + "x: {__include: /base, a: $spelling}\n")["x"]!!.mapping!!
                    a.pairs["a"]!!.string shouldBe "1"
                }
            }

            Then("a quoted null spelling is still a string") {
                val a = expand(template + "x: {__include: /base, a: 'null'}\n")["x"]!!.mapping!!
                a.pairs["a"]!!.string shouldBe "null"
            }
        }

        Given("a theme reusing a node through a YAML anchor") {
            val expanded = expand(
                """
                inner: {v: 1}
                base: &base
                  __include: /inner
                  extra: 2
                copy: *base
                """.trimIndent(),
            )

            Then("the aliased node is expanded as well, once") {
                // kaml resolves an alias to an equal but distinct node, so the two
                // copies are expanded separately and are equal by content, not identity.
                expanded.pairs!!["copy"]!!.mapping!!.pairs["v"]!!.string shouldBe "1"
                expanded.pairs!!["copy"]!!.mapping!!.pairs["extra"]!!.string shouldBe "2"
            }
        }

        Given("a theme using __patch") {
            val template = """
                preset_keyboards:
                  default: {name: base, keys: [q]}
                patches:
                  p: {name: patched}
            """.trimIndent()

            Then("a literal patch overwrites keys") {
                val expanded = expand(
                    template + "\nletter:\n  __include: /preset_keyboards/default\n  __patch: {name: literal}\n",
                )
                expanded.pairs!!["letter"]!!.mapping!!.pairs["name"]!!.string shouldBe "literal"
            }

            Then("a referenced patch overwrites keys") {
                val expanded = expand(
                    template + "\nletter:\n  __include: /preset_keyboards/default\n  __patch: /patches/p\n",
                )
                expanded.pairs!!["letter"]!!.mapping!!.pairs["name"]!!.string shouldBe "patched"
            }
        }

        Given("a theme with nested directives") {
            Then("directives inside included nodes and sequence items are expanded") {
                val expanded = expand(
                    """
                    inner: {v: 1}
                    middle:
                      __include: /inner
                      extra: 2
                    outer:
                      list:
                        - {__include: /middle}
                    """.trimIndent(),
                )
                val item = expanded.pairs!!["outer"]!!.mapping!!.pairs["list"]!!.mapping
                item shouldBe null
                val sequence = expanded.pairs!!["outer"]!!.mapping!!.pairs["list"] as YamlList
                val merged = sequence[0].mapping!!
                merged.pairs["v"]!!.string shouldBe "1"
                merged.pairs["extra"]!!.string shouldBe "2"
            }
        }

        Given("the built-in trime.yaml") {
            val file = File("src/main/assets/shared/trime.yaml")
            val expanded = ThemeDslExpander.expand("trime", ThemeTestSupport.yaml.parseToYamlNode(file.readText())) { null }

            Then("the 'letter' keyboard inherits the default keyboard") {
                val keyboards = expanded.pairs!!["preset_keyboards"]!!.mapping!!
                val default = keyboards.pairs["default"]!!.mapping!!
                val letter = keyboards.pairs["letter"]!!.mapping!!
                letter.pairs["ascii_mode"]!!.string shouldBe "1"
                letter.pairs["keys"] shouldBe default.pairs["keys"]
                letter.pairs["name"] shouldBe default.pairs["name"]
                letter.pairs["height"] shouldBe default.pairs["height"]
            }

            Then("the pure include 'scj6' equals its target keyboard") {
                val keyboards = expanded.pairs!!["preset_keyboards"]!!.mapping!!
                keyboards.pairs["scj6"] shouldBe keyboards.pairs["cangjie5"]
            }

            Then("the expansion is accepted by the theme decoder") {
                val theme = ThemeTestSupport.yaml.decodeFromYamlNode<Theme>(expanded.yamlMap)
                theme.presetKeyboards.getValue("letter").keys.size shouldBe
                    theme.presetKeyboards.getValue("default").keys.size
                theme.presetKeyboards.getValue("scj6").keys.size shouldBe
                    theme.presetKeyboards.getValue("cangjie5").keys.size
            }

            Then("every BackSpace key clears the whole composition on long press") {
                // 2026-11-04 爸爸需求：打出一串待选词时长按退格 = 整串清空（Escape）。
                // 只加 long_click 不够——GestureFrame 必须把长按优先于 repeatable，
                // 这里锁住键盘定义，防止后续改 trime.yaml 时把该动作静默删掉。
                val keyboards = expanded.pairs!!["preset_keyboards"]!!.mapping!!
                var checked = 0
                for ((name, kbNode) in keyboards.pairs) {
                    val keys = kbNode.mapping?.pairs?.get("keys") as? YamlList ?: continue
                    for (keyNode in keys.items) {
                        val keyMap = keyNode.mapping ?: continue
                        if (keyMap.pairs["click"]?.string != "BackSpace") continue
                        checked++
                        withClue("keyboard=$name") {
                            keyMap.pairs["long_click"]?.string shouldBe "Escape"
                        }
                    }
                }
                (checked > 0) shouldBe true
            }

            Then("voice input lives on space long press, no standalone mic key") {
                // 2026-11-04 爸爸令：🎤 独立键拿掉，语音改挂空格长按（省底排空间）。
                // 锁两点：① 任何键盘不得再有 click==VoiceInput 的独立麦克风键；
                // ② 26 键主键盘（qwerty）的空格键 long_click 必须是 VoiceInput。
                val keyboards = expanded.pairs!!["preset_keyboards"]!!.mapping!!
                for ((name, kbNode) in keyboards.pairs) {
                    val keys = kbNode.mapping?.pairs?.get("keys") as? YamlList ?: continue
                    for (keyNode in keys.items) {
                        val keyMap = keyNode.mapping ?: continue
                        withClue("keyboard=$name must not have a standalone VoiceInput key") {
                            (keyMap.pairs["click"]?.string == "VoiceInput") shouldBe false
                        }
                    }
                }
                val qwertyKeys = keyboards.pairs["qwerty"]!!.mapping!!.pairs["keys"] as YamlList
                val spaceKey = qwertyKeys.items.mapNotNull { it.mapping }.first {
                    it.pairs["click"]?.string == "space"
                }
                spaceKey.pairs["long_click"]?.string shouldBe "VoiceInput"
            }

            Then("t9 is a four-column grid: punctuation column + 9 letter keys") {
                // 2026-11-08 爸爸令：九宫格一行 3 键改 4 键，九个主力键整体右移一列，
                // 最左列放最常用标点 ，。？；中文态只显示字母，数字态才显示数字。
                val keyboards = expanded.pairs!!["preset_keyboards"]!!.mapping!!
                val t9Node = keyboards.pairs["t9"]!!.mapping!!
                t9Node.pairs["width"]?.string shouldBe "25"
                val keyMaps = (t9Node.pairs["keys"] as YamlList).items.mapNotNull { it.mapping }
                keyMaps.mapNotNull { it.pairs["click"]?.string } shouldBe
                    listOf(
                        "T9_COMMA", "T9_1", "T9_2", "T9_3",
                        "T9_PERIOD", "T9_4", "T9_5", "T9_6",
                        "T9_QUESTION", "T9_7", "T9_8", "T9_9",
                        "Keyboard_symbols", "T9_0", "BackSpace",
                        "space", "Return",
                    )

                val theme = ThemeTestSupport.yaml.decodeFromYamlNode<Theme>(expanded.yamlMap)
                // 主键标签随 ascii_mode 切换：中文=字母，数字态=数字（键值始终是 send 的数字）
                theme.presetKeys.getValue("T9_2").let {
                    it.send shouldBe "2"
                    it.states shouldBe listOf("ABC", "2")
                    it.toggle shouldBe "ascii_mode"
                }
                theme.presetKeys.getValue("T9_9").states shouldBe listOf("WXYZ", "9")
                theme.presetKeys.getValue("T9_COMMA").states shouldBe listOf("，", ",")
                theme.presetKeys.getValue("T9_1").label shouldBe "1"

                val t9 = theme.presetKeyboards.getValue("t9")
                t9.keys.size shouldBe 17
                t9.keys.first().click shouldBe KeyActionToken.Plain("T9_COMMA")
                // 底排仍是 82% 空格 + 18% 回车；空格长按 = 语音转写
                val spaceKey = keyMaps.first { it.pairs["click"]?.string == "space" }
                spaceKey.pairs["long_click"]?.string shouldBe "VoiceInput"
                spaceKey.pairs["width"]?.string shouldBe "82"
                val returnKey = keyMaps.first { it.pairs["click"]?.string == "Return" }
                returnKey.pairs["width"]?.string shouldBe "18"
                returnKey.pairs["label"]?.string shouldBe "回车"
                // 0 有独立键（加宽），不再挂在 1 的长按
                keyMaps.first { it.pairs["click"]?.string == "T9_1" }.pairs["long_click"] shouldBe null
                keyMaps.first { it.pairs["click"]?.string == "T9_0" }.pairs["width"]?.string shouldBe "50"
                // 2026-11-05 爸爸令：九宫格「符」键改「符/数」——点=符号键盘、长按=数字键盘（与 26 键一致）
                val symbolKey = keyMaps.first { it.pairs["click"]?.string == "Keyboard_symbols" }
                symbolKey.pairs["label"]?.string shouldBe "符/数"
                symbolKey.pairs["long_click"]?.string shouldBe "Keyboard_number"
                // 2026-11-05 爸爸反馈：从数字键盘切回九宫格后只能打一个中文就只剩数字。
                // 九宫格按键只有数字，ascii_mode 一旦残留 true 就只上屏数字 —— 必须每次显示都强制回中文。
                t9Node.pairs["reset_ascii_mode"]?.string shouldBe "true"
            }

            Then("every keyboard defines visible gaps and a key border") {
                // 爸爸 2026-11-04 反馈「所有键位的区隔界限看的并不是很明显」：
                // 1dp 间距 + 无描边在大屏手机上几乎看不出分界。
                val style = expanded.pairs!!["style"]!!.mapping!!
                val hGap = style.pairs["horizontal_gap"]?.string?.toFloat() ?: 0f
                val vGap = style.pairs["vertical_gap"]?.string?.toFloat() ?: 0f
                val keyBorder = style.pairs["key_border"]?.string?.toInt() ?: 0
                (hGap >= 3f) shouldBe true
                (vGap >= 3f) shouldBe true
                (keyBorder >= 1) shouldBe true
            }
        }
    })
