package dev.miniscreenpipe

import java.net.URI
import java.util.Locale

/** Best-effort text filters. They do not redact screenshot pixels. */
object TextPrivacy {
    private val email=Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",RegexOption.IGNORE_CASE)
    private val key=Regex("\\b(?:AIza[A-Za-z0-9_-]{20,}|sk-[A-Za-z0-9_-]{20,}|AQ\\.[A-Za-z0-9_-]{20,})")
    private val digits=Regex("(?<![\\p{L}\\p{N}])\\+?\\d[\\d ()-]{7,24}\\d(?![\\p{L}\\p{N}])")
    fun redact(text:String):String {
        var clean=key.replace(text,"[REDACTED KEY]")
        clean=email.replace(clean,"[REDACTED EMAIL]")
        return digits.replace(clean) { m -> if(!Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(m.value) && m.value.count { it.isDigit() } in 10..19)"[REDACTED NUMBER]" else m.value }
    }
    private fun host(value:String):String? = try {
        val url=value.trim();val uri=URI(if("://" in url)url else "https://$url")
        uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')
    } catch(_:Exception) { null }
    fun blockedUrl(value:String,blocked:String):Boolean {
        val domain=host(value) ?: return false
        return blocked.split('\n',',').any { item -> val rule=host(item) ?: return@any false; domain==rule || domain.endsWith(".$rule") }
    }
    fun privateLabel(text:String):Boolean = text.trim().lowercase(Locale.ROOT) in setOf(
        "you're incognito","you’ve gone incognito","you've gone incognito","incognito tab","incognito tabs",
        "private browsing","private browsing mode","private tab","private tabs","inprivate","inprivate tab")
    fun browser(pkg:String)=listOf("chrome","browser","firefox","brave","opera","com.microsoft.emmx","duckduckgo").any { pkg.contains(it,ignoreCase=true) }
    fun addressField(id:String)=listOf("url_bar","urlbar","address_bar","addressbar","location_bar","locationbar").any { id.substringAfterLast('/').contains(it,ignoreCase=true) }
}
