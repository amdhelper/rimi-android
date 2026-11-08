/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import android.content.res.Configuration
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.util.WeakHashSet
import com.osfans.trime.util.appContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

object ThemeManager {
    /** The theme shipped inside the APK, used when the data dirs are unreadable. */
    private const val BUNDLED_THEME_ID = "trime"

    fun interface OnThemeChangeListener {
        fun onThemeChange(theme: Theme)
    }

    fun getAllThemes(): List<ThemeItem> {
        val sharedThemes = ThemeFilesManager.listThemes(DataManager.sharedDataDir)
        val userThemes = ThemeFilesManager.listThemes(DataManager.userDataDir)
        return sharedThemes + userThemes
    }

    private lateinit var _activeTheme: Theme

    private var _activeFindings: List<ThemeDiagnostics.Finding>? = null

    /** True while [activeTheme] is the bundled stopgap instead of a real theme. */
    private var _bundledFallback = false

    /**
     * What static checks found in the active theme, or null when it was read
     * from its deployed artifact and never checked. Serves the diagnostics
     * screen: the findings belong to the load that produced the active theme.
     */
    val activeFindings: List<ThemeDiagnostics.Finding>?
        get() {
            ensureActiveTheme()
            return _activeFindings
        }

    private fun ensureActiveTheme() {
        if (!::_activeTheme.isInitialized) {
            _activeTheme = evaluateActiveTheme()
        }
    }

    var activeTheme: Theme
        get() {
            ensureActiveTheme()
            return _activeTheme
        }
        private set(value) {
            if (::_activeTheme.isInitialized && _activeTheme == value) return
            _activeTheme = value
            fireChange()
        }

    private val onChangeListeners = WeakHashSet<OnThemeChangeListener>()

