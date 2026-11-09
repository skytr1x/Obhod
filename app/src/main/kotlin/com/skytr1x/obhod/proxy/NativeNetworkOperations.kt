package com.skytr1x.obhod.proxy

import com.sun.jna.Pointer
import java.io.IOException
import com.skytr1x.obhod.diagnostics.TransferTestResult
import org.json.JSONException
import org.json.JSONObject

internal object NativeNetworkOperations {
    private const val MAX_DOMAIN_LIST_BYTES = 8 * 1024 * 1024

    fun fetchDomainList(): ByteArray {
        val buffer = ByteArray(MAX_DOMAIN_LIST_BYTES)
        val result = NativeTelegramProxy.library.FetchDomainList(buffer, buffer.size)
        if (result >= 0) return buffer.copyOf(result)

        when (result) {
            -1 -> throw IOException("Не удалось загрузить список доменов")
            -2 -> throw IOException("Файл списка превышает допустимый размер")
            -3 -> throw IOException("Некорректный буфер загрузки списка")
            else -> if (result <= -1000) {
                throw IOException("Сервер списка ответил HTTP ${-result - 1000}")
            } else {
                throw IOException("Ошибка загрузки списка доменов ($result)")
            }
        }
    }

    fun runTransferTest(
        service: String,
        host: String,
        resolvedIp: String,
        path: String,
        attempt: Int,
        payloadKiB: Int,
        hostTimeoutMillis: Int,
        idleTimeoutSeconds: Int
    ): TransferTestResult {
        val response: Pointer = NativeTelegramProxy.library.RunTransferTest(
            service,
            host,
            resolvedIp,
            path,
            attempt,
            payloadKiB,
            hostTimeoutMillis,
            idleTimeoutSeconds
        ) ?: throw IOException("Нативный тест передачи не вернул результат")

        return try {
            try {
                parseTransferTestResult(response.getString(0, Charsets.UTF_8.name()))
            } catch (error: JSONException) {
                throw IOException("Нативный тест вернул некорректный результат", error)
            }
        } finally {
            NativeTelegramProxy.library.FreeString(response)
        }
    }
}

internal fun parseTransferTestResult(json: String): TransferTestResult {
    val result = JSONObject(json)
    return TransferTestResult(
        service = result.getString("service"),
        attempt = result.getInt("attempt"),
        outcome = result.getString("outcome"),
        bytesReceived = result.getInt("bytesReceived"),
        elapsedMs = result.getLong("elapsedMs"),
        dohResolveMs = result.optionalLong("dohResolveMs"),
        socksConnectMs = result.optionalLong("socksConnectMs"),
        tlsHandshakeMs = result.optionalLong("tlsHandshakeMs"),
        transferMs = result.optionalLong("transferMs"),
        httpStatus = result.optionalInt("httpStatus")
    )
}

private fun JSONObject.optionalLong(name: String): Long? =
    if (isNull(name)) null else getLong(name)

private fun JSONObject.optionalInt(name: String): Int? =
    if (isNull(name)) null else getInt(name)
