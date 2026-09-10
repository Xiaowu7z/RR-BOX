package com.rr.client.routing

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.rr.client.core.model.AppRouteConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppManager(private val context: Context) {
    suspend fun getInstalledApps(
        includeSystem: Boolean = false,
        visiblePackages: Set<String> = emptySet()
    ): List<AppRouteConfig> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val visibleSystemPackages = visiblePackages + AutoProxySelectionPolicy.recommendedPackages()
        val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
        val list = mutableListOf<AppRouteConfig>()

        for (pkg in packages) {
            val appInfo = pkg.applicationInfo ?: continue
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            // Keep launcher apps (including preinstalled browsers) available for manual
            // selection, plus recommended background services such as Google Play.
            if (!includeSystem && isSystem && pkg.packageName !in visibleSystemPackages &&
                pm.getLaunchIntentForPackage(pkg.packageName) == null) continue

            val appName = pm.getApplicationLabel(appInfo).toString()
            val packageName = pkg.packageName

            if (packageName != context.packageName) {
                list.add(
                    AppRouteConfig(
                        packageName = packageName,
                        appName = appName,
                        routeMode = "PROXY_DEFAULT"
                    )
                )
            }
        }
        list.sortedBy { it.appName.lowercase() }
    }
}
