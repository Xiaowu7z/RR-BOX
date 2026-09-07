package com.rr.client.update

import java.net.URI

object ReleaseAssetPolicy {
    fun isTrustedDownload(url: String, assetName: String): Boolean = runCatching {
        val uri = URI(url)
        val segments = uri.rawPath.orEmpty().split('/')
        uri.scheme == "https" && uri.host == "github.com" && uri.rawUserInfo == null &&
            uri.port in listOf(-1, 443) && uri.rawQuery == null && uri.rawFragment == null &&
            segments.size == 7 && segments[1] == "Xiaowu7z" && segments[2] == "RR-BOX" &&
            segments[3] == "releases" && segments[4] == "download" &&
            Regex("v?[0-9]+\\.[0-9]+\\.[0-9]+").matches(segments[5]) && segments[6] == assetName
    }.getOrDefault(false)
}
