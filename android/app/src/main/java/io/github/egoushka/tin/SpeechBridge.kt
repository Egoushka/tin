package io.github.egoushka.tin

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.DeadObjectException
import android.os.IBinder
import android.util.Log
import kotlin.concurrent.thread

/**
 * Serves the abstract socket @tin-speech in the shell-uid process and forwards each line to the app's [ISpeech].
 * Like @tin it answers only root and shell, so another app cannot reach the mic through it.
 */
class SpeechBridge {
    @Volatile private var speech: ISpeech? = null

    fun set(binder: IBinder?) {
        speech = binder?.let { ISpeech.Stub.asInterface(it) }
    }

    fun start() = thread(name = "tin-speech", isDaemon = true) {
        val server = try {
            LocalServerSocket(SOCKET)
        } catch (e: Exception) {
            Log.e(TAG, "bind @$SOCKET failed", e)
            return@thread
        }
        while (true) {
            val client = server.accept()
            thread(name = "tin-speech-conn", isDaemon = true) { client.use(::serve) }
        }
    }

    private fun serve(client: LocalSocket) {
        val uid = client.peerCredentials.uid
        if (uid != 0 && uid != SHELL_UID) {
            Log.w(TAG, "refused @$SOCKET peer uid $uid")
            return
        }
        val reader = client.inputStream.bufferedReader()
        val writer = client.outputStream.bufferedWriter()
        while (true) {
            val line = reader.readLine() ?: return
            writer.write(forward(line))
            writer.newLine()
            writer.flush()
        }
    }

    private fun forward(line: String): String {
        val s = speech ?: return NOT_RUNNING
        return try {
            s.handle(line)
        } catch (e: DeadObjectException) {
            speech = null
            NOT_RUNNING
        } catch (e: Exception) {
            """{"ok":false,"error":${quote("speech app: $e")}}"""
        }
    }

    private companion object {
        const val TAG = "tin"
        const val SOCKET = "tin-speech"
        const val SHELL_UID = 2000
        const val NOT_RUNNING = """{"ok":false,"error":"speech service not running; open tin on the phone"}"""

        fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
}
