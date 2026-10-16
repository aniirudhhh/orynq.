package com.theveloper.pixelplay.data.youtube

import kotlinx.serialization.json.*

/** Accept only an explicit Cookie header, documented browser JSON, or a scoped Netscape export. */
internal fun parseMusicSessionCookie(input: String): String {
    require(input.length in 1..65_536) { "Session input is empty or too large." }
    val raw = input.trim()
    val cookie = when {
        raw.startsWith("{") -> {
            val obj = Json.parseToJsonElement(raw).jsonObject
            (obj["Cookie"] ?: obj["cookie"])?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Choose a browser-session file containing a Cookie header.")
        }
        raw.contains('\t') -> raw.lineSequence().mapNotNull { line ->
            if (line.startsWith('#') && !line.startsWith("#HttpOnly_")) return@mapNotNull null
            val parts = line.removePrefix("#HttpOnly_").split('\t')
            if (parts.size != 7) return@mapNotNull null
            val domain = parts[0].removePrefix(".")
            if (domain !in setOf("youtube.com", "music.youtube.com")) return@mapNotNull null
            if (parts[2] != "/" || parts[3] != "TRUE") return@mapNotNull null
            val expiry = parts[4].toLongOrNull() ?: return@mapNotNull null
            if (expiry != 0L && expiry <= System.currentTimeMillis() / 1000) return@mapNotNull null
            "${parts[5]}=${parts[6]}"
        }.joinToString("; ")
        else -> raw.removePrefix("Cookie:").removePrefix("cookie:").trim()
    }
    require(cookie.length in 1..16_384 && cookie.none { it == '\r' || it == '\n' || it.code < 32 }) {
        "Paste only the Cookie header, or import a browser-session file."
    }
    val values = cookie.split(';').associate { part ->
        val name = part.substringBefore('=').trim()
        require(part.contains('=') && Regex("[A-Za-z0-9_-]+").matches(name)) { "Invalid Cookie header." }
        name to part.substringAfter('=').trim()
    }
    require(listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID").any { !values[it].isNullOrBlank() }) {
        "This session has no YouTube sign-in cookie. Sign in at music.youtube.com first."
    }
    return values.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

data class MusicAccount(val name: String, val avatar: String?, val needsReconnect: Boolean = false)

internal fun parseMusicAccount(root: JsonElement): MusicAccount? {
    fun text(value: JsonElement?): String = when (value) {
        is JsonObject -> value["simpleText"]?.jsonPrimitive?.contentOrNull
            ?: (value["runs"] as? JsonArray)?.joinToString("") { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty() }.orEmpty()
        else -> ""
    }
    fun visit(value: JsonElement): MusicAccount? {
        if (value is JsonObject) {
            (value["activeAccountHeaderRenderer"] as? JsonObject)?.let { header ->
                val name = text(header["accountName"]).take(120)
                if (name.isNotBlank()) {
                    val thumbnails = (header["accountPhoto"] as? JsonObject)?.get("thumbnails") as? JsonArray
                    val avatar = (thumbnails?.lastOrNull() as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
                        ?.takeIf { url -> runCatching { val uri = java.net.URI(url); uri.scheme == "https" && uri.host in setOf("lh3.googleusercontent.com", "yt3.googleusercontent.com", "yt3.ggpht.com") }.getOrDefault(false) }
                    return MusicAccount(name, avatar)
                }
            }
            value.values.forEach { visit(it)?.let { account -> return account } }
        } else if (value is JsonArray) value.forEach { visit(it)?.let { account -> return account } }
        return null
    }
    return visit(root)
}
