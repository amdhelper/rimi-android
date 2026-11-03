/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.rimi

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 账户 seed 落盘加密。
 *
 * Android 6.0+（API 23）走 AndroidKeyStore 的 AES/GCM，密钥不可导出；
 * 5.x 没有 Keystore 对称密钥，只能 base64 存（极老设备，明确降级）。
 */
object RimiSeedVault {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "rimi_cloud_seed_v1"
    private const val PREFIX_GCM = "gcm:"
    private const val PREFIX_PLAIN = "plain:"

    fun encrypt(plain: ByteArray): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return PREFIX_PLAIN + Base64.encodeToString(plain, Base64.NO_WRAP)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val cipherText = cipher.doFinal(plain)
        val packed = ByteArray(cipher.iv.size + cipherText.size)
        cipher.iv.copyInto(packed)
        cipherText.copyInto(packed, cipher.iv.size)
        return PREFIX_GCM + Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): ByteArray? = runCatching {
        when {
            stored.startsWith(PREFIX_PLAIN) ->
                Base64.decode(stored.removePrefix(PREFIX_PLAIN), Base64.NO_WRAP)

            stored.startsWith(PREFIX_GCM) -> {
                val packed = Base64.decode(stored.removePrefix(PREFIX_GCM), Base64.NO_WRAP)
                require(packed.size > 12) { "seed 密文损坏" }
                val iv = packed.copyOfRange(0, 12)
                val body = packed.copyOfRange(12, packed.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
                cipher.doFinal(body)
            }

            else -> Base64.decode(stored, Base64.NO_WRAP)
        }
    }.getOrNull()

    fun clear() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
            }
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
