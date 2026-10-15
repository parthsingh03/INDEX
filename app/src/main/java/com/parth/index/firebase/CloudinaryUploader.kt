package com.parth.index.firebase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Uploads file bytes to Cloudinary with a plain HTTPS multipart POST —
 *  no SDK, no API key, no secret. The preset is UNSIGNED, so the request
 *  carries only the file and the preset name, and Cloudinary answers with
 *  JSON containing the public `secure_url` the file can be read from.
 *
 *  Runs entirely on Dispatchers.IO and streams the file in chunks, so a
 *  large PDF never sits whole in memory and the UI thread never blocks. */
object CloudinaryUploader {

    data class Result(val secureUrl: String, val bytes: Long)

    /** Upload [file] and return its public URL + exact byte size.
     *  Throws on any network / Cloudinary error so callers can surface an
     *  honest "upload failed" instead of saving a record with no file. */
    suspend fun upload(file: File, fileName: String): Result =
        withContext(Dispatchers.IO) {
            require(file.exists()) { "File to upload does not exist: ${file.absolutePath}" }
            val boundary = "index-" + System.currentTimeMillis().toString(16)
            val conn = URL(CloudConfig.CLOUDINARY_UPLOAD_URL)
                .openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 30_000
                conn.readTimeout = 180_000
                conn.setChunkedStreamingMode(64 * 1024)
                conn.setRequestProperty(
                    "Content-Type", "multipart/form-data; boundary=$boundary"
                )
                conn.outputStream.use { out ->
                    writeField(out, boundary, "upload_preset", CloudConfig.CLOUDINARY_UPLOAD_PRESET)
                    writeFileField(out, boundary, file, fileName)
                    out.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
                }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
                if (code !in 200..299) {
                    val message = runCatching {
                        JSONObject(body).getJSONObject("error").getString("message")
                    }.getOrNull() ?: "HTTP $code"
                    throw IllegalStateException("Cloudinary upload failed: $message")
                }
                val json = JSONObject(body)
                val url = json.getString("secure_url")
                val bytes = json.optLong("bytes", file.length())
                Result(url, bytes)
            } finally {
                conn.disconnect()
            }
        }

    private fun writeField(out: OutputStream, boundary: String, name: String, value: String) {
        out.write(
            ("--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"$name\"\r\n\r\n" +
                value + "\r\n").toByteArray(Charsets.UTF_8)
        )
    }

    private fun writeFileField(out: OutputStream, boundary: String, file: File, fileName: String) {
        val safeName = fileName.replace("\"", "_")
        out.write(
            ("--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n").toByteArray(Charsets.UTF_8)
        )
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
        }
        out.write("\r\n".toByteArray(Charsets.UTF_8))
    }
}
