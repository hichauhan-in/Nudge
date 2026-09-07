package com.example

import com.example.data.BackupEncryption
import com.example.data.LocalDataTransfer
import org.junit.Assert.*
import org.junit.Test

class BackupEncryptionTest {
    @Test fun authenticatedBackupsRoundTripAndUseFreshRandomness() {
        val password = "test-only-long-passphrase".toCharArray()
        val source = "local-only snapshot".toByteArray()
        val first = BackupEncryption.encrypt(source, password)
        val second = BackupEncryption.encrypt(source, password)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(source, BackupEncryption.decrypt(first, password))
    }

    @Test(expected = javax.crypto.AEADBadTagException::class)
    fun modifiedCiphertextIsRejectedBeforeParsing() {
        val password = "test-only-long-passphrase".toCharArray()
        val bytes = BackupEncryption.encrypt("test".toByteArray(), password)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        BackupEncryption.decrypt(bytes, password)
    }

    @Test(expected = javax.crypto.AEADBadTagException::class)
    fun wrongPasswordCannotDecryptHistory() {
        val bytes = BackupEncryption.encrypt("test".toByteArray(), "test-only-long-passphrase".toCharArray())
        BackupEncryption.decrypt(bytes, "different-long-password".toCharArray())
    }

    @Test fun csvExportNeutralizesSpreadsheetFormulas() {
        assertEquals("'=HYPERLINK(1)", LocalDataTransfer.spreadsheetText("=HYPERLINK(1)"))
        assertEquals("'  +1", LocalDataTransfer.spreadsheetText("  +1"))
        assertEquals("App, with commas", LocalDataTransfer.spreadsheetText("App, with commas"))
    }
}