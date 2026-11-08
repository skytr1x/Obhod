package com.skytr1x.obhod.proxy

import com.sun.jna.Library
import com.sun.jna.Native

private interface TelegramProxyLibrary : Library {
    fun StartProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int
    fun StopProxy(): Int
    fun SetPoolSize(size: Int)
    fun SetCfProxyCacheDir(path: String)
}

object NativeTelegramProxy {
    private val library: TelegramProxyLibrary by lazy {
        Native.load("tgwsproxy", TelegramProxyLibrary::class.java)
    }

    fun start(host: String, port: Int, secret: String, cacheDir: String): Int {
        library.SetPoolSize(4)
        library.SetCfProxyCacheDir(cacheDir)
        return library.StartProxy(host, port, "", secret, 1)
    }

    fun stop() = library.StopProxy()
}
