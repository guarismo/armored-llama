package com.iguar.armoredllama.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Foreground service that owns the llama-server process and streams its output to [LogBus]. */
class LlamaServerService : Service() {

    enum class Status { STOPPED, STARTING, RUNNING, ERROR }

    @Volatile private var process: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopServer(); stopSelfResult(startId) }
            else -> startServer(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun startServer(startId: Int) {
        if (process != null) return
        ensureChannel()
        startForegroundCompat("Starting llama-server…")
        statusFlow.value = Status.STARTING

        val repo = ConfigRepository(this)
        LogBus.attachFile(repo.logFile())
        val config = repo.load()
        val modelsDir = repo.modelsDir()
        val runtime = RuntimeBinaries(filesDir, File(applicationInfo.nativeLibraryDir))
        val onDownloadedRuntime = runtime.hasDownloadedActive()
        val target = runtime.activeExecutable()
        val binary = File(target.execPath)

        if (!binary.exists()) {
            fail("server binary missing (${binary.path}) — see jniLibs/arm64-v8a/README.md")
            return
        }
        val needed = listOf(config.modelFile, config.draftFile, config.mmprojFile).filter { it.isNotBlank() }
        val missing = needed.filter { !File(modelsDir, it).exists() }
        if (missing.isNotEmpty()) {
            fail("missing model file(s): ${missing.joinToString()} — download them first")
            return
        }

        val args = buildArgs(config, binary.path, modelsDir.path)
        LogBus.append("exec: ${args.joinToString(" ")}")
        try {
            val pb = ProcessBuilder(args).redirectErrorStream(true).directory(modelsDir)
            pb.environment()["LD_LIBRARY_PATH"] = target.libDir
            val startedAt = System.currentTimeMillis()
            val p = pb.start()
            process = p
            statusFlow.value = Status.RUNNING
            startForegroundCompat("Running on ${config.host}:${config.port}")
            Thread({
                runCatching {
                    p.inputStream.bufferedReader().forEachLine { LogBus.append(it) }
                }
                val code = runCatching { p.waitFor() }.getOrDefault(-1)
                LogBus.append("server exited (code $code)")
                // A downloaded runtime that dies almost immediately with a non-zero code is
                // corrupt/incompatible: drop it so the next Start falls back to bundled.
                val ranBriefly = System.currentTimeMillis() - startedAt < EARLY_FAIL_MS
                if (onDownloadedRuntime && code != 0 && ranBriefly && runtime.invalidateActive()) {
                    LogBus.append(
                        "downloaded runtime failed to start — reverting to bundled " +
                            "${RuntimeBinaries.BUNDLED_TAG}; press Start again",
                    )
                }
                process = null
                statusFlow.value = Status.STOPPED
                stopSelfResult(startId)
            }, "llama-server-reader").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            fail("failed to start: ${e.message}")
        }
    }

    private fun fail(message: String) {
        LogBus.append("error: $message")
        statusFlow.value = Status.ERROR
        process = null
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopServer() {
        process?.let { p ->
            p.destroy()
            runCatching { if (!p.waitForGrace()) p.destroyForcibly() }
        }
        process = null
        statusFlow.value = Status.STOPPED
        stopForegroundCompat()
    }

    private fun Process.waitForGrace(): Boolean =
        runCatching { waitFor(3, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)

    private fun startForegroundCompat(text: String) {
        // targetSdk 28: FGS types are not enforced, so the plain 2-arg form is correct.
        startForeground(NOTIF_ID, buildNotification(text))
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Armored Llama")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "llama-server", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "llama_server"
        private const val NOTIF_ID = 1001
        // A downloaded binary that exits sooner than this is treated as failed-to-launch.
        private const val EARLY_FAIL_MS = 8_000L
        private const val ACTION_START = "com.iguar.armoredllama.START"
        private const val ACTION_STOP = "com.iguar.armoredllama.STOP"

        private val statusFlow = MutableStateFlow(Status.STOPPED)
        val status: StateFlow<Status> = statusFlow

        fun start(context: Context) {
            val i = Intent(context, LlamaServerService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            val i = Intent(context, LlamaServerService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(i) }
        }
    }
}
