package dev.miniscreenpipe

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.*

class RetrievalPolicyTest {
    private fun stats(tf:Int,len:Int,df:Int):ByteArray {
        // p,c,n; average lengths; row lengths; 3 triples (app/package/text).
        val values=intArrayOf(1,3,1000,2,3,100,2,3,len,0,0,0,0,0,0,tf,tf*df,df)
        return ByteBuffer.allocate(values.size*4).order(ByteOrder.nativeOrder()).apply { values.forEach { putInt(it) } }.array()
    }
    @Test fun bm25RewardsRareTermsAndNormalizesLengthWithoutLinearKeywordSpam() {
        assertTrue(RetrievalPolicy.bm25(stats(1,40,2))>RetrievalPolicy.bm25(stats(1,40,900)))
        assertTrue(RetrievalPolicy.bm25(stats(1,40,2))>RetrievalPolicy.bm25(stats(1,1000,2)))
        assertTrue(RetrievalPolicy.bm25(stats(10,100,2))<RetrievalPolicy.bm25(stats(1,100,2))*3)
    }
    @Test fun malformedStatisticsFailClosed() {
        try { RetrievalPolicy.bm25(byteArrayOf(1));fail("Invalid index statistics") } catch(_:IllegalArgumentException) { }
    }
    @Test fun yesterdayUsesLocalCalendarBoundariesAcrossDstAndIntersectsChosenScope() {
        val zone=ZoneId.of("America/New_York");val now=Instant.parse("2026-03-09T16:00:00Z")
        val p=RetrievalPolicy.plan("What was I reading yesterday?",0,Long.MAX_VALUE,now,zone)
        assertTrue(p.overview);assertEquals(23*3600000L,p.end-p.start)
        assertEquals(LocalDate.of(2026,3,8),Instant.ofEpochMilli(p.start).atZone(zone).toLocalDate())
        val impossible=RetrievalPolicy.plan("yesterday",p.end,p.end+86400000,now,zone)
        assertTrue(impossible.start>=impossible.end)
    }
    @Test fun isoDateDoesNotBecomeATopicAndInvalidDateIsNotSilentlyApplied() {
        val now=Instant.parse("2026-10-01T12:00:00Z")
        val p=RetrievalPolicy.plan("Kotlin on 2026-09-15",0,Long.MAX_VALUE,now,ZoneOffset.UTC)
        assertEquals(listOf("kotlin"),p.words);assertEquals(86400000L,p.end-p.start)
        val invalid=RetrievalPolicy.plan("Kotlin on 2026-99-99",0,Long.MAX_VALUE,now,ZoneOffset.UTC)
        assertEquals(0L,invalid.start);assertEquals(Long.MAX_VALUE,invalid.end)
    }
    @Test fun focusedContextRejectsUnrelatedScreensAndFindsMatchBeyondFirstParagraph() {
        fun entry(id:Long,text:String)=Entry(id,id*1000,"Reader","test.reader",text,"",10)
        val rows=listOf(entry(1,"unrelated sports scores"),entry(2,"filler ".repeat(4000)+"quantum entanglement paper"))
        val context=Gemini.context(rows,"Find quantum entanglement")
        assertEquals(setOf(2L),Gemini.evidenceIds(context));assertTrue(context.contains("quantum entanglement"))
        assertEquals("",Gemini.context(rows,"astronomy"))
    }
    @Test fun chunkCroppingKeepsTheMatchingWordUnderTightRecordBudget() {
        val rows=(1L..24).map { Entry(it,it*3600000,"Reader","test.reader","x".repeat(540)+" quantum discovery $it "+"filler ".repeat(1000),"",10) }
        val ctx=Gemini.context(rows,"quantum")
        assertEquals(24,Gemini.evidenceIds(ctx).size);assertEquals(24,Regex("quantum").findAll(ctx).count())
    }
    @Test fun explicitlySelectedMomentDoesNotRequireMatchingTheQuestionOrNumericId() {
        val row=Entry(123,1000,"Reader","test.reader","Some stored text","",10)
        val ctx=EvidencePolicy.context(listOf(row),"What can you tell me about moment #123?",true)
        assertEquals(setOf(123L),Gemini.evidenceIds(ctx))
    }
    @Test fun repeatedVisitsInDifferentTimeWindowsKeepTheirEvidence() {
        val rows=listOf(Entry(1,1000,"Reader","test.reader","quantum paper","",10),Entry(2,3601000,"Reader","test.reader","quantum paper","",10))
        assertEquals(setOf(1L,2L),Gemini.evidenceIds(Gemini.context(rows,"quantum")))
    }
    @Test fun unicodeAndDuplicateHistoryStayWithinBothContextBudgets() {
        val rows=(1L..200).map { Entry(it,it*86400000,"Reader","test.reader","नमस्ते ".repeat(9000),"",10) }
        val ctx=Gemini.context(rows)
        assertTrue(RetrievalPolicy.fit(ctx));assertEquals(48,Gemini.evidenceIds(ctx).size);assertTrue(ctx.contains("[1]"));assertTrue(ctx.contains("[200]"))
        val duplicates=(1L..50).map { Entry(it,it*1000,"Reader","test.reader","quantum paper","",10) }
        assertEquals(1,Gemini.evidenceIds(Gemini.context(duplicates,"quantum")).size)
    }
}
