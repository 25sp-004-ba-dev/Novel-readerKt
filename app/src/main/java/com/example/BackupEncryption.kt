package com.example

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.IvParameterSpec
import java.security.KeyStore
import android.util.Base64

object BackupEncryption {
    
    private const val KEYSTORE_ALIAS = "wtr_backup_key"
    private const val CIPHER_TRANSFORMATION = "AES/CBC/PKCS7Padding"
    
    fun encryptBackup(plaintext: String): String {
        return try {
            val cipher = getCipher(Cipher.ENCRYPT_MODE)
            val encryptedBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            val combined = iv + encryptedBytes
            Base64.encodeToString(combined, Base64.DEFAULT)
        } catch (e: Exception) {
            throw RuntimeException("Encryption failed: ${e.message}", e)
        }
    }
    
    fun decryptBackup(ciphertext: String): String {
        return try {
            val combined = Base64.decode(ciphertext, Base64.DEFAULT)
            if (combined.size < 16) {
                throw Exception("Ciphertext too short, missing IV or payload")
            }
            val iv = combined.sliceArray(0 until 16)
            val encrypted = combined.sliceArray(16 until combined.size)
            
            val cipher = getCipher(Cipher.DECRYPT_MODE, IvParameterSpec(iv))
            val decrypted = cipher.doFinal(encrypted)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            throw RuntimeException("Decryption failed: ${e.message}", e)
        }
    }
    
    private fun getCipher(mode: Int, ivSpec: IvParameterSpec? = null): Cipher {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        
        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            generateKey()
        }
        
        val key = keyStore.getKey(KEYSTORE_ALIAS, null) as javax.crypto.SecretKey
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        
        return cipher.apply {
            if (ivSpec != null) {
                init(mode, key, ivSpec)
            } else {
                init(mode, key)
            }
        }
    }
    
    private fun generateKey() {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).apply {
            setBlockModes(KeyProperties.BLOCK_MODE_CBC)
            setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
        }.build()
        
        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }
}
