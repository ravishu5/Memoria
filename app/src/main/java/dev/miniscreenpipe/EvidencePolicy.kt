package dev.miniscreenpipe

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

object EvidencePolicy {
    fun normalize(text: String) = text.lines().map { it.trim().replace(Regex("[\\t ]+"), " ") }
        .filter { it.isNotBlank() }.joinToString("\n").take(60000)
    fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun tokens(text: String) = Regex("[\\p{L}\\p{N}_]+").findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()
    // Android FTS4 may omit enhanced syntax; adjacent quoted terms mean AND.
    fun ftsQuery(text: String) = tokens(text).take(20).joinToString(" ") { "\"$it*\"" }
    // Coalescing must never bridge a pause, app switch, or long unobserved gap.
    fun canMerge(previous: Entry, time: Long, pkg: String, fingerprint: String, text: String, interval: Int) =
        previous.pkg == pkg && previous.fingerprint.isNotEmpty() && previous.fingerprint == fingerprint &&
            previous.text == text && java.time.Instant.ofEpochMilli(previous.time).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault()).toLocalDate() && time >= previous.lastSeen && time - previous.lastSeen <= interval * 1500L

    fun select(entries: List<Entry>, question: String, maxRecords: Int = 160): List<Entry> {
        if(entries.isEmpty()) return emptyList()
        val words = tokens(question).filter { it.length >= 3 && it !in setOf("what", "when", "where", "which", "today", "about", "history", "summarize", "reading", "worked", "this", "that", "with", "from", "were", "have", "the", "and", "did", "was") }.toSet()
        fun score(e: Entry): Int { val text = (e.app + " " + e.text).lowercase(Locale.ROOT); return words.count { text.contains(it) } }
        val ranked = entries.sortedWith(compareByDescending<Entry> { score(it) }.thenByDescending { it.time })
        // Preserve day coverage as well as relevant observations: sampling the whole range avoids recency-only summaries.
        val selected = linkedMapOf<Long, Entry>()
        ranked.take(if(words.isEmpty()) 0 else maxRecords / 2).forEach { selected[it.id] = it }
        val chronological = entries.sortedBy { it.time }
        val slots = maxRecords - selected.size
        repeat(minOf(slots, chronological.size)) { i -> val index = if(slots <= 1) 0 else (i.toLong() * (chronological.size - 1) / (slots - 1)).toInt(); val e = chronological[index]; selected[e.id] = e }
        ranked.forEach { if (selected.size < maxRecords) selected[it.id] = it }
        return selected.values.sortedByDescending { it.time }
    }
    fun context(entries: List<Entry>, question: String = ""): String {
        val selected = select(entries, question)
        if(selected.isEmpty()) return ""
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
        val chronological = selected.sortedBy { it.time }
        val lead = "Partial sampled evidence: ${selected.size} selected from ${entries.size} candidate moments. Gaps and OCR errors are possible.\n\n"
        val headers = chronological.map { e -> "[${e.id}] ${date.format(e.time)} — ${date.format(e.lastSeen)} | ${e.app.replace(Regex("[\\r\\n]"), " ").take(60)} | ${e.samples} samples | source=${e.source.take(30)}\n" }
        val budgetPerRecord = minOf(2400, maxOf(40, (40000 - lead.length - headers.sumOf { it.length } - chronological.size * 20) / chronological.size))
        val pieces = chronological.mapIndexed { index, e ->
            val clean = e.text.replace(Regex("\\[(\\d+)\\]"), "($1)")
            val words = tokens(question).filter { it.length >= 3 }
            val lines = clean.lines()
            val relevant = lines.filter { line -> words.any { line.contains(it, true) } }
            val excerpt = if (clean.length <= budgetPerRecord || relevant.isEmpty()) clean.take(budgetPerRecord) else
                (relevant.joinToString("\n").take(budgetPerRecord * 2 / 3) + "\n…\n" + clean.take(budgetPerRecord / 3)).take(budgetPerRecord)
            headers[index] + excerpt + (if(excerpt.length < clean.length) "\n(text excerpt)" else "") + "\n\n"
        }
        return buildString {
            append(lead)
            for(piece in pieces) if(length + piece.length <= 40000) append(piece)
        }
    }
}
