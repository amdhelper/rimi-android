/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.worker

import android.content.Context
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.rimi.RimiCloud
import com.osfans.trime.data.rimi.RimiDictSync
import com.osfans.trime.ime.core.TrimeInputMethodService
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 日弥词库后台同步（M3）。
 *
 * 只做一件事：到点了把本地学到的词推上云、把别的设备的词拉下来。
 * 用户正在打字（输入法键盘可见）时直接让路，避免在引擎工作期间读写 userdb。
 */
class RimiCloudSyncWork(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        if (!RimiCloud.autoSync) return Result.success()
        if (!RimiCloud.isLoggedIn) return Result.success()
        if (TrimeInputMethodService.isInputViewShowing) {
            Timber.i("Rimi cloud sync deferred: keyboard is showing")
            return Result.retry()
        }
        val rime = RimeDaemon.createSession(javaClass.name)
        return try {
            val report = rime.runOnReady { RimiDictSync.sync(this) }
            Timber.i(
                "Rimi cloud sync done: local=%d pushed=%d pulled=%d applied=%d",
                report.localCount,
                report.pushed,
                report.pulled,
                report.applied,
            )
            Result.success()
        } catch (e: Exception) {
            Timber.w(e, "Rimi cloud sync failed")
            RimiCloud.recordError(e.message ?: e.toString())
            Result.retry()
        } finally {
            RimeDaemon.destroySession(javaClass.name)
        }
    }

    companion object {
        private const val PERIODIC_WORK_KEY = "periodic_rimi_cloud_sync"

        fun start(context: Context) {
            runCatching {
                WorkManager.initialize(context, Configuration.Builder().build())
            }
            val instance = WorkManager.getInstance(context)
            if (!RimiCloud.autoSync || !RimiCloud.isLoggedIn) {
                instance.cancelUniqueWork(PERIODIC_WORK_KEY)
                return
            }
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            val request =
                PeriodicWorkRequestBuilder<RimiCloudSyncWork>(
                    RimiCloud.syncIntervalMinutes.toLong(),
                    TimeUnit.MINUTES,
                    10,
                    TimeUnit.MINUTES,
                ).setConstraints(constraints)
                    .build()
            instance.enqueueUniquePeriodicWork(
                PERIODIC_WORK_KEY,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            Timber.i("RimiCloudSyncWork scheduled, interval=%d min", RimiCloud.syncIntervalMinutes)
        }

        fun restart(context: Context) {
            runCatching {
                WorkManager.initialize(context, Configuration.Builder().build())
            }
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_KEY)
            start(context)
        }
    }
}