    fun addOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.add(listener)
    }

    fun removeOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.remove(listener)
    }

    private fun fireChange() {
        onChangeListeners.forEach { it.onThemeChange(_activeTheme) }
    }

    val prefs = AppPrefs.defaultInstance().registerProvider(::ThemePrefs)

    private data class ResolvedTheme(
        val configId: String,
        val theme: Theme,
        val findings: List<ThemeDiagnostics.Finding>?,
        /** True when this theme came from the APK assets because nothing else could be read. */
        val bundled: Boolean = false,
    )

    private fun getThemeById(id: String): ResolvedTheme {
        val failures = mutableListOf<String>()
        fun note(error: ThemeLoader.ThemeLoadError): ThemeLoader.ThemeLoadError {
            Timber.w(error)
            failures += error.message.orEmpty()
            return error
        }

        when (val result = ThemeLoader.loadTheme(id)) {
            is ThemeLoader.ThemeLoadResult.Success -> return ResolvedTheme(id, result.theme, result.findings)
            is ThemeLoader.ThemeLoadResult.Failure -> note(result.error)
        }

        if (id != BUNDLED_THEME_ID) {
            when (val result = ThemeLoader.loadTheme(BUNDLED_THEME_ID)) {
                is ThemeLoader.ThemeLoadResult.Success -> {
                    Timber.w("Theme '$id' is unavailable, fallback to default theme '$BUNDLED_THEME_ID'")
                    return ResolvedTheme(BUNDLED_THEME_ID, result.theme, result.findings)
                }

                is ThemeLoader.ThemeLoadResult.Failure -> note(result.error)
            }
        }

        var lastFailure: ThemeLoader.ThemeLoadError? = null
        for (fallbackId in getAllThemes().map { it.configId }.distinct()) {
            when (val result = ThemeLoader.loadTheme(fallbackId)) {
                is ThemeLoader.ThemeLoadResult.Success -> {
                    Timber.w("Theme '$id' is unavailable, fallback to available theme '$fallbackId'")
                    return ResolvedTheme(fallbackId, result.theme, result.findings)
                }

                is ThemeLoader.ThemeLoadResult.Failure -> lastFailure = note(result.error)
            }
        }

        // Last resort: the copy shipped inside the APK. Reading it never depends on
        // external storage, which is exactly what is missing when the input method
        // service starts in direct boot mode before the user unlocks the device.
        // Without this the whole process used to die on "No valid theme available",
        // leaving a crash log as the first screen after a reboot.
        when (val result = ThemeLoader.loadBundledTheme(BUNDLED_THEME_ID)) {
            is ThemeLoader.ThemeLoadResult.Success -> {
                Timber.w("Theme '$id' and every user theme are unavailable, using the bundled theme")
                recordFallback(failures)
                return ResolvedTheme(BUNDLED_THEME_ID, result.theme, result.findings, bundled = true)
            }

            is ThemeLoader.ThemeLoadResult.Failure -> lastFailure = note(result.error)
        }

        Timber.e(lastFailure, "No valid theme available")
        error("No valid theme available")
    }

    /**
     * Leaves a trace of why the bundled theme was needed. The reason used to be lost
     * with the log buffer before anyone could look at it, which made this failure
     * impossible to diagnose after a reboot; the file is world-readable through adb.
     */
    private fun recordFallback(failures: List<String>) {
        runCatching {
            val dir = appContext.getExternalFilesDir(null) ?: return@runCatching
            File(dir, "theme_fallback.log").appendText(
                "${System.currentTimeMillis()} ${failures.joinToString(" | ")}\n",
            )
        }
    }

    private fun evaluateActiveTheme(): Theme {
        val selectedThemeId = prefs.selectedTheme.getValue()
        val resolvedTheme = getThemeById(selectedThemeId)
        val newTheme = resolvedTheme.theme
        // A bundled fallback is a stopgap, not the user's choice: remembering it
        // would silently rewrite the selected theme on every boot.
        if (!resolvedTheme.bundled && resolvedTheme.configId != selectedThemeId) {
            prefs.selectedTheme.setValue(resolvedTheme.configId)
        }
        applyTheme(resolvedTheme)
        return newTheme
    }

    private fun applyTheme(resolvedTheme: ResolvedTheme) {
        val theme = resolvedTheme.theme
        // The findings describe the file this load read; they are not tied to the
        // views, so they are refreshed even when the theme itself is unchanged.
        _activeFindings = resolvedTheme.findings
        _bundledFallback = resolvedTheme.bundled
        // A structurally equal theme suppresses the change notification below, so the
        // UI tree keeps its views and their injected scope. Replace neither the
        // caches nor the scope in that case, or later scheme changes would update
        // the new global scope while existing views still read the old one.
        if (::_activeTheme.isInitialized && _activeTheme == theme) return
        ColorManager.attachTheme(theme)
        activeTheme = theme
    }

    fun init(configuration: Configuration) {
        if (_bundledFallback) {
            // The previous load ran before the data dirs were readable (direct boot or
            // storage still mounting). Now that the service is (re)initialized, try the
            // real theme again instead of keeping the stopgap for the whole session.
            Timber.w("Reloading theme: the previous load fell back to the bundled theme")
            _activeTheme = evaluateActiveTheme()
        }
        ensureActiveTheme()
        ColorManager.init(configuration)
    }

    /**
     * Switches to theme [configId], falling back when it is unavailable.
     * Loading runs on [Dispatchers.IO]; state changes and listener callbacks run on the main thread.
     * @return the config id actually in effect; differs from [configId] when a fallback was used.
     */
    suspend fun selectTheme(configId: String): String {
        val resolvedTheme = withContext(Dispatchers.IO) { getThemeById(configId) }
        return withContext(Dispatchers.Main.immediate) {
            applyTheme(resolvedTheme)
            if (!resolvedTheme.bundled) {
                prefs.selectedTheme.setValue(resolvedTheme.configId)
            }
            resolvedTheme.configId
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun syncToDeviceEncryptedStorage() {
        val ctx = appContext.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            prefs.preferenceDelegates.forEach {
                it.value.putValueTo(this@edit)
            }
        }
    }
}
