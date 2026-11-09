package com.skytr1x.obhod.strategy

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import com.skytr1x.obhod.proxy.NativeNetworkOperations
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object DomainListRepository {
    private const val MAX_DOMAINS = 100_000
    private const val PREFERENCES = "zapret"
    private const val LAST_UPDATED_KEY = "domain_list_updated_at"
    private val domainPattern = Regex(
        "(?i)^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*" +
            "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$"
    )

    data class UpdateResult(val domainCount: Int, val updatedAt: Long)

    suspend fun update(context: Context): UpdateResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val bytes = NativeNetworkOperations.fetchDomainList()
            val domains = parseDomains(bytes.toString(Charsets.UTF_8))
            val updatedAt = System.currentTimeMillis()
            saveAtomically(context, JSONObject()
                .put("version", 1)
                .put("include", JSONArray(domains))
                .toString()
                .toByteArray(Charsets.UTF_8))
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putLong(LAST_UPDATED_KEY, updatedAt)
                .apply()
            UpdateResult(domains.size, updatedAt)
        }

    fun lastUpdated(context: Context): Long =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getLong(LAST_UPDATED_KEY, 0L)

    fun applyCachedList(context: Context, profileId: String, profileJson: String): String {
        if (profileId != StrategyProfiles.DEFAULT_ID) return profileJson
        val cacheFile = cacheFile(context)
        if (!cacheFile.isFile) return profileJson

        val domains = parseDomains(cacheFile.readText())
        val profile = JSONObject(profileJson)
        profile.put("include", JSONArray(domains))
        return profile.toString()
    }

    private fun parseDomains(json: String): List<String> {
        val root = JSONObject(json)
        val include = root.optJSONArray("include")
            ?: error("В файле списка отсутствует массив include")
        require(include.length() in 1..MAX_DOMAINS) {
            "Список должен содержать от 1 до $MAX_DOMAINS доменов"
        }

        val domains = LinkedHashSet<String>()
        for (index in 0 until include.length()) {
            val raw = include.optString(index).trim().lowercase(Locale.ROOT)
            require(raw.length <= 253 && domainPattern.matches(raw)) {
                "Некорректный домен в списке: ${include.optString(index)}"
            }
            domains.add(raw)
        }
        require(domains.isNotEmpty()) { "Список доменов пуст" }
        return domains.toList()
    }

    private fun saveAtomically(context: Context, bytes: ByteArray) {
        val directory = File(context.filesDir, "list")
        check(directory.isDirectory || directory.mkdirs()) {
            "Не удалось создать каталог списка"
        }

        val temporaryFile = File(directory, "list.json.tmp")
        val destination = cacheFile(context)
        try {
            FileOutputStream(temporaryFile).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Os.rename(temporaryFile.absolutePath, destination.absolutePath)
        } finally {
            temporaryFile.delete()
        }
    }

    private fun cacheFile(context: Context) = File(context.filesDir, "list/list.json")
}
