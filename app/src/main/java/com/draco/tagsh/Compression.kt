package com.draco.tagsh

import java.io.ByteArrayOutputStream
import java.lang.Exception
import java.util.zip.*

class Compression {
    companion object {
        fun compress(bytes: ByteArray): ByteArray {
            val byteStream = ByteArrayOutputStream()
            val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
            val deflaterStream = DeflaterOutputStream(byteStream, deflater)
            deflaterStream.write(bytes)
            deflaterStream.close()
            return byteStream.toByteArray()
        }

        /* Try and compress. If fail, return original */
        fun safeCompress(bytes: ByteArray): ByteArray {
            return try {
                compress(bytes)
            } catch(_: Exception) {
                bytes
            }
        }

        fun decompress(bytes: ByteArray): ByteArray {
            val byteStream = ByteArrayOutputStream()
            val inflater = Inflater(true)
            val inflaterStream = InflaterOutputStream(byteStream, inflater)
            inflaterStream.write(bytes)
            inflaterStream.close()
            return byteStream.toByteArray()
        }

        /* Try and decompress. If fail, return original */
        fun safeDecompress(bytes: ByteArray): ByteArray {
            return try {
                decompress(bytes)
            } catch(_: Exception) {
                bytes
            }
        }
    }
}