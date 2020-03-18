package com.draco.tagsh

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.lang.Exception
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    /* Private classes */
    private lateinit var nfc: Nfc

    /* Internal variables */
    private lateinit var tagScriptPath: String
    private val tagScriptName = "script.sh"
    private var pendingScriptBytes = byteArrayOf()
    private var currentlyExecuting = AtomicBoolean()

    /* Ready to flash dialog */
    private lateinit var readyToFlashDialog: AlertDialog

    /* UI Elements */
    private lateinit var scrollView: ScrollView
    private lateinit var outputView: TextView

    /* Flash saved script contents to NFC tag */
    private fun flashScriptToTag(intent: Intent) {
        val exception = nfc.writeBytes(intent, pendingScriptBytes)

        if (exception != null)
            Toast.makeText(this, exception.message, Toast.LENGTH_SHORT).show()

        readyToFlashDialog.dismiss()

        /* Clear pending bytes for security */
        pendingScriptBytes = byteArrayOf()
    }

    /* Load script from storage and get ready to flash */
    private fun loadScriptFromStorage(uri: Uri?) {
        /* Valid Uri check */
        if (uri == null)
            return

        /* Read contents of script */
        try {
            val inputStream = contentResolver.openInputStream(uri)
            pendingScriptBytes = inputStream!!.readBytes()
            inputStream.close()
            readyToFlashDialog.show()
        } catch (_: Exception) {
            Toast.makeText(this, "Script cannot be read.", Toast.LENGTH_SHORT).show()
        }
    }

    /* Select script from storage */
    private fun selectScriptFromStorage() {
        val intent = Intent()
            .setType("*/*")
            .setAction(Intent.ACTION_GET_CONTENT)
        val chooserIntent = Intent.createChooser(intent, "Select script")
        startActivityForResult(chooserIntent, FLASH_SCRIPT_REQUEST_CODE)
    }

    /* Execute whatever script is on NFC tag */
    private fun executeScriptFromTag(intent: Intent) {
        /* Read contents as compressed bytes */
        val nfcContent = nfc.readBytes(intent) ?: return

        /* Write contents to local file */
        val fileOutputStream = openFileOutput(tagScriptName, Context.MODE_PRIVATE)
        fileOutputStream.write(nfcContent)
        fileOutputStream.close()

        /* Clear any existing output */
        outputView.text = ""

        /* Execute in another thread */
        Thread {
            currentlyExecuting.set(true)
            /* Execute shell script */
            val processBuilder = ProcessBuilder("sh", tagScriptPath)
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
                        scrollView.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }

            /* Signal that we are finished */
            currentlyExecuting.set(false)

            /* Delete script for security reasons */
            deleteFile(tagScriptName)
        }.start()
    }

    /* Warn the user that NFC is either disabled or unsupported */
    private fun warnNfcDisabled() {
        AlertDialog.Builder(this)
            .setTitle("NFC Disabled")
            .setMessage("NFC is either disabled or unsupported on this device. Make sure you enable NFC to use this app.")
            .setPositiveButton("Okay", null)
            .show()
    }

    /* On activity creation */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        /* Lateinit setup */
        scrollView = findViewById(R.id.scrollView)
        outputView = findViewById(R.id.output)
        tagScriptPath = "${filesDir}/${tagScriptName}"

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

        /* Make sure NFC is enabled */
        if (nfc.supportState() != Nfc.State.SUPPORTED_ON)
            warnNfcDisabled()

        /* Check if we opened the app due to a Nfc event */
        executeScriptFromTag(intent)
    }

    /* ----- Miscellaneous Setup ----- */

    /* When our script selection intent finishes */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == FLASH_SCRIPT_REQUEST_CODE && resultCode == RESULT_OK)
            if (data != null)
                loadScriptFromStorage(data.data!!)
    }

    /* On toolbar menu item click */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.flash -> {
                /* Ask user to select script from storage */
                selectScriptFromStorage()
            }
        }

        return super.onOptionsItemSelected(item)
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

    /* Catch Nfc tag scan in our foreground intent filter */
    override fun onNewIntent(thisIntent: Intent?) {
        super.onNewIntent(thisIntent)

        /* Call Nfc tag handler if we are sure this is an Nfc scan */
        if (thisIntent == null)
            return

        /* Decide what to do with the scanned tag */
        if (readyToFlashDialog.isShowing)
            flashScriptToTag(thisIntent)
        else if (!currentlyExecuting.get())
            executeScriptFromTag(thisIntent)
    }
}
