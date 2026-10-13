package android.net

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class Uri private constructor(private val raw: String) {
    val scheme: String?
        get() = runCatching {
            if (raw.contains("://")) raw.substringBefore("://").lowercase()
            else if (raw.contains(":")) raw.substringBefore(":").lowercase()
            else null
        }.getOrNull()

    val authority: String?
        get() = runCatching { URI(raw).authority }.getOrNull()

    val path: String?
        get() = when {
            raw.startsWith("file:") -> {
                runCatching {
                    val uriObj = URI(raw.replace(" ", "%20"))
                    File(uriObj).absolutePath
                }.getOrElse {
                    var s = raw.removePrefix("file:")
                    while (s.startsWith("/")) s = s.substring(1)
                    val decoded = URLDecoder.decode(s, Charsets.UTF_8)
                    if (decoded.length >= 2 && decoded[1] == ':') decoded else "/$decoded"
                }
            }
            raw.startsWith("content://") -> {
                val afterAuth = raw.removePrefix("content://").substringAfter('/', "")
                URLDecoder.decode(afterAuth, Charsets.UTF_8)
            }
            else -> raw
        }

    val lastPathSegment: String?
        get() {
            val p = path ?: raw
            return p.replace('\\', '/').trimEnd('/').substringAfterLast('/').takeIf { it.isNotEmpty() }
        }

    fun buildUpon(): Builder = Builder(raw)

    class Builder(private var base: String = "") {
        private val params = mutableListOf<Pair<String, String>>()
        fun appendQueryParameter(key: String, value: String): Builder {
            params += key to value
            return this
        }
        fun build(): Uri {
            if (params.isEmpty()) return parse(base)
            val query = params.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}"
            }
            val sep = if (base.contains("?")) "&" else "?"
            return parse("$base$sep$query")
        }
    }

    override fun toString(): String = raw
    override fun equals(other: Any?): Boolean = other is Uri && other.raw == raw
    override fun hashCode(): Int = raw.hashCode()

    companion object {
        fun parse(uriString: String): Uri {
            val trimmed = uriString.trim()
            if (trimmed.length >= 2 && trimmed[1] == ':' && (trimmed.length == 2 || trimmed[2] == '\\' || trimmed[2] == '/')) {
                return fromFile(File(trimmed))
            }
            return Uri(trimmed)
        }

        fun fromFile(file: File): Uri = Uri(file.toURI().toString())

        fun encode(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)
    }
}
