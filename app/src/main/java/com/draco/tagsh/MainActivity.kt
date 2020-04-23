package com.draco.tagsh

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import java.io.File

class MainActivity : AppCompatActivity() {
    /* Private classes */
    private lateinit var nfc: Nfc
    private lateinit var execution: Execution

    /* Internal constants */
    private val maxScriptSize = 1024 * 32
    private val requestCodeFlash = 1
    private val requestCodeRun = 2

    /* Internal variables */
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var privacyPolicyDialog: AlertDialog
    private lateinit var readyToFlashDialog: AlertDialog
    private var pendingScriptBytes = byteArrayOf()

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

        /* Allocate and read a maximum of maxScriptSize bytes */
        val tempBuffer = ByteArray(maxScriptSize)
        val bytesRead = inputStream.read(tempBuffer, 0, maxScriptSize)
        pendingScriptBytes = tempBuffer.copyOf(bytesRead)

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

    private fun updateOutputView() {
        outputView.text = execution.outputBuffer.joinToString(System.lineSeparator())

        if (sharedPrefs.getBoolean("autoScroll", true)) scrollView.post {
            outputView.setTextIsSelectable(false)
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            outputView.setTextIsSelectable(true)
        }
    }

    /* First write out script to internal storage, then execute it */
    private fun executeScriptFromBytes(bytes: ByteArray) {
        if (execution.executing.get())
            return

        if (sharedPrefs.getBoolean("viewOnly", false)) {
            outputView.text = String(bytes)
            return
        }

        val execParams = ExecParams()
        with (execParams) {
            scriptBytes = bytes
            workingDir = prepareWorkingDir(sharedPrefs.getBoolean("autoClean", true))
            holdWakelock = sharedPrefs.getBoolean("holdWakelock", false)

            val wakelockTimeoutString = sharedPrefs.getString("wakelockTimeout", "60")
            if (!wakelockTimeoutString.isNullOrBlank()) try {
                wakelockTimeout = Integer.parseInt(wakelockTimeoutString).coerceAtLeast(1)
            } catch (_: NumberFormatException) {}

            val bufferSizeString = sharedPrefs.getString("bufferSize", "100")
            if (!bufferSizeString.isNullOrBlank()) try {
                bufferSize = Integer.parseInt(bufferSizeString).coerceAtLeast(1)
            } catch (_: NumberFormatException) {}
        }

        /* Execute in another thread */
        Thread {
            execution.execute(execParams)
        }.start()

        /* Handle output in another thread */
        Thread {
            while (execution.executing.get()) {
                runOnUiThread {
                    updateOutputView()
                }

                Thread.sleep(100)
            }

            /* Final update after thread ends */
            runOnUiThread {
                updateOutputView()
            }
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
                when (nfc.supportState()) {
                    Nfc.State.SUPPORTED_ON -> promptSelectScript(requestCodeFlash)
                    Nfc.State.SUPPORTED_OFF ->
                        Toast.makeText(this, "Enable NFC to use this feature.", Toast.LENGTH_SHORT).show()
                    Nfc.State.UNSUPPORTED ->
                        Toast.makeText(this, "Your device does not support NFC.", Toast.LENGTH_SHORT).show()
                }
            }

            /* Scan QR or barcode */
            R.id.scan -> {
                /* Ask user to accept the privacy policy */
                if (!sharedPrefs.getBoolean("privacyPolicyAccepted", false)) {
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
                execution.executing.set(false)
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
        } else {
            /* Try to execute whatever script is on the tag */
            executeScriptFromBytes(nfc.readBytes(thisIntent) ?: return)
        }
    }

    /* Update terminal output settings */
    private fun updateOutputViewConfig() {
        /* Use blank strings as default so a user can clear their configuration */
        val wordWrap = sharedPrefs.getBoolean("wordWrap", true)
        val fontSize = sharedPrefs.getString("fontSize", "")
        val backgroundColor = sharedPrefs.getString("backgroundColor", "")
        val foregroundColor = sharedPrefs.getString("foregroundColor", "")

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
                editor.putBoolean("privacyPolicyAccepted", true)
                editor.apply()
            }
            .setNegativeButton("Decline") { _, _ ->
                editor.putBoolean("privacyPolicyAccepted", false)
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

        /* Create our execution environment */
        execution = Execution(getSystemService(Context.POWER_SERVICE) as PowerManager)

        /* Register our Nfc helper class */
        nfc = Nfc()

        /* Register Nfc adapter */
        nfc.registerAdapter(this)

        /* Allow Nfc tags to be scanned while the app is opened */
        nfc.setupForegroundIntent(this)

        /* Ask user to accept the privacy policy on first launch */
        if (sharedPrefs.getBoolean("firstLaunch", true)) {
            privacyPolicyDialog.show()

            editor.putBoolean("firstLaunch", false)
            editor.apply()
        }

        /* Try to execute whatever script is on the tag */
        executeScriptFromBytes(nfc.readBytes(intent) ?: return)
    }

    /* ----- Miscellaneous Setup ----- */

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
