package com.aura.companion.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

object PathUtils {

    /**
     * Resolves a physical absolute filesystem path from an Android Uri.
     * With MANAGE_EXTERNAL_STORAGE enabled, direct path access works across storage.
     */
    fun getPathFromUri(context: Context, uri: Uri): String? {
        // 1. Direct file URI
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path
        }

        // 2. Document URI from External Storage Provider
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val docId = DocumentsContract.getDocumentId(uri)
            val authority = uri.authority

            if ("com.android.externalstorage.documents" == authority) {
                val split = docId.split(":")
                val type = split.getOrNull(0)
                val relativePath = split.getOrNull(1) ?: ""

                if ("primary".equals(type, ignoreCase = true)) {
                    val path1 = Environment.getExternalStorageDirectory().absolutePath + "/" + relativePath
                    if (File(path1).exists()) return path1
                    val path2 = "/storage/emulated/0/$relativePath"
                    if (File(path2).exists()) return path2
                    return path1
                } else if (!type.isNullOrBlank()) {
                    val path = "/storage/$type/$relativePath"
                    if (File(path).exists()) return path
                }
            } else if ("com.android.providers.downloads.documents" == authority) {
                if (docId.startsWith("raw:")) {
                    val raw = docId.removePrefix("raw:")
                    if (File(raw).exists()) return raw
                }
                val fileName = getFileName(context, uri)
                if (!fileName.isNullOrBlank()) {
                    val candidate1 = "/storage/emulated/0/Download/$fileName"
                    if (File(candidate1).exists()) return candidate1
                    val candidate2 = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/$fileName"
                    if (File(candidate2).exists()) return candidate2
                }
            }
        }

        // 3. Fallback: check if decoded uri.path exists directly
        val rawPath = uri.path
        if (!rawPath.isNullOrBlank()) {
            if (File(rawPath).exists()) return rawPath
            val decoded = Uri.decode(rawPath)
            if (File(decoded).exists()) return decoded
            if (decoded.contains("/storage/")) {
                val sub = decoded.substring(decoded.indexOf("/storage/"))
                if (File(sub).exists()) return sub
            }
        }

        // 4. Fallback: Copy to app's internal cache directory if direct POSIX path unavailable
        return copyToInternalStorage(context, uri)
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            try {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) name = it.getString(index)
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/') ?: -1
            if (cut != -1 && name != null) {
                name = name.substring(cut + 1)
            }
        }
        return name
    }

    private fun copyToInternalStorage(context: Context, uri: Uri): String? {
        return try {
            val name = getFileName(context, uri) ?: "custom_gemma_model.litertlm"
            val destFile = File(context.filesDir, name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (destFile.exists()) destFile.absolutePath else null
        } catch (e: Exception) {
            null
        }
    }
}
