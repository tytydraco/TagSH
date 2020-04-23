package com.draco.tagsh

import android.os.PowerManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/* Parameters to use when executing script */
class ExecParams {
    /* Script contents as a byte array */
    var scriptBytes = byteArrayOf()

    /* File name to save script as */
    var scriptName = "script.sh"

    /* Directory for script to use and exist in */
    var workingDir: File? = null

    /* Keep the CPU awake during execution */
    var holdWakelock = false

    /* Maximum time to keep CPU awake */
    var wakelockTimeout = 60

    /* Number of lines of output to keep track of at one time */
    var bufferSize = 100
}

/* Class for clean script execution */
class Execution(private val powerManager: PowerManager) {
    private val wakelockTag = "TagSH::Executing"
    var executing = AtomicBoolean(false)
    var outputBuffer = arrayListOf<String>()

    /* Execute the script using the given parameters */
    fun execute(execParams: ExecParams) {
        /* Fail if not given a working directory */
        if (execParams.workingDir == null)
            return

        /* Acquire lock */
        executing.set(true)

        /* Write our script */
        val fileOutput = File("${execParams.workingDir!!.absolutePath}/${execParams.scriptName}")
        val fileOutputStream = FileOutputStream(fileOutput)
        fileOutputStream.write(execParams.scriptBytes)
        fileOutputStream.close()

        /* Clean output buffer preemptively */
        outputBuffer.clear()

        /* Acquire wakelock */
        val wakelock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, wakelockTag)
        if (execParams.holdWakelock)
            wakelock.acquire(execParams.wakelockTimeout * 1000L)

        /* Begin execution */
        val process = ProcessBuilder("sh", fileOutput.absolutePath)
            .directory(execParams.workingDir)
            .redirectErrorStream(true)
            .start()

        val bufferedReader = process.inputStream.bufferedReader()

        /* Update output buffer with script output */
        while (executing.get()) {
            val line = bufferedReader.readLine() ?: break
            outputBuffer.add(line)

            outputBuffer = ArrayList(outputBuffer.takeLast(execParams.bufferSize))
        }

        /* Release wakelock */
        if (wakelock.isHeld)
            wakelock.release()

        /* Destroy process in case we were interrupted */
        process.destroy()

        /* Release lock */
        executing.set(false)
    }
}