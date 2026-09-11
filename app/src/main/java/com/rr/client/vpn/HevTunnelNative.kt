package com.rr.client.vpn

import android.util.Log
import android.os.Build
import androidx.annotation.Keep

/** Thin JNI wrapper around hev-socks5-tunnel. */
@Keep
class HevTunnelNative private constructor() {
    companion object {
        private const val TAG = "HevTunnelNative"

        @Volatile
        private var loadState: Boolean? = null

        @Volatile
        private var ownerRouter: HevConnectionOwnerRouter? = null

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxySetAppRouting(enabled: Boolean)

        /** Called only with the original TUN tuple. Never fall back after lookup failure. */
        @Keep
        @JvmStatic
        fun resolveRoute(protocol: Int, sourceAddress: String, sourcePort: Int,
                         destinationAddress: String, destinationPort: Int): Long {
            val router = ownerRouter ?: return 0L
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1L
            return try {
                router.resolve(protocol, sourceAddress, sourcePort, destinationAddress, destinationPort)
            } catch (_: Exception) {
                -1L
            }
        }

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

        @Synchronized
        fun start(configPath: String, fd: Int, router: HevConnectionOwnerRouter? = null): Boolean {
            if (!ensureLoaded()) return false
            return runCatching {
                // Join the old worker before replacing its immutable routing snapshot.
                check(TProxyStopService()) { "HEV previous worker did not stop" }
                ownerRouter = router
                TProxySetAppRouting(router != null)
                TProxyStartService(configPath, fd)
            }
                .onFailure { Log.e(TAG, "HEV start failed", it) }
                .getOrDefault(false)
        }

        @Synchronized
        fun stop(): Boolean {
            if (loadState != true) return true
            return runCatching {
                val stopped = TProxyStopService()
                if (stopped) {
                    TProxySetAppRouting(false)
                    ownerRouter = null
                }
                stopped
            }
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
