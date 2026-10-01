package dev.miniscreenpipe

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

object EvidencePolicy {
    val STOP_WORDS = setOf(
        "what", "when", "where", "which", "today", "about",
        "history", "summarize", "reading", "worked", "this",
        "that", "with", "from", "were", "have", "the",
        "and", "did", "was", "find", "show", "tell", "can", "you", "me", "my", "please", "look", "looked", "saw", "seen", "read", "earlier", "yesterday", "last", "past", "week", "month", "day", "days", "seven", "evidence", "activity", "activities", "remember", "anything", "something", "summary", "apps", "app", "appeared", "screenshots", "screenshot", "moment", "moments"
    )

    fun normalize(text: String) = text.lines().map { it.trim().replace(Regex("[\\t ]+"), " ") }
        .filter { it.isNotBlank() }.joinToString("\n").take(60000)
    fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun tokens(text: String) = Regex("[\\p{L}\\p{N}_]+").findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()

    fun topicWords(question: String): Set<String> =
        tokens(question).filter { it.length >= 3 && it !in STOP_WORDS }.toSet()

    // Android FTS4 may omit enhanced syntax; adjacent quoted terms mean AND.
    fun ftsQuery(text: String) = tokens(text).take(20).joinToString(" ") { "\"$it*\"" }
    fun ftsQuery(words: Collection<String>, or: Boolean = false) =
        words.take(if (or) 8 else 20).joinToString(if (or) " OR " else " ") { "\"$it*\"" }

    // Coalescing must never bridge a pause, app switch, or long unobserved gap.
    fun canMerge(previous: Entry, time: Long, pkg: String, fingerprint: String, text: String, interval: Int) =
        previous.pkg == pkg && previous.fingerprint.isNotEmpty() && previous.fingerprint == fingerprint &&
            previous.text == text && java.time.Instant.ofEpochMilli(previous.time).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault()).toLocalDate() && time >= previous.lastSeen && time - previous.lastSeen <= interval * 1500L

    fun select(entries: List<Entry>, question: String, maxRecords: Int = RetrievalPolicy.MAX_OVERVIEW): List<Entry> {
        if(entries.isEmpty() || maxRecords<=0)return emptyList()
        val words=topicWords(question)
        if(words.isNotEmpty())return RetrievalPolicy.diverse(RetrievalPolicy.rank(entries,question),minOf(maxRecords,RetrievalPolicy.MAX_FOCUSED))
        val chronological=entries.distinctBy { it.id }.sortedBy { it.time }
        if(chronological.size<=maxRecords)return chronological
        val sampled=(0 until maxRecords).map { i -> chronological[if(maxRecords==1)0 else (i.toLong()*(chronological.size-1)/(maxRecords-1)).toInt()] }
        return sampled
    }
    private fun truncateUtf8(text:String,bytes:Int):String {
        if(text.toByteArray(Charsets.UTF_8).size<=bytes)return text
        var lo=0;var hi=text.length
        while(lo<hi) { val mid=(lo+hi+1)/2;if(text.substring(0,mid).toByteArray(Charsets.UTF_8).size<=bytes)lo=mid else hi=mid-1 }
        if(lo>0 && Character.isHighSurrogate(text[lo-1]))lo--
        return text.take(lo)
    }
    fun context(entries:List<Entry>,question:String="",preselected:Boolean=false,note:String=""):String {
        val selected=if(preselected)entries.distinctBy { it.id }.take(RetrievalPolicy.MAX_OVERVIEW) else select(entries,question)
        if(selected.isEmpty())return ""
        val date=SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.US)
        val lead="Partial retrieved evidence: ${selected.size} moments. ${note.take(600)} Gaps, omitted history and OCR errors are possible. This is not a complete activity log.\n\n"
        val budget=minOf(1800,maxOf(80,(RetrievalPolicy.MAX_CONTEXT_CHARS-lead.length-selected.size*220)/selected.size))
        val words=topicWords(question)
        val pieces=selected.map { e ->
            val range=if(e.lastSeen>e.time)"${date.format(e.time)} — ${date.format(e.lastSeen)}" else date.format(e.time)
            fun safe(s:String)=s.replace(Regex("[\\r\\n]")," ").replace(Regex("\\[(\\d+)\\]"),"($1)")
            val header="[${e.id}] $range | ${safe(e.app).take(60)} | ${e.samples} samples | source=${safe(e.source).take(30)} | quality=${safe(e.quality).take(30)}\n"
            val clean=e.text.replace(Regex("\\[(\\d+)\\]"),"($1)")
            // Fixed overlapping chunks prevent one enormous OCR paragraph from hiding an older match.
            val chunks=mutableListOf<Pair<Int,String>>()
            var start=0
            while(start<clean.length) { chunks.add(start to clean.substring(start,minOf(clean.length,start+600)));start+=480 }
            val ranked=chunks.map { (offset,text) ->
                val terms=tokens(text)
                Triple(offset,text,words.count { word -> terms.any { it.startsWith(word) } })
            }.sortedWith(compareByDescending<Triple<Int,String,Int>> { it.third }.thenBy { it.first })
            val chosen=ranked.take(maxOf(1,budget/600)).sortedBy { it.first }
            val excerpt=if(clean.length<=budget)clean else chosen.joinToString("\n…\n") { chunk ->
                val limit=maxOf(1,budget/maxOf(1,chosen.size))
                val hit=Regex("[\\p{L}\\p{N}_]+").findAll(chunk.second).firstOrNull { token -> words.any { token.value.startsWith(it,true) } }?.range?.first ?: 0
                val begin=if(chunk.second.length>limit)maxOf(0,hit-minOf(24,limit/4)) else 0
                chunk.second.substring(begin,minOf(chunk.second.length,begin+limit))
            }.take(budget)
            val byteAllowance=maxOf(0,(RetrievalPolicy.MAX_CONTEXT_BYTES-lead.toByteArray(Charsets.UTF_8).size)/selected.size-header.toByteArray(Charsets.UTF_8).size-24)
            val bounded=truncateUtf8(excerpt,byteAllowance)
            Triple(e.time,header+bounded+(if(bounded!=clean)"\n(text excerpt)" else "")+"\n\n",e.id)
        }
        // Budget by retrieval rank BEFORE chronological rendering, so the oldest low-rank hit cannot crowd out the best one.
        val included=mutableListOf<Triple<Long,String,Long>>()
        var chars=lead.length;var bytes=lead.toByteArray(Charsets.UTF_8).size
        for(piece in pieces) {
            val size=piece.second.toByteArray(Charsets.UTF_8).size
            if(chars+piece.second.length<=RetrievalPolicy.MAX_CONTEXT_CHARS && bytes+size<=RetrievalPolicy.MAX_CONTEXT_BYTES) { included.add(piece);chars+=piece.second.length;bytes+=size }
        }
        if(included.isEmpty())return ""
        return lead.replace("${selected.size} moments.","${included.size} moments.")+included.sortedBy { it.first }.joinToString("") { it.second }
    }
}
