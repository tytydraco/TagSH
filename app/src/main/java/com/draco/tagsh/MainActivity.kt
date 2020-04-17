package com.draco.tagsh

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.view.Menu
import android.view.MenuItem
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toDrawable
import androidx.preference.PreferenceManager
import com.google.zxing.integration.android.IntentIntegrator
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    /* Private classes */
    private lateinit var nfc: Nfc

    /* Internal constants */
    private val scriptName = "script.sh"
    private val privacyPolicyPrefName = "privacyPolicyAccepted"
    private val firstLaunchPrefName = "firstLaunch"
    private val wakelockTag = "TagSH::Executing"
    private val requestCodeFlash = 1
    private val requestCodeRun = 2

    /* Internal variables */
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var powerManager: PowerManager
    private lateinit var privacyPolicyDialog: AlertDialog
    private lateinit var readyToFlashDialog: AlertDialog
    private var pendingScriptBytes = byteArrayOf()
    private var currentlyExecuting = AtomicBoolean()
    private var outputBuffer = arrayListOf<String>()

    /* UI Elements */
    private lateinit var scrollView: ScrollView
    private lateinit var outputView: TextView

    /* Choose the best accessible working directory for the script */
    private fun getBestWorkingDir(): File {
        val externalFilesDir = getExternalFilesDir(null)

        /* ~/Android/data/id/files */
        if (externalFilesDir != null)
            return externalFilesDir

        /* ~/Android/data/id/cache */
        if (externalCacheDir != null)
            return externalCacheDir!!

        /* /data/data/id/files */
        return filesDir
    }

    /* Clean and prepare working directory and return its path */
    private fun prepareWorkingDir(delete: Boolean = true): File {
        /* Find best choice of working dir */
        val workingDir = getBestWorkingDir()

        /* Delete everything in it */
        if (delete)
            workingDir.deleteRecursively()

        /* Recreate clean working dir */
        workingDir.mkdirs()

        return workingDir
    }

    /* Load script from storage into memory for flashing */
    private fun loadScriptFromUri(uri: Uri) {
        /* Read contents of script */
        val inputStream = contentResolver.openInputStream(uri)

        /* Make sure we have a valid input stream */
        if (inputStream == null) {
            Toast.makeText(this, "Could not read script.", Toast.LENGTH_SHORT).show()
            return
        }

        pendingScriptBytes = inputStream.readBytes()
        inputStream.close()
    }

    /* Prompt user to select a script from storage */
    private fun promptSelectScript(requestCode: Int) {
        val intent = Intent()
            .setType("*/*")
            .setAction(Intent.ACTION_GET_CONTENT)

        val chooserIntent = Intent.createChooser(intent, "Select script")
        startActivityForResult(chooserIntent, requestCode)
    }

    /* First write out script to internal storage, then execute it */
    private fun executeScriptFromBytes(bytes: ByteArray) {
        /* Clean and prepare working directory */
        val autoClean = sharedPrefs.getBoolean("autoClean", true)
        val workingDir = prepareWorkingDir(autoClean)
        val readOnlyMode = sharedPrefs.getBoolean("viewOnly", false)

        /* Write our script using bytes as it is most versatile */
        val fileOutput = File("${workingDir.absolutePath}/${scriptName}")
        val fileOutputStream = FileOutputStream(fileOutput)
        fileOutputStream.write(bytes)
        fileOutputStream.close()

        /* Clear any existing output */
        if (sharedPrefs.getBoolean("autoClear", true))
            outputBuffer.clear()

        /* Execute in another thread */
        Thread {
            currentlyExecuting.set(true)

            /* Hold a wakelock if we need to */
            val wakelock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, wakelockTag)
            if (sharedPrefs.getBoolean("holdWakelock", true)) {
                val timeoutString = sharedPrefs.getString("wakelockTimeout", "60")
                var timeout = 60
                if (!timeoutString.isNullOrBlank())
                    try {
                        timeout = Integer.parseInt(timeoutString).coerceAtLeast(1)
                    } catch (_: NumberFormatException) {}
                wakelock.acquire(timeout * 1000L)
            }

            /* Execute shell script from internal storage in the working environment */
            val process = if (!readOnlyMode)
                ProcessBuilder("sh", fileOutput.absolutePath)
                    .directory(workingDir)
                    .redirectErrorStream(true)
                    .start()
            else
                null

            /* If we are in read only mode, use our bytes as the input */
            val bufferedReader = if (!readOnlyMode)
                process!!.inputStream.bufferedReader()
            else
                BufferedInputStream(bytes.inputStream()).bufferedReader()

            /* Buffer output to outputView as long as we want to keep running */
            while (currentlyExecuting.get()) {
                /* Try to fetch the next line, or break if we are already finished */
                val line = bufferedReader.readLine() ?: break

                /* Add line to our buffer */
                outputBuffer.add(line)

                /* Before we enter the main thread, calculate our buffer size */
                val bufferSizeString = sharedPrefs.getString("bufferSize", "100")
                var bufferSize = 100
                if (!bufferSizeString.isNullOrBlank())
                    try {
                        bufferSize = Integer.parseInt(bufferSizeString).coerceAtLeast(1)
                    } catch (_: NumberFormatException) {}

                /* Before we enter the main thread, join our buffer into a string */
                val trimmedBuffer = outputBuffer.takeLast(bufferSize)
                val bufferedString = trimmedBuffer.joinToString(System.lineSeparator())

                /* Keep our buffer at a constant size to not use excess memory */
                outputBuffer = ArrayList(trimmedBuffer)

                /* Pipe output to display */
                if (sharedPrefs.getBoolean("showOutput", true)) runOnUiThread {
                    /* Update text using lines from our buffer */
                    outputView.text = bufferedString

                    /* Scroll to bottom of text */
                    if (sharedPrefs.getBoolean("autoScroll", true)) scrollView.post {
                        /* When the text is selectable, it causes scroll jitter */
                        outputView.setTextIsSelectable(false)
                        scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                        outputView.setTextIsSelectable(true)
                    }
                }
            }

            /* Signal that we are finished */
            currentlyExecuting.set(false)

            /* Release our wakelock */
            if (wakelock.isHeld)
                wakelock.release()

            /* Ensure we kill the process */
            process?.destroy()
        }.start()
    }

    /* When our script selection intent finishes */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        /* Don't bother if we failed */
        if (resultCode != Activity.RESULT_OK)
            return

        /* Load script bytes into memory and prompt user to scan tag */
        if (requestCode == requestCodeFlash &&
            data != null &&
            data.data != null) {
                loadScriptFromUri(data.data!!)
                readyToFlashDialog.show()

            return
        }

        /* Load script bytes into memory and execute it */
        if (requestCode == requestCodeRun &&
            data != null &&
            data.data != null) {
            loadScriptFromUri(data.data!!)
            executeScriptFromBytes(pendingScriptBytes)

            return
        }

        /* If we just scanned a QR code or barcode, execute it as a script */
        val scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scanResult != null && scanResult.contents != null)
            executeScriptFromBytes(scanResult.contents.toByteArray())
    }

    /* On toolbar menu item click */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            /* Flash script to tag */
            R.id.flash -> {
                /* Ask user to select script from storage */
                promptSelectScript(requestCodeFlash)
            }

            /* Scan QR or barcode */
            R.id.scan -> {
                /* Ask user to accept the privacy policy */
                if (!privacyPolicyAccepted()) {
                    privacyPolicyDialog.show()
                    return super.onOptionsItemSelected(item)
                }

                /* Initiate QR or barcode scan */
                IntentIntegrator(this)
                    .setPrompt("")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
                    .initiateScan()
            }

            /* Run locally stored script */
            R.id.run -> {
                /* Ask user to select script from storage */
                promptSelectScript(requestCodeRun)
            }

            /* Kill the currently running process */
            R.id.kill -> {
                currentlyExecuting.set(false)
            }

            /* Clear terminal output */
            R.id.clear -> {
                outputView.text = ""
            }

            /* Clean all files in working directory */
            R.id.clean -> {
                prepareWorkingDir()
            }

            /* Show privacy policy */
            R.id.privacy_policy -> {
                privacyPolicyDialog.show()
            }

            /* Open settings activity */
            R.id.settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
            }
        }

        return super.onOptionsItemSelected(item)
    }

    /* Catch Nfc tag scan in our foreground intent filter */
    override fun onNewIntent(thisIntent: Intent?) {
        super.onNewIntent(thisIntent)

        /* Call Nfc tag handler if we are sure this is an Nfc scan */
        if (thisIntent == null)
            return

        /* Decide what to do with the scanned tag */
        if (readyToFlashDialog.isShowing) {
            /* Flash script to NFC tag */
            val exception = nfc.writeBytes(thisIntent, pendingScriptBytes)
            readyToFlashDialog.dismiss()

            /* If there was an issue, report it */
            if (exception != null)
                Toast.makeText(this, exception.message, Toast.LENGTH_SHORT).show()
        } else if (!currentlyExecuting.get()) {
            /* Try to execute whatever script is on the tag */
            executeScriptFromBytes(nfc.readBytes(thisIntent) ?: return)
        }
    }

    /* Update terminal output settings */
    private fun updateOutputViewConfig() {
        /* Use blank strings as default so a user can clear their configuration */
        val allowRotation = sharedPrefs.getBoolean("allowRotation", true)
        val wordWrap = sharedPrefs.getBoolean("wordWrap", true)
        val fontSize = sharedPrefs.getString("fontSize", "")
        val backgroundColor = sharedPrefs.getString("backgroundColor", "")
        val foregroundColor = sharedPrefs.getString("foregroundColor", "")

        requestedOrientation = if (allowRotation)
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        else
            ActivityInfo.SCREEN_ORIENTATION_NOSENSOR

        outputView.setHorizontallyScrolling(!wordWrap)

        if (!fontSize.isNullOrBlank()) {
            val size = fontSize.toFloatOrNull()
            if (size != null)
                outputView.textSize = size.coerceAtLeast(1f)
        } else
            outputView.textSize = 14f

        if (!backgroundColor.isNullOrBlank())
            try {
                val color = Color.parseColor(backgroundColor)
                window.decorView.setBackgroundColor(color)
                supportActionBar!!.setBackgroundDrawable(color.toDrawable())
            } catch (_: IllegalArgumentException) {}
        else {
            val color = getColor(R.color.colorPrimaryDark)
            window.decorView.setBackgroundColor(color)
            supportActionBar!!.setBackgroundDrawable(color.toDrawable())
        }

        if (!foregroundColor.isNullOrBlank())
            try {
                outputView.setTextColor(Color.parseColor(foregroundColor))
            } catch (_: IllegalArgumentException) {}
        else
            outputView.setTextColor(getColor(R.color.colorText))
    }

    /* On activity creation */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        /* Lateinit setup */
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        editor = sharedPrefs.edit()
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        scrollView = findViewById(R.id.scrollView)
        outputView = findViewById(R.id.output)

        /* Set welcome message */
        val welcomeMessage = sharedPrefs.getString("welcomeMessage", "Scan an NFC tag to execute it.")
        if (!welcomeMessage.isNullOrBlank())
            outputView.text = welcomeMessage

        /* Setup privacy policy dialog */
        privacyPolicyDialog = AlertDialog.Builder(this)
            .setTitle("Privacy Policy")
            .setMessage(getString(R.string.privacy_policy_text))
            .setPositiveButton("Accept") { _, _ ->
                editor.putBoolean(privacyPolicyPrefName, true)
                editor.apply()
            }
            .setNegativeButton("Decline") { _, _ ->
                editor.putBoolean(privacyPolicyPrefName, false)
                editor.apply()
                Toast.makeText(this,
                    "QR and barcode scanning require Privacy Policy consent.",
                    Toast.LENGTH_SHORT).show()
            }
            .setCancelable(false)
            .create()

        /* Setup ready to flash dialog */
        readyToFlashDialog = AlertDialog.Builder(this)
            .setTitle("Ready to Flash")
            .setMessage("Please scan an NFC tag to flash your script. Ensure that NFC is enabled.")
            .setPositiveButton("Cancel", null)
            .create()

        /* Configure our terminal using user configuration */
        updateOutputViewConfig()

        /* Register our Nfc helper class */
        nfc = Nfc()

        /* Register Nfc adapter */
        nfc.registerAdapter(this)

        /* Allow Nfc tags to be scanned while the app is opened */
        nfc.setupForegroundIntent(this)

        /* Ask user to accept the privacy policy on first launch */
        if (sharedPrefs.getBoolean(firstLaunchPrefName, true)) {
            privacyPolicyDialog.show()

            editor.putBoolean(firstLaunchPrefName, false)
            editor.apply()
        }

        /* Try to execute whatever script is on the tag */
        executeScriptFromBytes(nfc.readBytes(intent) ?: return)
    }

    /* ----- Miscellaneous Setup ----- */

    /* Returns current status on privacy policy acceptance */
    private fun privacyPolicyAccepted(): Boolean {
        return sharedPrefs.getBoolean(privacyPolicyPrefName, false)
    }

    /* Inflate custom menu to toolbar */
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.mainactivity, menu)
        return super.onCreateOptionsMenu(menu)
    }

    /* Enable foreground scanning */
    override fun onResume() {
        super.onResume()
        nfc.enableForegroundIntent(this)

        /* We may have come back from the settings page, so update the view */
        updateOutputViewConfig()
    }

    /* Disable foreground scanning */
    override fun onPause() {
        super.onPause()
        nfc.disableForegroundIntent(this)
    }
}
