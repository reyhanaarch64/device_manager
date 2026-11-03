package com.devicemanager.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.rhythmcache.adb.AdbAuth
import io.github.rhythmcache.adb.AdbKeyProvider
import io.github.rhythmcache.adb.MemoryKeyProvider
import java.io.File
import java.io.FileOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyStore
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the ADB RSA identity stable while encrypting the private-key bytes with
 * an AES-GCM key managed by Android Keystore. The RSA private key is never
 * stored as plaintext on disk and the AES key is not exportable.
 */
class AndroidKeystoreAdbKeyProvider(context: Context) : AdbKeyProvider {
    private val appContext = context.applicationContext
    private val keyFile = File(File(appContext.noBackupFilesDir, "adb"), "identity.enc")
    @Volatile private var cachedPair: KeyPair? = null

    override suspend fun getKeyPair(): KeyPair {
        cachedPair?.let { return it }
        synchronized(this) {
            cachedPair?.let { return it }
            val pair = if (keyFile.exists()) decryptKeyPair(keyFile.readBytes()) else {
                val generated = AdbAuth.generateKey()
                persistKeyPair(generated)
                generated
            }
            cachedPair = pair
            return pair
        }
    }

    override suspend fun getAdbPublicKeyBytes(): ByteArray? =
        MemoryKeyProvider(getKeyPair(), "device-manager@android").getAdbPublicKeyBytes()

    fun clear() {
        synchronized(this) {
            cachedPair = null
            keyFile.delete()
            File(keyFile.parentFile, "identity.pub").delete()
            runCatching {
                val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
            }
        }
    }

    private fun persistKeyPair(pair: KeyPair) {
        val privateBytes = pair.private.encoded
            ?: throw IllegalStateException("provider RSA tidak menghasilkan PKCS#8 encoded key")
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAesKey())
            val encrypted = cipher.doFinal(privateBytes)
            val iv = cipher.iv
            require(iv.size in 12..16) { "iv AES-GCM tidak valid" }
            keyFile.parentFile?.mkdirs()
            val temp = File(keyFile.parentFile, "${keyFile.name}.tmp")
            FileOutputStream(temp).use { stream ->
                stream.write(byteArrayOf(iv.size.toByte()))
                stream.write(iv)
                stream.write(encrypted)
                stream.fd.sync()
            }
            if (keyFile.exists() && !keyFile.delete()) throw IllegalStateException("gagal mengganti file kredensial")
            if (!temp.renameTo(keyFile)) {
                temp.delete()
                throw IllegalStateException("gagal menyimpan kredensial terenkripsi")
            }
            val publicFile = File(keyFile.parentFile, "identity.pub")
            val publicTemp = File(keyFile.parentFile, "identity.pub.tmp")
            FileOutputStream(publicTemp).use { stream ->
                stream.write(pair.public.encoded)
                stream.fd.sync()
            }
            if (publicFile.exists()) publicFile.delete()
            if (!publicTemp.renameTo(publicFile)) {
                publicTemp.delete()
                keyFile.delete()
                throw IllegalStateException("gagal menyimpan public key ADB")
            }
        } finally {
            privateBytes.fill(0)
        }
    }

    private fun decryptKeyPair(blob: ByteArray): KeyPair {
        require(blob.isNotEmpty()) { "file kredensial kosong; reset pairing untuk membuat kunci baru" }
        val ivLength = blob[0].toInt() and 0xff
        require(ivLength in 12..16 && blob.size > 1 + ivLength) {
            "format kredensial tidak valid; reset pairing lalu pair ulang"
        }
        val key = getExistingAesKey()
            ?: throw IllegalStateException("kunci Android Keystore tidak ditemukan; reset kredensial pairing lalu pair ulang")
        val iv = blob.copyOfRange(1, 1 + ivLength)
        val encrypted = blob.copyOfRange(1 + ivLength, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val privateBytes = cipher.doFinal(encrypted)
        try {
            val factory = KeyFactory.getInstance("RSA")
            val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes))
            val publicFile = File(keyFile.parentFile, "identity.pub")
            val publicBytes = if (publicFile.exists()) publicFile.readBytes() else {
                // Recovering the public half from a private RSA key is not portable;
                // the public key is stored alongside the encrypted identity on first creation.
                throw IllegalStateException("public key pendamping hilang; reset kredensial pairing lalu pair ulang")
            }
            val publicKey = factory.generatePublic(X509EncodedKeySpec(publicBytes))
            return KeyPair(publicKey, privateKey)
        } finally {
            privateBytes.fill(0)
            encrypted.fill(0)
            iv.fill(0)
        }
    }

    private fun getExistingAesKey(): SecretKey? {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (store.getKey(KEY_ALIAS, null) as? SecretKey)
    }

    private fun getOrCreateAesKey(): SecretKey {
        getExistingAesKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "device_manager_adb_aes_gcm"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
