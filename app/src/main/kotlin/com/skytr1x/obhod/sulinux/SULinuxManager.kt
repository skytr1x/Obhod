package com.skytr1x.obhod.sulinux

import android.content.Context
import android.os.Build
import android.os.Process
import com.skytr1x.obhod.logging.AppLog
import com.skytr1x.obhod.strategy.StrategyProfiles
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

object SULinuxManager {
    private const val TAG = "SULinux"
    const val ID = "sulinux"
    const val DEFAULT_PORT = 10800

    private const val MODULE_ID = ID
    private const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
    private const val UPDATE_DIR = "/data/adb/modules_update/$MODULE_ID"
    private const val STATE_DIR = "/data/adb/sulinux"
    private const val FALLBACK_DIR = "$STATE_DIR/fallback"
    private const val TMP_ZIP = "$STATE_DIR/module.zip"
    private const val FIXED_ZIP_TIME = 315_532_800_000L
    private val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

    data class Outcome(val ok: Boolean, val message: String)

    private data class ShellResult(val code: Int, val output: String) {
        val ok: Boolean get() = code == 0
    }

    fun activate(context: Context): Outcome {
        AppLog.i(TAG, "Проверка root-доступа…")
        if (!isRootAvailable()) {
            return Outcome(
                false,
                "SULinux требует root (KernelSU или временный DFRoot). " +
                    "Предоставьте приложению root-доступ и повторите."
            )
        }

        val abi = Build.SUPPORTED_ABIS.firstOrNull(SUPPORTED_ABIS::contains)
            ?: return Outcome(false, "Нет SULinux-бинарника для ABI ${Build.SUPPORTED_ABIS.joinToString()}")
        val uid = Process.myUid()
        val config = "APP_UID=$uid\nPORT=$DEFAULT_PORT\nDROP_QUIC=1\n".toByteArray()

        val profile = try {
            buildProfile(context)
        } catch (error: Exception) {
            return Outcome(false, "Не удалось собрать профиль SULinux: ${error.message}")
        }
        val script = try {
            readAsset(context, "sulinux/sulinux.sh")
        } catch (error: Exception) {
            return Outcome(false, "В APK отсутствует sulinux.sh: ${error.message}")
        }
        val binary = try {
            readAsset(context, "sulinux/bin/$abi/sulinux")
        } catch (error: Exception) {
            return Outcome(false, "В APK отсутствует SULinux-бинарник ($abi): ${error.message}")
        }

        root("mkdir -p '$STATE_DIR/cache'")
        writeRootFile("$STATE_DIR/config", config)

        ensureFallback(script, binary, profile, config)

        val zip = try {
            buildModuleZip(context, profile, config, abi)
        } catch (error: Exception) {
            return Outcome(false, "Не удалось собрать KernelSU-модуль: ${error.message}")
        }
        val zipBytes = zip.readBytes()
        zip.delete()
        val install = installModule(zipBytes)
        if (install.message.isNotBlank()) AppLog.i(TAG, install.message)

        val moduleReady = exists("$MODULE_DIR/sulinux.sh")
        val scriptPath = if (moduleReady) "$MODULE_DIR/sulinux.sh" else "$FALLBACK_DIR/sulinux.sh"
        val apply = root("sh '$scriptPath' apply", timeoutMs = 90_000)
        val output = apply.output.trim()
        if (output.isNotBlank()) AppLog.i(TAG, output)
        if (!output.contains("SULINUX_APPLY_OK")) {
            val detail = output.lineSequence()
                .lastOrNull { it.startsWith("SULINUX_APPLY_FAIL") }
                ?: output.lineSequence().lastOrNull { it.isNotBlank() }
                ?: "код ${apply.code}"
            return Outcome(false, "SULinux не запустился: $detail")
        }
        val mode = when {
            moduleReady -> "KernelSU-модуль"
            install.message.contains("после перезагрузки") -> "прямой root-режим (модуль ждёт перезагрузки)"
            else -> "прямой root-режим (KernelSU не найден)"
        }
        return Outcome(true, "SULinux активен: $mode")
    }

