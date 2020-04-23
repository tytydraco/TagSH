package com.draco.tagsh

import android.os.PowerManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ExecParams {
    var scriptBytes = byteArrayOf()
    var scriptName = "script.sh"
    var workingDir: File? = null

    var holdWakelock = false
    var wakelockTimeout = 60
    var bufferSize = 100
}

class Execution(private val powerManager: PowerManager) {
    private val wakelockTag = "TagSH::Executing"
    var executing = AtomicBoolean(false)

    var outputBuffer = arrayListOf<String>()

    fun execute(execParams: ExecParams) {
        if (execParams.workingDir == null)
            return

        executing.set(true)

        val fileOutput = File("${execParams.workingDir!!.absolutePath}/${execParams.scriptName}")
        val fileOutputStream = FileOutputStream(fileOutput)
        fileOutputStream.write(execParams.scriptBytes)
        fileOutputStream.close()

        outputBuffer.clear()

        val wakelock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, wakelockTag)

        if (execParams.holdWakelock)
            wakelock.acquire(execParams.wakelockTimeout * 1000L)

        val process = ProcessBuilder("sh", fileOutput.absolutePath)
            .directory(execParams.workingDir)
            .redirectErrorStream(true)
            .start()

        val bufferedReader = process.inputStream.bufferedReader()

        while (executing.get()) {
            val line = bufferedReader.readLine() ?: break
            outputBuffer.add(line)

            outputBuffer = ArrayList(outputBuffer.takeLast(execParams.bufferSize))
        }

        if (wakelock.isHeld)
            wakelock.release()

        process.destroy()

        executing.set(false)
    }
}