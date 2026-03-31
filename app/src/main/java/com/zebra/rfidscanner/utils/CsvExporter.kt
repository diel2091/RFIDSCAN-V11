package com.zebra.rfidscanner.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvExporter {

    fun buildEpcCsv(tags: List<String>): String = buildString {
        appendLine("EPC")
        tags.forEach { appendLine(it) }
    }

    fun buildEanCsv(tags: List<String>): String = buildString {
        appendLine("EPC,GTIN14,EAN13,CompanyPrefix,ItemReference,Serial,Valid,Error")
        tags.forEach { epc ->
            val r = SgtinDecoder.decode(epc)
            appendLine("${r.epc},${r.gtin14},${r.ean13},${r.companyPrefix},${r.itemReference},${r.serial},${r.isValid},${r.error}")
        }
    }

    fun saveToDownloads(context: Context, content: String, filename: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { out ->
                        out.write(content.toByteArray())
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    context.contentResolver.update(it, values, null, null)
                }
                uri
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val file = File(dir, filename)
                FileWriter(file).use { it.write(content) }
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            Log.e("CsvExporter", "Save error", e)
            null
        }
    }

    fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
}
