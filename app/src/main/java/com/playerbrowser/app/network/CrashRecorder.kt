package com.playerbrowser.app.network

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures uncaught exceptions on any thread and persists the stack trace to
 * `filesDir/crashes/` so it survives process death. After writing the file
 * the previous default handler is invoked, which usually means the platform
 * shows the standard "app stopped" dialog and kills the process — exactly
 * what we want, just with a forensic trail.
 *
 * Files are pruned to MAX_FILES newest entries to keep storage bounded over
 * long install lifetimes.
 */
object CrashRecorder {
    private const val DIR_NAME = "crashes"
    private const val MAX_FILES = 20

    fun install(context: Context) {
        val dir = ensureDir(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrash(dir, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun list(context: Context): List<File> {
        val dir = File(context.applicationContext.filesDir, DIR_NAME)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun read(file: File): String = runCatching { file.readText() }.getOrElse { "" }

    /**
     * Records a non-fatal event (e.g. WebView render-process death) using the
     * same on-disk format as real crashes so they show up in the debug screen.
     */
    fun record(context: Context, label: String, detail: String) {
        val dir = ensureDir(context)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val file = File(dir, "event-$stamp.txt")
        runCatching {
            file.writeText(
                "Time: ${Date()}\nLabel: $label\n\n$detail\n"
            )
            prune(dir)
        }
    }

    fun clear(context: Context) {
        val dir = File(context.applicationContext.filesDir, DIR_NAME)
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun ensureDir(context: Context): File =
        File(context.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }

    private fun writeCrash(dir: File, thread: Thread, throwable: Throwable) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val file = File(dir, "crash-$stamp.txt")
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("Time: ${Date()}")
            pw.println("Thread: ${thread.name} (id=${thread.id})")
            pw.println("Type: ${throwable.javaClass.name}")
            pw.println("Message: ${throwable.message}")
            pw.println()
            throwable.printStackTrace(pw)
        }
        file.writeText(sw.toString())
        prune(dir)
    }

    private fun prune(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_FILES).forEach { it.delete() }
    }
}
