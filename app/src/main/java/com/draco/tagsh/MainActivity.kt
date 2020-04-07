package com.draco.tagsh

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.google.zxing.integration.android.IntentIntegrator
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    /* Private classes */
    private lateinit var nfc: Nfc

    /* Internal constants */
    private val scriptName = "script.sh"
    private val privacyPolicyPrefName = "privacyPolicyAccepted"
    private val firstLaunchPrefName = "firstLaunch"
    private val requestCodeSelectScript = 1

    /* Internal variables */
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var privacyPolicyDialog: AlertDialog
    private lateinit var readyToFlashDialog: AlertDialog
    private var pendingScriptBytes = byteArrayOf()
    private var currentlyExecuting = AtomicBoolean()

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
    private fun prepareWorkingDir(): File {
        /* Find best choice of working dir */
        val workingDir = getBestWorkingDir()

        /* Delete everything in it */
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
    private fun promptSelectScript() {
        val intent = Intent()
            .setType("*/*")
            .setAction(Intent.ACTION_GET_CONTENT)

        val chooserIntent = Intent.createChooser(intent, "Select script")
        startActivityForResult(chooserIntent, requestCodeSelectScript)
    }

    /* First write out script to internal storage, then execute it */
    private fun executeScriptFromBytes(bytes: ByteArray) {
        /* Clean and prepare working directory */
        val workingDir = prepareWorkingDir()

        /* Write our script using bytes as it is most versatile */
        val fileOutput = File("${workingDir.absolutePath}/${scriptName}")
        val fileOutputStream = FileOutputStream(fileOutput)
        fileOutputStream.write(bytes)
        fileOutputStream.close()

        /* Clear any existing output */
        outputView.text = ""

        /* Execute in another thread */
        Thread {
            currentlyExecuting.set(true)
            /* Execute shell script from internal storage in the working environment */
            val processBuilder = ProcessBuilder("sh", fileOutput.absolutePath)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()

            val bufferedReader = processBuilder.inputStream.bufferedReader()

            /* Buffer output to outputView as long as we want to keep running */
            while (currentlyExecuting.get()) {
                /* Try to fetch the next line, or break if we are already finished */
                val line = bufferedReader.readLine() ?: break

                runOnUiThread {
                    val currentText = outputView.text.toString()
                    val newText = currentText + line + "\n"

                    /* Update output text */
                    outputView.text = newText

                    /* Scroll to bottom of text */
                    scrollView.post {
                        /* When the text is selectable, it causes scroll jitter */
                        outputView.setTextIsSelectable(false)
                        scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                        outputView.setTextIsSelectable(true)
                    }
                }
            }

            /* Signal that we are finished */
            currentlyExecuting.set(false)

            /* Ensure we kill the process */
            processBuilder.destroy()
        }.start()
    }

    /* When our script selection intent finishes */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        /* Don't bother if we failed */
        if (resultCode != Activity.RESULT_OK)
            return

        /* Load script bytes into memory and prompt user to scan tag */
        if (requestCode == requestCodeSelectScript &&
            data != null &&
            data.data != null) {
                loadScriptFromUri(data.data!!)
                readyToFlashDialog.show()

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
            R.id.flash -> {
                /* Ask user to select script from storage */
                promptSelectScript()
            }

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

    /* On activity creation */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        /* Lateinit setup */
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        editor = sharedPrefs.edit()
        scrollView = findViewById(R.id.scrollView)
        outputView = findViewById(R.id.output)

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
    }

    /* Disable foreground scanning */
    override fun onPause() {
        super.onPause()
        nfc.disableForegroundIntent(this)
    }
}
