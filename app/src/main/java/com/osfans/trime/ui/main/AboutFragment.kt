/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import com.osfans.trime.BuildConfig
import com.osfans.trime.R
import com.osfans.trime.data.update.AppUpdateChecker
import com.osfans.trime.data.update.AppUpdateInfo
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.util.Const
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.formatDateTime
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class AboutFragment : PaddingPreferenceFragment() {

    @SuppressLint("UseKtx")
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(
                R.string.check_update,
                getString(R.string.check_update_summary, Const.VERSION_NAME),
            ) { checkForUpdate() }
            addPreference(R.string.current_version, Const.VERSION_NAME) {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("${BuildConfig.BUILD_GIT_REPO}/commit/${BuildConfig.BUILD_COMMIT_HASH}"),
                    ),
                )
            }
            addPreference(R.string.librime_version, BuildConfig.LIBRIME_VERSION) {
                val hash = getCommitFromVersionName(BuildConfig.LIBRIME_VERSION)
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("${Const.LIBRIME_URL}/commit/$hash"),
                    ),
                )
            }
            addPreference(R.string.opencc_version, BuildConfig.OPENCC_VERSION) {
                val hash = getCommitFromVersionName(BuildConfig.OPENCC_VERSION)
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("${Const.OPENCC_URL}/commit/$hash"),
                    ),
                )
            }
            addPreference(
                Preference(requireContext()).apply {
                    isIconSpaceReserved = false
                    isCopyingEnabled = true
                    setTitle(R.string.build_info)
                    summary = requireContext().getString(
                        R.string.build_info_format,
                        BuildConfig.BUILDER,
                        BuildConfig.BUILD_COMMIT_HASH,
                        formatDateTime(BuildConfig.BUILD_TIMESTAMP),
                    )
                },
            )
            addCategory("") {
                isIconSpaceReserved = false
                addPreference(R.string.privacy_policy) {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(Const.PRIVACY_POLICY_URL),
                        ),
                    )
                }
                addPreference(R.string.source_code, R.string.git_repo) {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(BuildConfig.BUILD_GIT_REPO),
                        ),
                    )
                }
                addPreference(R.string.license, Const.LICENSE_SPDX_ID) {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(Const.LICENSE_URL),
                        ),
                    )
                }
                addPreference(
                    R.string.open_source_licenses,
                    R.string.licenses_of_third_party_libraries,
                ) {
                    findNavController().navigate(NavigationRoute.License)
                }
            }
        }
    }

    // ---------------- 应用内自更新 ----------------

    private var pendingDownloadId: Long = -1L

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != pendingDownloadId) return
            pendingDownloadId = -1L
            installDownloadedApk(id)
        }
    }

    override fun onStart() {
        super.onStart()
        val ctx = context ?: return
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(downloadReceiver, filter)
        }
    }

    override fun onStop() {
        try {
            context?.unregisterReceiver(downloadReceiver)
        } catch (_: IllegalArgumentException) {
            // 未注册（提前退出）时忽略
        }
        super.onStop()
    }

    private fun checkForUpdate() {
        val ctx = context ?: return
        Toast.makeText(ctx, R.string.check_update_checking, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val info = AppUpdateChecker.check(BuildConfig.VERSION_NAME)
                val c = context ?: return@launch
                if (!info.hasUpdate) {
                    Toast.makeText(
                        c,
                        getString(
                            R.string.check_update_latest,
                            info.latestVersion.ifBlank { Const.VERSION_NAME },
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                val size =
                    if (info.size > 0) {
                        String.format(Locale.US, "%.1f MB", info.size / 1048576.0)
                    } else {
                        "—"
                    }
                androidx.appcompat.app.AlertDialog.Builder(c)
                    .setTitle(R.string.update_available_title)
                    .setMessage(
                        getString(
                            R.string.update_available_message,
                            Const.VERSION_NAME,
                            info.latestVersion,
                            size,
                            info.notes.trim(),
                        ),
                    )
                    .setPositiveButton(R.string.update_now) { _, _ -> startDownload(info) }
                    .setNegativeButton(R.string.update_later, null)
                    .show()
            } catch (e: Exception) {
                context?.let {
                    Toast.makeText(
                        it,
                        getString(R.string.check_update_failed, e.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun startDownload(info: AppUpdateInfo) {
        val ctx = context ?: return
        try {
            pendingDownloadId = AppUpdateChecker.enqueueDownload(ctx, info)
            Toast.makeText(ctx, R.string.update_download_started, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(
                ctx,
                getString(R.string.update_download_failed, e.message ?: ""),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun installDownloadedApk(downloadId: Long) {
        val ctx = context ?: return
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId)) ?: return
        try {
            if (!cursor.moveToFirst()) return
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.update_download_failed, ""),
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
            val localUri = cursor.getString(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI),
            ) ?: return
            val path = Uri.parse(localUri).path ?: return
            val file = File(path)
            if (!file.exists()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !ctx.packageManager.canRequestPackageInstalls()
            ) {
                Toast.makeText(ctx, R.string.update_install_permission, Toast.LENGTH_LONG).show()
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${ctx.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return
            }
            val apkUri = FileProvider.getUriForFile(
                ctx,
                "${ctx.packageName}.fileprovider",
                file,
            )
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } finally {
            cursor.close()
        }
    }

    companion object {
        private val DASH_G_PATTERN = Regex("^(.*-g)([0-9a-f]+)(.*)$")
        private val COMMON_PATTERN = Regex("^([^-]*)(-.*)$")

        private fun getCommitFromVersionName(versionCode: String): String {
            val dashG = DASH_G_PATTERN.find(versionCode)?.groupValues?.get(2)
            val common = COMMON_PATTERN.find(versionCode)?.groupValues?.get(1)
            return dashG ?: common ?: versionCode
        }
    }
}
