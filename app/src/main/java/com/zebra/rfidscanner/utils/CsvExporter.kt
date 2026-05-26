package com.zebra.rfidscanner.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
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

    // CSV agrupado EAN+QTY — solo EANs válidos, omite EPCs sin EAN
    fun buildEanQtyCsv(tags: List<String>): String = buildString {
        appendLine("EAN,QTY")
        val eanQtyMap = mutableMapOf<String, Int>()
        tags.forEach { epc ->
            val r = SgtinDecoder.decode(epc)
            if (r.isValid && r.ean13.isNotBlank()) {
                eanQtyMap[r.ean13] = (eanQtyMap[r.ean13] ?: 0) + 1
            }
            // EPCs sin EAN se omiten
        }
        eanQtyMap.entries.sortedBy { it.key }.forEach { (ean, qty) ->
            appendLine("$ean,$qty")
        }
    }

    // XLSX agrupado EAN+QTY — formato Excel con encabezado en negrita
    fun buildEanQtyXlsx(tags: List<String>): ByteArray {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Inventario")

        // Estilo encabezado — fondo azul, texto blanco, negrita
        val headerStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.DARK_BLUE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
            val font = workbook.createFont().apply {
                bold = true
                color = IndexedColors.WHITE.index
                fontHeightInPoints = 11
            }
            setFont(font)
        }

        // Estilo datos — centrado
        val dataStyle = workbook.createCellStyle().apply {
            alignment = HorizontalAlignment.CENTER
        }

        // Estilo QTY — centrado, negrita
        val qtyStyle = workbook.createCellStyle().apply {
            alignment = HorizontalAlignment.CENTER
            val font = workbook.createFont().apply { bold = true }
            setFont(font)
        }

        // Encabezado
        val headerRow = sheet.createRow(0)
        headerRow.createCell(0).apply {
            setCellValue("EAN")
            cellStyle = headerStyle
        }
        headerRow.createCell(1).apply {
            setCellValue("QTY")
            cellStyle = headerStyle
        }

        // Agrupar EPCs por EAN — omitir los que no tienen EAN válido
        val eanQtyMap = mutableMapOf<String, Int>()
        tags.forEach { epc ->
            val r = SgtinDecoder.decode(epc)
            if (r.isValid && r.ean13.isNotBlank()) {
                eanQtyMap[r.ean13] = (eanQtyMap[r.ean13] ?: 0) + 1
            }
        }

        // Escribir datos ordenados por EAN
        eanQtyMap.entries.sortedBy { it.key }.forEachIndexed { idx, (ean, qty) ->
            val row = sheet.createRow(idx + 1)
            row.createCell(0).apply {
                setCellValue(ean)
                cellStyle = dataStyle
            }
            row.createCell(1).apply {
                setCellValue(qty.toDouble())
                cellStyle = qtyStyle
            }
        }

        // Ajustar ancho de columnas automáticamente
        sheet.setColumnWidth(0, 6000) // EAN ~20 chars
        sheet.setColumnWidth(1, 3000) // QTY

        // Convertir a bytes
        val outputStream = java.io.ByteArrayOutputStream()
        workbook.write(outputStream)
        workbook.close()
        return outputStream.toByteArray()
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

    fun saveXlsxToDownloads(context: Context, bytes: ByteArray, filename: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { out -> out.write(bytes) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    context.contentResolver.update(it, values, null, null)
                }
                uri
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val file = File(dir, filename)
                file.writeBytes(bytes)
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            Log.e("CsvExporter", "XLSX save error", e)
            null
        }
    }

    fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
}