    fun deactivate(context: Context): Outcome {
        if (!isRootAvailable()) {
            return Outcome(false, "Нет root-доступа для остановки SULinux")
        }
        root("rm -f '$STATE_DIR/active'")
        val script = when {
            exists("$MODULE_DIR/sulinux.sh") -> "$MODULE_DIR/sulinux.sh"
            exists("$FALLBACK_DIR/sulinux.sh") -> "$FALLBACK_DIR/sulinux.sh"
            else -> null
        }
        val result = if (script != null) {
            root("sh '$script' restore", timeoutMs = 60_000)
        } else {
            root(FALLBACK_RESTORE, timeoutMs = 60_000)
        }
        val output = result.output.trim()
        if (output.isNotBlank()) AppLog.i(TAG, output)
        return if (output.contains("SULINUX_RESTORE_OK") || result.ok) {
            Outcome(true, "SULinux остановлен")
        } else {
            Outcome(false, "Не удалось остановить SULinux: ${output.takeLast(500)}")
        }
    }

    fun isRootAvailable(): Boolean {
        val result = root("id")
        return result.ok && result.output.contains("uid=0")
    }

    private fun installModule(zipBytes: ByteArray): Outcome {
        val ksud = findKsud()
            ?: return Outcome(false, "KernelSU (ksud) не найден; используется прямой root-режим")
        val hash = sha256(zipBytes)
        val installedHash = root("cat '$STATE_DIR/module.sha256' 2>/dev/null").output.trim()
        if (exists("$MODULE_DIR/module.prop") && installedHash == hash) {
            return Outcome(true, "KernelSU-модуль уже установлен")
        }

        val written = writeRootFile(TMP_ZIP, zipBytes, timeoutMs = 60_000)
        if (!written.ok) {
            return Outcome(false, "Не удалось передать модуль root: ${written.output.takeLast(300)}")
        }
        val install = root("'$ksud' module install '$TMP_ZIP'", timeoutMs = 180_000)
        root("rm -f '$TMP_ZIP'")
        val installed = exists("$MODULE_DIR/module.prop")
        val staged = exists("$UPDATE_DIR/module.prop")
        if (!installed) {
            return if (staged) {
                Outcome(false, "KernelSU подготовил модуль; он применится после перезагрузки")
            } else {
                Outcome(
                    false,
                    "KernelSU не установил модуль: ${install.output.takeLast(600).ifBlank { "код ${install.code}" }}"
                )
            }
        }
        writeRootFile("$STATE_DIR/module.sha256", hash.toByteArray())
        return Outcome(true, "KernelSU-модуль установлен и активирован без перезагрузки")
    }

    private fun ensureFallback(
        script: ByteArray,
        binary: ByteArray,
        profile: ByteArray,
        config: ByteArray
    ) {
        val hash = sha256(script + binary + profile + config)
        val current = root("cat '$FALLBACK_DIR/hash' 2>/dev/null").output.trim()
        if (current == hash) return
        root("rm -rf '$FALLBACK_DIR'")
        writeRootFile("$FALLBACK_DIR/bin/sulinux", binary, timeoutMs = 60_000)
        writeRootFile("$FALLBACK_DIR/sulinux.sh", script)
        writeRootFile("$FALLBACK_DIR/profile.json", profile)
        writeRootFile("$FALLBACK_DIR/config", config)
        writeRootFile("$FALLBACK_DIR/hash", hash.toByteArray())
        root("chmod 0755 '$FALLBACK_DIR/bin/sulinux' '$FALLBACK_DIR/sulinux.sh'")
    }

