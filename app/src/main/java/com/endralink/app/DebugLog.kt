package com.endralink.app

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Private, bounded diagnostic text log. Recording and export never send data over the network. */
object DebugLog {
    private const val LIMIT = 256 * 1024L
    private val writer = Executors.newSingleThreadExecutor()
    private val dropped = AtomicInteger()
    @Volatile private var directory: File? = null

    /** Attach application storage and record the environment at each activity creation. */
    fun start(context: Context) {
        directory = File(context.applicationContext.filesDir, "diagnostics")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        event("SESSION_START", "app=" + info.versionName + " android=" + Build.VERSION.RELEASE +
            " sdk=" + Build.VERSION.SDK_INT + " manufacturer=" + Build.MANUFACTURER +
            " model=" + Build.MODEL)
    }

    /** Queue a single timestamped event; disk failures must never interrupt USB work. */
    fun event(name: String, message: String = "", error: Throwable? = null) {
        val dir = directory ?: return
        val detail = (message + if (error != null) " | " + error.stackTraceToString() else "")
            .replace("\r", "\\r").replace("\n", "\\n").take(12000)
        val line = Instant.now().toString() + " uptimeMs=" + SystemClock.elapsedRealtime() +
            " [" + name + "] " + detail + "\n"
        writer.execute {
            try {
                if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create diagnostic folder")
                val current = File(dir, "current.txt")
                val previous = File(dir, "previous.txt")
                if (current.length() + line.toByteArray(Charsets.UTF_8).size > LIMIT) {
                    if (previous.exists() && !previous.delete()) throw IOException("Cannot rotate previous log")
                    if (!current.renameTo(previous)) throw IOException("Cannot rotate current log")
                }
                current.appendText(line, Charsets.UTF_8)
            } catch (_: Exception) {
                dropped.incrementAndGet()
            }
        }
    }

    /** Call off the UI thread. Queuing behind writes includes all events before the export. */
    fun snapshot(): String = writer.submit<String> {
        val dir = directory ?: throw IOException("Diagnostic logging is not initialized")
        val chunks = listOf("previous.txt", "current.txt").mapNotNull {
            File(dir, it).takeIf { file -> file.exists() }?.readText(Charsets.UTF_8)
        }
        if (chunks.isEmpty()) throw IOException("No diagnostic log is available")
        "EndraLink diagnostic log\nExported UTC: " + Instant.now() +
            "\nLog write failures: " + dropped.get() +
            "\nFile contents, document URIs and device serial numbers are not recorded.\n\n" +
            chunks.joinToString("")
    }.get()
}
