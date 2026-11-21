package com.example.opencodemobile

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.SecureRandom

object SecretStore {
    @Synchronized
    fun getOrCreate(context: Context): String {
        val file = File(context.filesDir, "server-password")
        if (file.exists()) return file.readText().trim()
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val value = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        file.writeText(value)
        return value
    }
}
