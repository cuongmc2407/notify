package com.notifybridge.net

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Goi API cua server. Dung HttpURLConnection cua he thong nen app khong can
 * them thu vien mang nao.
 */
object Api {

    private const val CONNECT_TIMEOUT_MS = 15000
    private const val READ_TIMEOUT_MS = 20000

    class ApiException(val code: Int, message: String) : Exception(message)

    data class PairResult(
        val deviceId: String,
        val token: String,
        val deviceName: String,
        /** true = server nhan ra may cu va cap lai token, khong tao them may moi. */
        val reused: Boolean,
    )

    /** Ghep doi bang ma 6 so lay tu web dashboard. */
    fun pair(
        baseUrl: String,
        code: String,
        deviceName: String,
        model: String,
        androidVersion: String,
        hardwareId: String,
    ): PairResult {
        val body = JSONObject()
            .put("code", code)
            .put("deviceName", deviceName)
            .put("model", model)
            .put("androidVersion", androidVersion)
            .put("hardwareId", hardwareId)

        val json = post("$baseUrl/api/pair", null, body.toString())
        return PairResult(
            deviceId = json.optString("deviceId"),
            token = json.optString("token"),
            deviceName = json.optString("deviceName", deviceName),
            reused = json.optBoolean("reused", false),
        )
    }

    /**
     * Bo loc ung dung do server giu. Server la ban chinh: sua tren web hay tren may
     * deu ve day, [version] tang moi lan doi de may biet khi nao can cap nhat.
     */
    data class Filter(val version: Int, val blocked: Set<String>)

    data class UploadResult(val accepted: Int, val filter: Filter?)

    /** Gui mot lo thong bao. Tra ve so ban ghi server ghi nhan moi + bo loc hien hanh. */
    fun upload(
        baseUrl: String,
        token: String,
        events: JSONArray,
        battery: Int = -1,
        charging: Boolean = false,
    ): UploadResult {
        val body = JSONObject().put("events", events)
        putBattery(body, battery, charging)
        val json = post("$baseUrl/api/notifications", token, body.toString())
        return UploadResult(json.optInt("accepted", 0), parseFilter(json))
    }

    /** Bao cho server biet may van dang song, kem muc pin. Tra ve bo loc hien hanh. */
    fun heartbeat(
        baseUrl: String,
        token: String,
        battery: Int = -1,
        charging: Boolean = false,
    ): Filter? {
        val body = JSONObject()
        putBattery(body, battery, charging)
        return parseFilter(post("$baseUrl/api/heartbeat", token, body.toString()))
    }

    /** Day bo loc nguoi dung vua sua tren may len server. */
    fun pushFilter(baseUrl: String, token: String, blocked: Set<String>): Filter? {
        val body = JSONObject().put("blocked", JSONArray(blocked.toList()))
        return parseFilter(post("$baseUrl/api/device/filter", token, body.toString()))
    }

    private fun parseFilter(json: JSONObject): Filter? {
        val obj = json.optJSONObject("filter") ?: return null
        val arr = obj.optJSONArray("blocked") ?: JSONArray()
        val blocked = HashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optString(i).takeIf { it.isNotBlank() }?.let(blocked::add)
        }
        return Filter(obj.optInt("version", 0), blocked)
    }

    /** Chi gui khi doc duoc pin, de server phan biet "chua biet" voi "0%". */
    private fun putBattery(body: JSONObject, battery: Int, charging: Boolean) {
        if (battery in 0..100) {
            body.put("battery", battery)
            body.put("charging", charging)
        }
    }

    private fun post(url: String, token: String?, body: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }

        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()

            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).optString("message") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "HTTP $code"
                throw ApiException(code, message)
            }

            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
