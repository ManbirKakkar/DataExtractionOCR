package com.manbir.dataextractor

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogUtils {
    private const val TAG = "DataExtractor"
    private const val LOG_DIR = "DataExtractorLogs"

    fun logDebug(message: String) {
        Log.d(TAG, message)
    }

    fun logError(message: String) {
        Log.e(TAG, message)
    }

    fun saveLogToFile(context: Context, filename: String, content: String): List<String> {
        val savedPaths = mutableListOf<String>()

        try {
            // Save to app-specific storage (always works)
            val appDir = File(context.getExternalFilesDir(null), LOG_DIR)
            if (!appDir.exists()) appDir.mkdirs()
            val appFile = File(appDir, filename)
            appFile.writeText(content)
            savedPaths.add(appFile.absolutePath)
            logDebug("Saved log to app storage: ${appFile.absolutePath}")

            // Try to save to Downloads folder (requires permission)
            if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val publicDir = File(downloadsDir, LOG_DIR)
                if (!publicDir.exists()) publicDir.mkdirs()
                val publicFile = File(publicDir, filename)
                publicFile.writeText(content)
                savedPaths.add(publicFile.absolutePath)
                logDebug("Saved log to Downloads: ${publicFile.absolutePath}")
            }
        } catch (e: Exception) {
            logError("Failed to save log: ${e.message}")
        }

        return savedPaths
    }

    fun getTimestamp(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date())
    }
}