package com.skytr1x.obhod.proxy

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

internal interface TelegramProxyLibrary : Library {
    fun StartProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int
    fun StopProxy(): Int
    fun SetPoolSize(size: Int)
    fun SetCfProxyCacheDir(path: String)
    fun FetchDomainList(output: ByteArray, capacity: Int): Int
    fun RunTransferTest(
        service: String,
        host: String,
        resolvedIp: String,
        path: String,
        attempt: Int,
        payloadKiB: Int,
        hostTimeoutMillis: Int,
        idleTimeoutSeconds: Int
    ): Pointer?
    fun FreeString(value: Pointer)
}

object NativeTelegramProxy {
    internal val library: TelegramProxyLibrary by lazy {
        Native.load("tgwsproxy", TelegramProxyLibrary::class.java)
    }

    fun start(host: String, port: Int, secret: String, cacheDir: String): Int {
        library.SetPoolSize(4)
        library.SetCfProxyCacheDir(cacheDir)
        return library.StartProxy(host, port, "", secret, 1)
    }

    fun stop() = library.StopProxy()
}
