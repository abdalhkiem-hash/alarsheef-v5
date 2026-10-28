package com.alarsheef.archive.onedrive

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ملف نصي صغير مشفّر بمفتاح AES-GCM داخل Android Keystore (لا يغادر الجهاز).
 * يُحفظ في noBackupFilesDir فلا يدخل النسخ الاحتياطي السحابي، ولا يعمل خارج هذا الجهاز أصلًا.
 */
class SecureFile(context: Context, name: String) {

    private val file = File(context.noBackupFilesDir, name)

    fun read(): String? {
        if (!file.exists()) return null
        return try {
            val raw = Base64.decode(file.readText(), Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun write(text: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        file.writeText(Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP))
    }

    fun delete() {
        runCatching { file.delete() }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "arsheef_onedrive_session"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
