package com.draco.tagsh

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.nfc.*
import android.nfc.tech.Ndef
import java.io.IOException

class Nfc(context: Context) {
    enum class State {
        UNSUPPORTED, /* Device lacks Nfc support on a hardware level */
        SUPPORTED_OFF, /* The device supports Nfc, but it is currently off */
        SUPPORTED_ON /* The device supports Nfc, and it is currently on */
    }

    private var nfcAdapter = NfcAdapter.getDefaultAdapter(context)

    private var nfcPendingIntent: PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, context.javaClass)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP),
        0
    )

    /* Mime type for NDEF record. P.S.: Takes up Nfc tag space */
    private val mimeType: String = context.getString(R.string.nfc_mime)

    /* Get the byte contents of a Nfc tag */
    fun readBytes(intent: Intent): ByteArray {
        /* Parse any messages */
        val parcelables = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)

        /* Empty tags are still tags */
        if (parcelables.isNullOrEmpty())
            return byteArrayOf()

        /* Get the first record */
        val ndefMessage = parcelables[0] as NdefMessage
        val ndefRecord = ndefMessage.records[0]

        /* Return the content of the first record */
        return Compression.safeDecompress(ndefRecord.payload)
    }

    /* Try to write a ByteArray to a tag. Return true if succeeded */
    fun writeBytes(intent: Intent, bytes: ByteArray): Exception? {
        var exception: Exception? = null
        val currentTag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)

        val ndef = Ndef.get(currentTag) ?: return IOException("Tag was removed from device.")
        val newBytes = Compression.safeCompress(bytes)

        /* Ensure we account for both our bytes and our mime type string */
        if (newBytes.size + mimeType.length > ndef.maxSize)
            return IOException("Contents are too large.")

        /* Don't bother writing if read-only */
        if (!ndef.isWritable)
            return IOException("Tag is not writable.")

        /* Try to write to the tag; if fail, return false */
        try {
            ndef.connect()
            val record = NdefRecord.createMime(mimeType, newBytes)
            ndef.writeNdefMessage(NdefMessage(record))
            ndef.close()
        } catch (_: FormatException) {
            exception = FormatException("Content is malformed.")
        } catch (_: IOException) {
            exception = IOException("Tag is not writable.")
        } catch (_: TagLostException) {
            exception = TagLostException("Tag was removed from device.")
        }

        return exception
    }

    /* Check if the passed intent was caused by a tag */
    fun startedByNDEF(intent: Intent): Boolean {
        return (intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
                intent.action == NfcAdapter.ACTION_TAG_DISCOVERED)
    }

    /* Enable foreground scanning (call from onResume) */
    fun enableForegroundIntent(activity: Activity) {
        nfcAdapter?.enableForegroundDispatch(
            activity,
            nfcPendingIntent,
            null,
            null
        )
    }

    /* Disable foreground scanning (call from onPause) */
    fun disableForegroundIntent(activity: Activity) {
        nfcAdapter?.disableForegroundDispatch(activity)
    }

    /* Check if Nfc is supported on the current device */
    fun supportState(): State {
        /* Device lacks hardware support */
        if (nfcAdapter == null)
            return State.UNSUPPORTED

        /* Device has Nfc disabled */
        if (!nfcAdapter.isEnabled)
            return State.SUPPORTED_OFF

        /* Device has Nfc enabled */
        return State.SUPPORTED_ON
    }
}