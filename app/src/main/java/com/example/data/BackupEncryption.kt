package com.example.data

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupEncryption {
    const val MAX_BYTES = 32 * 1024 * 1024
    private const val ITERATIONS = 600_000
    private val magic = "NUDGEBK1".toByteArray(Charsets.US_ASCII)
    private const val HEADER_BYTES = 40

    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        require(plain.size <= MAX_BYTES && password.size in 12..1024)
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES).put(magic).putInt(ITERATIONS).put(salt).put(nonce).array()
        val cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, nonce)
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    fun decrypt(encrypted: ByteArray, password: CharArray): ByteArray {
        require(encrypted.size in (HEADER_BYTES + 16)..(MAX_BYTES + HEADER_BYTES + 16))
        require(password.size in 12..1024)
        val header = encrypted.copyOfRange(0, HEADER_BYTES)
        val input = ByteBuffer.wrap(header)
        require(ByteArray(magic.size).also(input::get).contentEquals(magic))
        require(input.int == ITERATIONS)
        val salt = ByteArray(16).also(input::get)
        val nonce = ByteArray(12).also(input::get)
        val cipher = cipher(Cipher.DECRYPT_MODE, password, salt, nonce)
        cipher.updateAAD(header)
        return cipher.doFinal(encrypted, HEADER_BYTES, encrypted.size - HEADER_BYTES)
    }

    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, nonce: ByteArray): Cipher {
        val specification = PBEKeySpec(password, salt, ITERATIONS, 256)
        val keyBytes = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).encoded }
        finally { specification.clearPassword() }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce)) }
        } finally { keyBytes.fill(0) }
    }
}