    private fun buildModuleZip(
        context: Context,
        profile: ByteArray,
        config: ByteArray,
        abi: String
    ): File {
        val zipFile = File(context.cacheDir, "sulinux-module.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zip ->
            fun add(name: String, bytes: ByteArray) {
                val entry = ZipEntry(name).apply { time = FIXED_ZIP_TIME }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            fun addAsset(asset: String, entry: String) {
                add(entry, readAsset(context, asset))
            }
            addAsset("sulinux/module.prop", "module.prop")
            addAsset("sulinux/customize.sh", "customize.sh")
            addAsset("sulinux/sulinux.sh", "sulinux.sh")
            addAsset("sulinux/service.sh", "service.sh")
            addAsset("sulinux/uninstall.sh", "uninstall.sh")
            addAsset("sulinux/action.sh", "action.sh")
            add("profile.json", profile)
            add("config", config)
            addAsset("sulinux/bin/$abi/sulinux", "bin/sulinux")
        }
        return zipFile
    }

    private fun buildProfile(context: Context): ByteArray {
        val base = readAsset(context, "strategies/list.json").toString(Charsets.UTF_8)
        val adaptive = readAsset(context, "strategies/adaptive-desync-chain.json")
            .toString(Charsets.UTF_8)
        val merged = JSONObject(StrategyProfiles.mergeAdaptiveProfile(base, adaptive)).apply {
            remove("include")
            remove("exclude")
            remove("rules")
            put("socks_port", DEFAULT_PORT)
            put("direct_ru", false)
            put("direct_first", true)
            put("adaptive_chain", true)
        }
        return merged.toString(2).toByteArray(Charsets.UTF_8)
    }

    private fun findKsud(): String? {
        val result = root(
            "command -v ksud 2>/dev/null || ls /data/adb/ksud 2>/dev/null || " +
                "ls /data/adb/ksu/bin/ksud 2>/dev/null"
        )
        return result.output.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("/") && !it.contains(' ') }
    }

    private fun exists(path: String): Boolean =
        root("[ -f '$path' ] && echo yes").output.contains("yes")

    private fun readAsset(context: Context, path: String): ByteArray =
        context.assets.open(path).use { it.readBytes() }

    private fun writeRootFile(
        path: String,
        bytes: ByteArray,
        timeoutMs: Long = 30_000
    ): ShellResult {
        val parent = path.substringBeforeLast('/', "")
        val prefix = if (parent.isNotEmpty()) "mkdir -p '$parent' && " else ""
        return runSu("${prefix}cat > '$path'", bytes, timeoutMs)
    }

    private fun root(command: String, timeoutMs: Long = 20_000): ShellResult =
        runSu(command, null, timeoutMs)

    private val suBinary: String by lazy {
        listOf("/system/bin/su", "/system/xbin/su", "/debug_ramdisk/su", "/sbin/su")
            .firstOrNull { File(it).exists() } ?: "su"
    }

    private fun runSu(command: String, input: ByteArray?, timeoutMs: Long): ShellResult {
        val process = try {
            ProcessBuilder(suBinary, "-c", command).redirectErrorStream(true).start()
        } catch (error: Exception) {
            return ShellResult(-1, error.message ?: "su недоступен")
        }
        val output = StringBuilder()
        val lock = Any()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(lock) { output.append(line).append('\n') }
                }
            } catch (_: Exception) {
            }
        }.apply {
            isDaemon = true
            start()
        }
        val writer = if (input != null) {
            Thread {
                try {
                    process.outputStream.use { it.write(input) }
                } catch (_: Exception) {
                }
            }.apply {
                isDaemon = true
                start()
            }
        } else {
            runCatching { process.outputStream.close() }
            null
        }

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
        reader.join(1_000)
        writer?.join(1_000)
        val code = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
        return ShellResult(code, synchronized(lock) { output.toString() })
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private const val FALLBACK_RESTORE = """
        P=$STATE_DIR/sulinux.pid
        if [ -f "${'$'}P" ]; then kill $(cat "${'$'}P") 2>/dev/null; rm -f "${'$'}P"; fi
        W=$STATE_DIR/watch.pid
        if [ -f "${'$'}W" ]; then kill $(cat "${'$'}W") 2>/dev/null; rm -f "${'$'}W"; fi
        nft delete table inet sulinux 2>/dev/null
        for cmd in iptables ip6tables; do
          command -v ${'$'}cmd >/dev/null 2>&1 || continue
          while ${'$'}cmd -w -t nat -D OUTPUT -j SULINUX_NAT 2>/dev/null; do :; done
          while ${'$'}cmd -w -t filter -D OUTPUT -j SULINUX_FILTER 2>/dev/null; do :; done
          ${'$'}cmd -w -t nat -F SULINUX_NAT 2>/dev/null
          ${'$'}cmd -w -t nat -X SULINUX_NAT 2>/dev/null
          ${'$'}cmd -w -t filter -F SULINUX_FILTER 2>/dev/null
          ${'$'}cmd -w -t filter -X SULINUX_FILTER 2>/dev/null
        done
        echo SULINUX_RESTORE_OK
    """
}
