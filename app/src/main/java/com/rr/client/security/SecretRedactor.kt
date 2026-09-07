package com.rr.client.security

/** Best-effort diagnostics sanitization, including quoted JSON keys and URL path tokens. */
object SecretRedactor {
    fun redact(input: String): String {
        var text = UUID.replace(input, "<uuid>")
        text = HTTP_URL.replace(text, "<redacted-url>")
        text = SHARE_URL.replace(text, "<redacted-node>")
        text = JSON_SECRET.replace(text) { "${it.groupValues[1]}\"<redacted>\"" }
        text = TEXT_SECRET.replace(text) { "${it.groupValues[1]}${it.groupValues[2]}<redacted>" }
        text = QUERY_SECRET.replace(text) { "${it.groupValues[1]}<redacted>" }
        return text
    }
    private const val KEYS = "password|passwd|token|secret|private_key|private-key|auth|auth_str|uuid|api_key|psk"
    private val UUID = Regex("""(?i)\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\b""")
    private val HTTP_URL = Regex("""(?i)https?://[^\s<>"']+""")
    private val SHARE_URL = Regex("""(?i)(?:vless|vmess|ss|trojan|hy2|hysteria2?|tuic|anytls|naive\+https|naive\+quic|socks5?|ssh)://[^\s<>"']+""")
    private val JSON_SECRET = Regex("""(?i)("(?:$KEYS)"\s*:\s*)(?:"(?:\\.|[^"\\])*"|[^,}\s]+)""")
    private val TEXT_SECRET = Regex("""(?i)\b($KEYS)(\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^,\s}]+)""")
    private val QUERY_SECRET = Regex("""(?i)([?&](?:$KEYS)=)[^&\s]+""")
}
