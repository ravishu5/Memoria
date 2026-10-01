package dev.miniscreenpipe

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.*
import kotlin.math.ln

/** Offline ranking using FTS4 index statistics; no history is sent for retrieval. */
object RetrievalPolicy {
    const val MAX_CANDIDATES=128
    const val MAX_FOCUSED=24
    const val MAX_OVERVIEW=48
    const val MAX_CONTEXT_CHARS=18000
    const val MAX_CONTEXT_BYTES=24000
    data class Plan(val start:Long,val end:Long,val words:List<String>,val overview:Boolean,val scope:String)
    fun plan(question:String,start:Long,end:Long,now:Instant=Instant.now(),zone:ZoneId=ZoneId.systemDefault()):Plan {
        val q=question.lowercase(java.util.Locale.ROOT);val today=now.atZone(zone).toLocalDate()
        val iso=Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b").find(q)?.value?.let { try { LocalDate.parse(it) } catch(_:Exception) { null } }
        val range=when {
            iso!=null -> iso to iso.plusDays(1)
            Regex("\\byesterday\\b").containsMatchIn(q) -> today.minusDays(1) to today
            Regex("\\b(today|my day)\\b").containsMatchIn(q) -> today to today.plusDays(1)
            Regex("\\b(last|past) (7|seven) days\\b").containsMatchIn(q) -> today.minusDays(6) to today.plusDays(1)
            Regex("\\blast week\\b").containsMatchIn(q) -> today.with(java.time.DayOfWeek.MONDAY).minusWeeks(1) to today.with(java.time.DayOfWeek.MONDAY)
            Regex("\\bthis week\\b").containsMatchIn(q) -> today.with(java.time.DayOfWeek.MONDAY) to today.plusDays(1)
            Regex("\\blast month\\b").containsMatchIn(q) -> today.withDayOfMonth(1).minusMonths(1) to today.withDayOfMonth(1)
            Regex("\\bthis month\\b").containsMatchIn(q) -> today.withDayOfMonth(1) to today.plusDays(1)
            else -> null
        }
        val from=maxOf(start,range?.first?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: start)
        val until=minOf(end,range?.second?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: end)
        val topic=if(iso!=null)q.replace(iso.toString(),"") else q
        val words=EvidencePolicy.topicWords(topic).take(8)
        val scope=if(from>=until)"Empty date intersection" else if(range!=null)"${range.first} to ${range.second} (end exclusive), $zone; intersected with selected scope" else "Selected date scope; $zone"
        return Plan(from,until,words,words.isEmpty(),scope)
    }
    /** 'pcnalx' unsigned native-endian matchinfo. Weighted BM25 per indexed field. */
    fun bm25(blob:ByteArray):Double {
        require(blob.size%4==0 && blob.size>=12) { "Invalid FTS statistics" }
        val b=ByteBuffer.wrap(blob).order(ByteOrder.nativeOrder())
        val v=LongArray(blob.size/4) { b.int.toLong() and 0xffffffffL }
        val phrases=v[0].toInt();val cols=v[1].toInt();val n=v[2].toDouble()
        require(phrases in 1..20 && cols==3 && v.size==3+cols*2+3*cols*phrases) { "Unexpected FTS statistics" }
        var score=0.0
        for(p in 0 until phrases)for(c in 0 until cols) {
            val offset=3+cols*2+3*(p*cols+c)
            val tf=v[offset].toDouble();if(tf==0.0)continue
            val df=v[offset+2].toDouble().coerceIn(0.0,n)
            val avg=maxOf(1.0,v[3+c].toDouble());val length=v[3+cols+c].toDouble()
            val idf=ln(1.0+(n-df+0.5)/(df+0.5))
            val weight=when(c) { 0 -> 2.0;1 -> 0.35;else -> 1.0 }
            score+=weight*idf*tf*2.2/(tf+1.2*(0.25+0.75*length/avg))
        }
        return score
    }
    // Pure in-memory fallback for callers supplying observations directly, e.g. the single-moment viewer.
    fun rank(entries:List<Entry>,question:String):List<Entry> {
        val words=EvidencePolicy.topicWords(question).take(8)
        if(words.isEmpty())return entries
        val docs=entries.map { EvidencePolicy.tokens(it.app+" "+it.text) }
        val avg=docs.map { it.size }.average().coerceAtLeast(1.0)
        val df=words.associateWith { word -> docs.count { doc -> doc.any { it.startsWith(word) } } }
        return entries.indices.map { i ->
            val tokens=docs[i];val score=words.sumOf { word ->
                val tf=tokens.count { it.startsWith(word) }.toDouble()
                val freq=df.getValue(word).toDouble()
                ln(1+(entries.size-freq+0.5)/(freq+0.5))*tf*2.2/(tf+1.2*(0.25+0.75*tokens.size/avg))
            };entries[i] to score
        }.filter { it.second>0 }.sortedWith(compareByDescending<Pair<Entry,Double>> { it.second }.thenByDescending { it.first.time }).map { it.first }
    }
    fun diverse(entries:List<Entry>,max:Int):List<Entry> {
        val signatures=hashSetOf<String>()
        return entries.distinctBy { it.id }.filter { e ->
            // Keep repeated visits in different half-hour windows as temporal evidence.
            val window=e.time/(30*60*1000L)
            signatures.add("${e.pkg}:$window:${EvidencePolicy.hash(EvidencePolicy.normalize(e.text).toByteArray())}")
        }.take(max)
    }
    fun fit(text:String)=text.length<=MAX_CONTEXT_CHARS && text.toByteArray(Charsets.UTF_8).size<=MAX_CONTEXT_BYTES
}
