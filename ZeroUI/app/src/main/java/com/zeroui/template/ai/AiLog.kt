package com.zeroui.template.ai

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Error log of the AI features at `Android/data/<package>/files/logs/ai.log`. */
object AiLog {

    private const val DIR = "logs"
    private const val FILE = "ai.log"

    /** Size above which the older half of the file is dropped. */
    private const val MAX_BYTES = 256 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Appends one line; [code] is the code shown on screen, [detail] the text kept off screen. */
    fun record(context: Context, code: String, detail: String) {
        if (detail.isBlank()) return
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, DIR)
                if (!dir.exists() && !dir.mkdirs()) return@runCatching
                val file = File(dir, FILE)
                if (file.length() > MAX_BYTES) trim(file)
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                file.appendText("[$stamp] $code  $detail\n")
            }
        }
    }

    private fun trim(file: File) {
        runCatching {
            val lines = file.readLines()
            file.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
        }
    }
}
