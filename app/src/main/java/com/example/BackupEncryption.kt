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

    /**
     * Wrap an output stream so that all written characters/bytes are encrypted on-the-fly,
     * base64-encoded, and written to the destination.
     */
    fun getEncryptingStream(outputStream: java.io.OutputStream): java.io.OutputStream {
        val cipher = getCipher(Cipher.ENCRYPT_MODE)
        val iv = cipher.iv
        
        // Wrap output in Base64 first to write the final encoded characters
        val b64Stream = android.util.Base64OutputStream(outputStream, android.util.Base64.DEFAULT)
        // Write the 16-byte initialization vector first
        b64Stream.write(iv)
        // Write the remainder through the encrypting Cipher
        return javax.crypto.CipherOutputStream(b64Stream, cipher)
    }

    /**
     * Wrap an input stream so that all read bytes are automatically base64-decoded,
     * decrypted on-the-fly, and returned.
     */
    fun getDecryptingStream(inputStream: java.io.InputStream): java.io.InputStream {
        val b64Stream = android.util.Base64InputStream(inputStream, android.util.Base64.DEFAULT)
        // Read the 16-byte initialization vector first
        val iv = ByteArray(16)
        var totalRead = 0
        while (totalRead < 16) {
            val count = b64Stream.read(iv, totalRead, 16 - totalRead)
            if (count == -1) {
                throw Exception("Stream too short, missing IV")
            }
            totalRead += count
        }
        val cipher = getCipher(Cipher.DECRYPT_MODE, IvParameterSpec(iv))
        return javax.crypto.CipherInputStream(b64Stream, cipher)
    }
    
    private fun getCipher(mode: Int, ivSpec: IvParameterSpec? = null): Cipher {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        
        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            generateKey()
        }
        
        val keyEntry = keyStore.getKey(KEYSTORE_ALIAS, null) ?: throw IllegalStateException("Keystore key $KEYSTORE_ALIAS not found")
        val key = keyEntry as? javax.crypto.SecretKey ?: throw IllegalStateException("Key is not a SecretKey: ${keyEntry.javaClass.simpleName}")
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
