/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.switches

import android.app.Dialog
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.RimeConfig
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.data.theme.model.scaledCandidateBarHeight
import com.osfans.trime.ime.bar.ui.ToolButton
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dialog.EnabledSchemaPickerDialog
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.settings.ThemePickerDialog
import com.osfans.trime.util.AppUtils
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.core.add
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.recyclerview.gridLayoutManager

class SwitchOptionWindow(di: DI) :
    BoardWindow.BarBoardWindow(di),
    InputBroadcastReceiver {
    private val service: TrimeInputMethodService by instance()
    private val rime: RimeSession by instance()
    private val scope: ThemeScope by instance()
    private val windowManager: BoardWindowManager by instance()
    private val theme: Theme get() = scope.theme

    // 左上角三点菜单 = 本窗口。26/9 一键切换放这里，不占底排键盘（易误触）。
    private val staticEntries by lazy {
        arrayOf(
            SwitchOptionEntry.Static(
                "26键全键盘",
                R.drawable.ic_baseline_keyboard_24,
                SwitchOptionEntry.Static.Type.KeyboardQwerty,
            ),
            SwitchOptionEntry.Static(
                "9键九宫格",
                R.drawable.ic_baseline_phone_24,
                SwitchOptionEntry.Static.Type.KeyboardT9,
            ),
            SwitchOptionEntry.Static(
                context.getString(R.string.theme),
                R.drawable.ic_baseline_color_lens_24,
                SwitchOptionEntry.Static.Type.ThemeList,
            ),
            SwitchOptionEntry.Static(
                context.getString(R.string.schemata),
                R.drawable.ic_round_view_list_24,
                SwitchOptionEntry.Static.Type.SchemaList,
            ),
            SwitchOptionEntry.Static(
                context.getString(R.string.update_config),
                R.drawable.ic_baseline_sync_24,
                SwitchOptionEntry.Static.Type.UpdateConfig,
            ),
            SwitchOptionEntry.Static(
                context.getString(R.string.virtual_keyboard),
                R.drawable.ic_baseline_keyboard_24,
                SwitchOptionEntry.Static.Type.Keyboard,
            ),
        )
    }

    var popupMenu: PopupMenu? = null

    private val saveOptions by lazy {
        RimeConfig.openConfig("default").use {
            it.getList("switcher/save_options", RimeConfig::getString).toSet()
        }
    }

    private suspend fun RimeApi.applyOption(option: String, value: Boolean) {
        setRuntimeOption(option, value)
        if (option in saveOptions) {
            RimeConfig.openUserConfig("user").use {
                it.setBool("var/option/$option", value)
            }
        }
    }

    private fun showDialog(builder: suspend (RimeApi) -> Dialog) {
        rime.launchOnReady { api ->
            service.lifecycleScope.launch {
                service.showDialog(builder(api))
            }
        }
    }

    private fun switchToSchema(schemaId: String) {
        // selectSchema 是 suspend：launchOnReady 的 lambda 不是挂起上下文，
        // 必须在 lifecycleScope 里包一层（与 CommonKeyboardActionListener.switch_schema 同款）。
        rime.launchOnReady { api ->
            service.lifecycleScope.launch {
                api.selectSchema(schemaId)
                // ascii_mode 是全局开关，不随方案走：切入任何中文方案都强制回中文
                // （西文残留会让九宫格数字键直接上屏，不进候选）。
                api.setRuntimeOption("ascii_mode", false)
                // 切完回到键盘：KeyboardWindow.onRimeSchemaUpdated 会按方案名自动跟换键盘。
                windowManager.attachWindow(KeyboardWindow)
            }
        }
    }

    private val adapter: SwitchOptionAdapter by lazy {
        object : SwitchOptionAdapter() {
            override val scope: ThemeScope = this@SwitchOptionWindow.scope

            override fun onItemClick(
                view: View,
                entry: SwitchOptionEntry,
            ) {
                when (entry) {
                    is SwitchOptionEntry.Static -> when (entry.type) {
                        // 与 preset_keys SwitchToT9/SwitchToQwerty 同款切法：切方案 +
                        // 强制中文（ascii_mode 是全局开关）。切完直接回到键盘。
                        SwitchOptionEntry.Static.Type.KeyboardQwerty ->
                            switchToSchema("rimi")

                        SwitchOptionEntry.Static.Type.KeyboardT9 ->
                            switchToSchema("t9")

                        SwitchOptionEntry.Static.Type.SchemaList -> showDialog { r ->
                            EnabledSchemaPickerDialog.build(r, service.lifecycleScope, context) {
                                setNegativeButton(R.string.enable_schemata) { _, _ ->
                                    AppUtils.launchMainToSchemaList(context)
                                }
                            }
                        }

                        SwitchOptionEntry.Static.Type.UpdateConfig -> rime.launchOnReady { r ->
                            r.updateConfig()
                            service.lifecycleScope.launch {
                                Toast.makeText(service, R.string.done, Toast.LENGTH_SHORT).show()
                            }
                        }

                        SwitchOptionEntry.Static.Type.Keyboard -> AppUtils.launchMainToKeyboard(context)

                        SwitchOptionEntry.Static.Type.ThemeList -> showDialog { r ->
                            ThemePickerDialog.build(service.lifecycleScope, context) {
                                r.commitComposition()
                            }
                        }
                    }

                    is SwitchOptionEntry.Custom -> {
                        val options = entry.switch.options
                        if (options.isEmpty()) {
                            rime.launchOnReady {
                                val oldValue = it.getRuntimeOption(entry.switch.name)
                                it.applyOption(entry.switch.name, !oldValue)
                            }
                        } else {
                            val popup = PopupMenu(context, view)
                            val menu = popup.menu
                            entry.switch.states.forEachIndexed { i, state ->
                                menu.add(0, 0, 0, state).apply {
                                    setOnMenuItemClickListener {
                                        rime.launchOnReady {
                                            options.forEachIndexed { j, option ->
                                                it.applyOption(option, i == j)
                                            }
                                        }
                                        true
                                    }
                                }
                            }
                            popupMenu?.dismiss()
                            popupMenu = popup
                            popup.show()
                        }
                    }
                }
            }
        }
    }

    val view by lazy {
        context.recyclerView {
            layoutManager = gridLayoutManager(4)
            adapter = this@SwitchOptionWindow.adapter
        }
    }

    private fun updateSchemaOptionEntries() {
        val switches = rime.run { schemaCached }.switches
        adapter.submitList(
            listOf(
                *staticEntries,
                *switches.mapNotNull { SwitchOptionEntry.fromSwitch(rime, it) }.toTypedArray(),
            ),
        )
    }

    override fun onRimeSchemaUpdated(schema: SchemaItem) {
        updateSchemaOptionEntries()
    }

    override fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {
        updateSchemaOptionEntries()
    }

    private var viewCreated = false

    override fun onCreateView(): View {
        viewCreated = true
        return view
    }

    override fun refreshColors() {
        if (!viewCreated) return
        (view.adapter as? SwitchOptionAdapter)?.refreshColors()
        settingsButton.refreshColors()
    }

    private val settingsButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_settings_24, scope).apply {
            setOnClickListener { AppUtils.launchMainActivity(context) }
        }
    }

    private val barExternalView by lazy {
        context.constraintLayout {
            val size = dp(theme.style.scaledCandidateBarHeight)
            add(
                settingsButton,
                lParams(size, size) {
                    endOfParent()
                },
            )
        }
    }

    override fun onCreateBarView() = barExternalView

    override fun onAttached() {
        rime.launchOnReady { api ->
            val data = api.currentSchema().switches
            service.lifecycleScope.launch {
                adapter.submitList(
                    listOf(
                        *staticEntries,
                        *data.mapNotNull { SwitchOptionEntry.fromSwitch(rime, it) }.toTypedArray(),
                    ),
                )
            }
        }
    }

    override fun onDetached() {
        popupMenu?.dismiss()
        popupMenu = null
    }
}
