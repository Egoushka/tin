package io.github.egoushka.tin

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Runs inside the process Shizuku starts as the shell user (uid 2000). Copies the tin binary out of the APK to
 * /data/local/tmp/tin/bin/tin, starts `tin serve`, and starts it again whenever it exits.
 */
class TinService(private val context: Context) : ITinService.Stub() {
    @Volatile private var stopped = false
    @Volatile private var child: Process? = null
    @Volatile private var starts = 0

    init {
        thread(name = "tin-supervisor", isDaemon = true) { supervise() }
    }

    private fun supervise() {
        try {
            install()
        } catch (e: Exception) {
            Log.e(TAG, "install failed", e)
            return
        }
        while (!stopped) {
            val began = System.currentTimeMillis()
            try {
                rotateLog()
                val p = ProcessBuilder(BIN.path, "serve")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(LOG))
                    .start()
                child = p
                starts++
                val code = p.waitFor()
                Log.w(TAG, "tin exited with $code")
            } catch (e: Exception) {
                Log.e(TAG, "tin failed to start", e)
            }
            // A crash right after start (say, @tin already bound by a dev copy) waits longer, so it never spins.
            val ran = System.currentTimeMillis() - began
            Thread.sleep(if (ran < 10_000) 5_000 else 1_000)
        }
    }

    /**
     * Writes the binary next to its final path and renames it over, so a running copy is never written to. Shizuku
     * starts this process with umask 0, so every mode is set explicitly: only shell may read the policy or swap the
     * binary.
     */
    private fun install() {
        val src = File(context.applicationInfo.nativeLibraryDir, "libtin.so")
        val bin = BIN.parentFile!!
        bin.mkdirs()
        Os.chmod(bin.parent!!, 0b111_000_000)
        Os.chmod(bin.path, 0b111_000_000)
        val tmp = File(bin, "tin.new")
        src.copyTo(tmp, overwrite = true)
        Os.chmod(tmp.path, 0b111_000_000)
        check(tmp.renameTo(BIN)) { "rename $tmp to $BIN" }
    }

    private fun rotateLog() {
        if (LOG.length() > 1 shl 20) LOG.renameTo(File(LOG.parentFile, "tin.log.1"))
        LOG.createNewFile()
        Os.chmod(LOG.path, 0b110_000_000)
    }

    override fun status(): String = "alive=${child?.isAlive ?: false} starts=$starts"

    override fun destroy() {
        stopped = true
        child?.destroy()
        exitProcess(0)
    }

    companion object {
        private const val TAG = "tin"
        private val BIN = File("/data/local/tmp/tin/bin/tin")
        private val LOG = File("/data/local/tmp/tin/tin.log")
    }
}
