package com.zebra.rfidscanner.utils

import java.math.BigInteger

object SgtinDecoder {

    data class SgtinResult(
        val epc: String,
        val gtin14: String,
        val ean13: String,
        val companyPrefix: String,
        val itemReference: String,
        val serial: String,
        val isValid: Boolean,
        val error: String = ""
    )

    // GS1 SGTIN-96 partition table: cpBits, cpDigits, irBits, irDigits
    // cpDigits + irDigits = 12 always (for EAN-13 base)
    private val PARTITION_TABLE = mapOf(
        0 to intArrayOf(40, 12, 4,  1),
        1 to intArrayOf(37, 11, 7,  2),
        2 to intArrayOf(34, 10, 10, 3),
        3 to intArrayOf(30,  9, 14, 4),
        4 to intArrayOf(27,  8, 17, 5),
        5 to intArrayOf(24,  7, 20, 5),
        6 to intArrayOf(20,  6, 24, 6)
    )

    fun decode(epc: String): SgtinResult {
        try {
            val clean = epc.trim().replace(" ", "").uppercase()

            if (clean.length != 24) {
                return SgtinResult(epc, "", "", "", "", "", false,
                    "EPC debe tener 24 hex chars, tiene ${clean.length}")
            }

            val bin = BigInteger(clean, 16).toString(2).padStart(96, '0')

            val header = bin.substring(0, 8).toInt(2)
            if (header != 0x30) {
                return SgtinResult(epc, "", "", "", "", "", false,
                    "No es SGTIN-96 (header=0x${header.toString(16).uppercase()})")
            }

            val partition = bin.substring(11, 14).toInt(2)
            val pd = PARTITION_TABLE[partition]
                ?: return SgtinResult(epc, "", "", "", "", "", false,
                    "Partición inválida: $partition")

            val cpBits = pd[0]; val cpDigits = pd[1]
            val irBits = pd[2]; val irDigits = pd[3]

            val cpStart = 14
            val cpEnd   = cpStart + cpBits
            val irEnd   = cpEnd + irBits

            val companyPrefix = BigInteger(bin.substring(cpStart, cpEnd), 2)
                .toString().padStart(cpDigits, '0')
            val itemReference = BigInteger(bin.substring(cpEnd, irEnd), 2)
                .toString().padStart(irDigits, '0')
            val serial = BigInteger(bin.substring(irEnd, 96), 2).toString()

            // GTIN-13 = companyPrefix(N) + itemReference(M) + checkDigit, where N+M=12
            val gtinNoCheck = companyPrefix + itemReference
            val checkDigit = gs1Check(gtinNoCheck)
            val ean13  = gtinNoCheck + checkDigit.toString()
            val gtin14 = "0$ean13"

            return SgtinResult(epc, gtin14, ean13, companyPrefix, itemReference, serial, true)

        } catch (e: Exception) {
            return SgtinResult(epc, "", "", "", "", "", false, "Error: ${e.message}")
        }
    }

    private fun gs1Check(digits: String): Int {
        var sum = 0
        digits.reversed().forEachIndexed { i, c ->
            sum += if (i % 2 == 0) c.digitToInt() * 3 else c.digitToInt()
        }
        return (10 - sum % 10) % 10
    }
}
