/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.worker

import android.content.Context
import android.os.PowerManager
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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
 *
 * 跑之前有两道闸：
 * 1. 默认只在「屏幕熄灭」时跑（[RimiCloud.syncOnlyWhenScreenOff]）：亮屏时用户随时可能打字，
 *    同步要读写 userdb、写回后还会 `updateConfig()` 重启引擎，绝不能和输入抢资源。
 *    熄屏后由 TrimeApplication 的 ACTION_SCREEN_OFF 接收器立即补一次同步。
 * 2. 键盘可见时让路（与熄屏闸互补，覆盖手动亮屏同步等边界）。
 */
class RimiCloudSyncWork(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        if (!RimiCloud.autoSync) return Result.success()
        if (!RimiCloud.isLoggedIn) return Result.success()
        if (RimiCloud.syncOnlyWhenScreenOff && isScreenOn(applicationContext)) {
            Timber.i("Rimi cloud sync skipped: screen is on")
            return Result.success()
        }
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
        private const val SCREEN_OFF_WORK_KEY = "rimi_cloud_sync_on_screen_off"

        /**
         * ACTION_SCREEN_OFF 广播比 PowerManager 状态落定早 100ms 量级（2026-11-04 真机实测：
         * 广播到达 131ms 后 `isInteractive` 仍为 true）。延后 10s 再跑，让「屏幕已熄灭」
         * 成为稳定事实；用户若在这 10s 内又点亮屏幕，任务会按闸门跳过，等下次熄屏。
         */
        private const val SCREEN_OFF_SETTLE_SECONDS = 10L

        private fun isScreenOn(context: Context): Boolean {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
            return power.isInteractive
        }

        /**
         * 熄屏时补一次同步。
         *
         * WorkManager 没有「屏幕熄灭」这种 constraint，所以由 TrimeApplication 动态注册的
         * ACTION_SCREEN_OFF 接收器调用这里，把一次性任务塞进队列；重复熄屏用 KEEP 去重。
         * 只有「自动同步 + 已登录 + 仅熄屏同步」三项都满足才排队。
         */
        fun onScreenOff(context: Context) {
            if (!RimiCloud.autoSync || !RimiCloud.isLoggedIn) return
            if (!RimiCloud.syncOnlyWhenScreenOff) return
            runCatching {
                WorkManager.initialize(context, Configuration.Builder().build())
            }
            val request =
                OneTimeWorkRequestBuilder<RimiCloudSyncWork>()
                    .setInitialDelay(SCREEN_OFF_SETTLE_SECONDS, TimeUnit.SECONDS)
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    ).build()
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(SCREEN_OFF_WORK_KEY, ExistingWorkPolicy.KEEP, request)
            Timber.i("Rimi cloud sync enqueued on screen off")
        }

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
