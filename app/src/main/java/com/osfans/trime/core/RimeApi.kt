/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core

import kotlinx.coroutines.flow.SharedFlow

interface RimeApi {
    val messageFlow: SharedFlow<RimeMessage<*>>

    val isReady: Boolean

    val schemaCached: RimeSchema

    val statusCached: StatusProto

    val compositionCached: CompositionProto

    val hasMenu: Boolean

    val paging: Boolean

    suspend fun isEmpty(): Boolean

    suspend fun deploy()

    suspend fun updateConfig()

    /**
     * 临时释放 RIME 引擎（leveldb 的独占锁随之释放），执行 [block] 后重新启动引擎。
     *
     * 同步写回 userdb 时用：引擎运行期间 userdb 被 flock 独占，同进程 JNI 也打不开；
     * 只在键盘不可见 / 熄屏的同步路径调用，重启不会打断输入。
     */
    suspend fun <T> withEngineReleased(block: suspend () -> T): T

    suspend fun syncUserData(): Boolean

    suspend fun processKey(
        value: Int,
        modifiers: UInt = 0u,
        isVirtual: Boolean = true,
    ): Boolean

    suspend fun processKey(
        value: KeyValue,
        modifiers: KeyModifiers,
        isVirtual: Boolean = true,
    ): Boolean

    suspend fun simulateKeySequence(
        sequence: String,
    ): Boolean

    suspend fun selectCandidate(idx: Int, global: Boolean): Boolean

    suspend fun deleteCandidate(idx: Int, global: Boolean): Boolean

    suspend fun changeCandidatePage(backward: Boolean): Boolean

    suspend fun moveCursorPos(position: Int)

    suspend fun availableSchemata(): Array<SchemaItem>

    suspend fun enabledSchemata(): Array<SchemaItem>

    suspend fun setEnabledSchemata(schemaIds: Array<String>): Boolean

    suspend fun selectedSchemata(): Array<SchemaItem>

    suspend fun selectedSchemaId(): String

    suspend fun selectSchema(schemaId: String): Boolean

    suspend fun currentSchema(): RimeSchema

    suspend fun commitComposition(): Boolean

    suspend fun clearComposition()

    suspend fun getRawInput(): String

    suspend fun setRuntimeOption(
        option: String,
        value: Boolean,
    )

    suspend fun getRuntimeOption(option: String): Boolean

    suspend fun setNullInputType(value: Boolean)

    suspend fun getCandidates(
        startIndex: Int,
        limit: Int,
    ): Array<CandidateProto>

    suspend fun setCandidatePagingMode(enabled: Boolean)
}
