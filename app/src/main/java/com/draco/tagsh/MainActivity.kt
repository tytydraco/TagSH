package com.draco.tagsh

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.integration.android.IntentIntegrator
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    /* Private classes */
    private lateinit var nfc: Nfc

    /* Internal constants */
    private val scriptName = "script.sh"
    private val requestCodeSelectScript = 1

    /* Internal variables */
    private lateinit var scriptPath: String
    private lateinit var readyToFlashDialog: AlertDialog
    private var pendingScriptBytes = byteArrayOf()
    private var currentlyExecuting = AtomicBoolean()

    /* UI Elements */
    private lateinit var scrollView: ScrollView
    private lateinit var outputView: TextView

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
        /* Write our script using bytes as it is most versatile */
        val fileOutputStream = openFileOutput(scriptName, Context.MODE_PRIVATE)
        fileOutputStream.write(bytes)
        fileOutputStream.close()

        /* Clear any existing output */
        outputView.text = ""

        /* Execute in another thread */
        Thread {
            currentlyExecuting.set(true)
            /* Execute shell script */
            val processBuilder = ProcessBuilder("sh", scriptPath)
                .redirectErrorStream(true)
                .start()

            val bufferedReader = processBuilder.inputStream.bufferedReader()

            /* Buffer output to outputView */
            bufferedReader.forEachLine {
                runOnUiThread {
                    val currentText = outputView.text.toString()
                    val newText = currentText + it + "\n"

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

            /* Delete script for security reasons */
            deleteFile(scriptName)
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
                /* Initiate QR or barcode scan */
                IntentIntegrator(this)
                    .setPrompt("")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
                    .initiateScan()
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
        scrollView = findViewById(R.id.scrollView)
        outputView = findViewById(R.id.output)
        scriptPath = "${filesDir}/${scriptName}"

        /* Setup ready to flash dialog */
        readyToFlashDialog = AlertDialog.Builder(this)
            .setTitle("Ready to Flash")
            .setMessage("Please scan an NFC tag to flash your script.")
            .setPositiveButton("Cancel", null)
            .create()

        /* Register our Nfc helper class */
        nfc = Nfc()

        /* Register Nfc adapter */
        nfc.registerAdapter(this)

        /* Allow Nfc tags to be scanned while the app is opened */
        nfc.setupForegroundIntent(this)

        /* Warn the user that NFC is either disabled or unsupported */
        if (nfc.supportState() != Nfc.State.SUPPORTED_ON)
            AlertDialog.Builder(this)
                .setTitle("NFC Disabled")
                .setMessage("NFC is either disabled or unsupported on this device. Make sure you enable NFC to use TagSH.")
                .setPositiveButton("Okay", null)
                .show()

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
    }

    /* Disable foreground scanning */
    override fun onPause() {
        super.onPause()
        nfc.disableForegroundIntent(this)
    }
}
