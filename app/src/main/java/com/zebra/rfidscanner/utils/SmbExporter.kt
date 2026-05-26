package com.zebra.rfidscanner.utils

import android.content.Context
import android.content.SharedPreferences
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileOutputStream
import java.util.Properties

object SmbExporter {

    private const val PREFS_NAME = "smb_prefs"
    private const val KEY_HOST   = "smb_host"
    private const val KEY_SHARE  = "smb_share"
    private const val KEY_USER   = "smb_user"
    private const val KEY_PASS   = "smb_pass"
    private const val KEY_DOMAIN = "smb_domain"

    data class SmbConfig(
        val host: String,
        val share: String,
        val user: String,
        val password: String,
        val domain: String = ""
    )

    fun saveConfig(context: Context, config: SmbConfig) {
        prefs(context).edit()
            .putString(KEY_HOST,   config.host)
            .putString(KEY_SHARE,  config.share)
            .putString(KEY_USER,   config.user)
            .putString(KEY_PASS,   config.password)
            .putString(KEY_DOMAIN, config.domain)
            .apply()
    }

    fun loadConfig(context: Context): SmbConfig? {
        val p = prefs(context)
        val host = p.getString(KEY_HOST, "") ?: ""
        val share = p.getString(KEY_SHARE, "") ?: ""
        val user = p.getString(KEY_USER, "") ?: ""
        if (host.isEmpty() || user.isEmpty()) return null
        return SmbConfig(
            host     = host,
            share    = share,
            user     = user,
            password = p.getString(KEY_PASS, "") ?: "",
            domain   = p.getString(KEY_DOMAIN, "") ?: ""
        )
    }

    private fun buildContext(config: SmbConfig): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.enableSMB2", "true")
            setProperty("jcifs.smb.client.disableSMB1", "false")
            setProperty("jcifs.resolveOrder", "DNS")
        }
        return BaseContext(PropertyConfiguration(props))
            .withCredentials(
                NtlmPasswordAuthenticator(config.domain, config.user, config.password)
            )
    }

    private fun buildUrl(config: SmbConfig, fileName: String): String {
        val share = config.share.trimStart('\\', '/').trimEnd('\\', '/')
        return "smb://${config.host}/$share/$fileName"
    }

    // Subir CSV (texto)
    fun upload(config: SmbConfig, fileName: String, csvContent: String): String? {
        return try {
            val cifsContext = buildContext(config)
            val url = buildUrl(config, fileName)
            SmbFile(url, cifsContext).use { smbFile ->
                SmbFileOutputStream(smbFile).use { out ->
                    out.write(csvContent.toByteArray(Charsets.UTF_8))
                }
            }
            null
        } catch (e: Exception) {
            e.message ?: "Error desconocido"
        }
    }

    // Subir XLSX (bytes) — mismo mecanismo pero recibe ByteArray
    fun uploadBytes(config: SmbConfig, fileName: String, bytes: ByteArray): String? {
        return try {
            val cifsContext = buildContext(config)
            val url = buildUrl(config, fileName)
            SmbFile(url, cifsContext).use { smbFile ->
                SmbFileOutputStream(smbFile).use { out ->
                    out.write(bytes)
                }
            }
            null
        } catch (e: Exception) {
            e.message ?: "Error desconocido"
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
