package com.jarvis.assistant.ai

import com.jarvis.assistant.util.AppLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

private const val TAG = "WebTools"

data class WebResult(val title: String, val snippet: String, val url: String)

/**
 * A tool call the model asked for by emitting a one-line JSON directive
 * instead of prose — see [parseToolDirective] and WEB_TOOL_INSTRUCTION in
 * `ChatViewModel`. Provider-agnostic on purpose: it works identically for
 * Gemini/Anthropic/OpenAI/Ollama without touching each provider's native
 * tool-call wire format.
 */
data class ToolDirective(val tool: String, val query: String?, val url: String?)

/**
 * Returns the directive if [text] is (only) a tool-call JSON line, else null.
 * Tolerates surrounding whitespace and markdown code fences, since small
 * models love wrapping JSON in ```json fences no matter what you tell them.
 */
fun parseToolDirective(text: String): ToolDirective? {
    var t = text.trim()
    if (t.startsWith("```")) {
        t = t.removePrefix("```json").removePrefix("```").trim()
        t = t.removeSuffix("```").trim()
    }
    if (!t.startsWith("{") || !t.endsWith("}")) return null
    return runCatching {
        val json = JSONObject(t)
        val tool = json.optString("tool", "")
        if (tool.isEmpty()) return null
        ToolDirective(
            tool = tool,
            query = json.optString("query", "").ifBlank { null },
            url = json.optString("url", "").ifBlank { null },
        )
    }.getOrNull()
}

/**
 * Blocking web search/fetch used by the tool loop (call from Dispatchers.IO)
 * and by the on-device model's `@Tool` functions (LiteRT invokes those on
 * its own background thread). DuckDuckGo's HTML endpoint needs no API key,
 * which matters here — web search should work even for users who only run
 * the on-device or Ollama backends and never enter any key.
 */
object WebTools {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        // Checking only the URL we were handed would leave the hole open:
        // a public address can 302 to an internal one, and OkHttp follows
        // redirects for us. Validating in an interceptor covers every hop.
        .addInterceptor { chain ->
            requirePublicHttpUrl(chain.request().url.toString())
            chain.proceed(chain.request())
        }
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    fun search(query: String, maxResults: Int = 5): List<WebResult> {
        AppLogger.i(TAG, "web_search: \"$query\"")
        val url = "https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val html = get(url)

        // DuckDuckGo's HTML results: <a class="result__a" href="...uddg=<enc>...">Title</a>
        // and <a class="result__snippet" ...>snippet</a>. Regex parsing is
        // fragile in general but this endpoint's markup has been stable for
        // years and a parse miss just means fewer results, not a crash.
        val linkRegex = Regex(
            """<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val snippetRegex = Regex(
            """class="result__snippet"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val links = linkRegex.findAll(html).toList()
        val snippets = snippetRegex.findAll(html).map { stripTags(it.groupValues[1]) }.toList()

        return links.take(maxResults).mapIndexed { i, match ->
            WebResult(
                title = stripTags(match.groupValues[2]),
                snippet = snippets.getOrNull(i).orEmpty(),
                url = resolveDuckDuckGoUrl(match.groupValues[1]),
            )
        }.filter { it.title.isNotBlank() }
    }

    /** Fetches [pageUrl] and returns readable text (tags stripped), capped for prompt size. */
    fun fetchPage(pageUrl: String, maxChars: Int = 6000): String {
        AppLogger.i(TAG, "fetch_page: $pageUrl")
        requirePublicHttpUrl(pageUrl)
        val html = get(pageUrl)
        val withoutScripts = html
            .replace(Regex("""<script.*?</script>""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""<style.*?</style>""", RegexOption.DOT_MATCHES_ALL), " ")
        val text = stripTags(withoutScripts)
            .replace(Regex("""\s{3,}"""), "\n")
            .trim()
        return if (text.length <= maxChars) text else text.take(maxChars) + "\n[…truncated]"
    }

    /**
     * Rejects anything that isn't a public http(s) address.
     *
     * The URL fetched here can originate from a web page the model just
     * read: a hostile page can instruct the model to fetch some other
     * address, and the model will comply. Without this, that's a
     * server-side-request-forgery path into the user's own network — a
     * router admin page, a printer, a LAN service — with the contents
     * summarized straight back into the conversation. Cleartext is allowed
     * app-wide for LAN Ollama servers, which makes plain http://192.168.x.x
     * reachable too, so the check is on the resolved address rather than
     * the scheme alone.
     */
    private fun requirePublicHttpUrl(url: String) {
        val parsed = runCatching { java.net.URI(url) }.getOrNull()
            ?: error("That doesn't look like a valid URL.")
        val scheme = parsed.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") {
            "Only http and https addresses can be fetched."
        }
        val host = parsed.host ?: error("That URL has no host.")

        val addresses = runCatching { java.net.InetAddress.getAllByName(host) }
            .getOrElse { error("Couldn't resolve $host.") }
        require(addresses.isNotEmpty()) { "Couldn't resolve $host." }
        addresses.forEach { address ->
            require(
                !address.isLoopbackAddress && !address.isAnyLocalAddress &&
                    !address.isLinkLocalAddress && !address.isSiteLocalAddress &&
                    !address.isMulticastAddress && !isUniqueLocalIpv6(address)
            ) {
                "For safety Jarvis only fetches public web addresses, not devices " +
                    "on your local network ($host)."
            }
        }
    }

    /** IPv6 unique-local (fc00::/7) — the v6 equivalent of a private range. */
    private fun isUniqueLocalIpv6(address: java.net.InetAddress): Boolean =
        address is java.net.Inet6Address && (address.address.firstOrNull()?.toInt()?.and(0xFE) == 0xFC)

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} from ${resp.request.url.host}")
            return resp.body?.string().orEmpty()
        }
    }

    /** DDG links point at a redirect: //duckduckgo.com/l/?uddg=<encoded real url>&… */
    private fun resolveDuckDuckGoUrl(href: String): String {
        val uddg = Regex("""[?&]uddg=([^&]+)""").find(href)?.groupValues?.get(1)
        return if (uddg != null) {
            runCatching { URLDecoder.decode(uddg, "UTF-8") }.getOrDefault(href)
        } else if (href.startsWith("//")) "https:$href" else href
    }

    private fun stripTags(html: String): String = html
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#x27;", "'").replace("&nbsp;", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}
