package com.rr.client.vpn

import android.util.Log

/** Thin JNI wrapper around hev-socks5-tunnel. */
class HevTunnelNative private constructor() {
    companion object {
        private const val TAG = "HevTunnelNative"

        @Volatile
        private var loadState: Boolean? = null

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStartService(configPath: String, fd: Int): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStopService(): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyIsRunning(): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyGetStats(): LongArray?

        @Synchronized
        fun ensureLoaded(): Boolean {
            loadState?.let { return it }
            val loaded = runCatching {
                System.loadLibrary("hev-socks5-tunnel")
                true
            }.getOrElse { error ->
                Log.e(TAG, "Unable to load HEV native library", error)
                false
            }
            loadState = loaded
            return loaded
        }

        fun start(configPath: String, fd: Int): Boolean {
            if (!ensureLoaded()) return false
            return runCatching { TProxyStartService(configPath, fd) }
                .onFailure { Log.e(TAG, "HEV start failed", it) }
                .getOrDefault(false)
        }

        fun stop(): Boolean {
            if (loadState != true) return true
            return runCatching { TProxyStopService() }
                .onFailure { Log.e(TAG, "HEV stop failed", it) }
                .getOrDefault(false)
        }

        fun isRunning(): Boolean {
            if (loadState != true) return false
            return runCatching { TProxyIsRunning() }.getOrDefault(false)
        }

        /** [txPackets, txBytes, rxPackets, rxBytes] */
        fun stats(): LongArray? {
            if (loadState != true) return null
            return runCatching { TProxyGetStats() }.getOrNull()
        }
    }
}
