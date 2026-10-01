package dev.miniscreenpipe

import org.junit.Assert.*
import org.junit.Test

class CapturePolicyTest {
    @Test fun allTriggersRespectGapAndQuietPeriods() {
        val p=CapturePolicy();assertEquals("idle",p.due(1000,true,30))
        p.attempt(1000);p.stored(1000)
        p.settle(1100,250);assertEquals(400L,p.delay(1350));assertNull(p.due(1749,true,30))
        assertEquals(0L,p.delay(1750));p.settle(1800,750);assertEquals(1L,p.delay(2549));assertEquals(0L,p.delay(2550))
    }
    @Test fun continuousContentEventsCannotStarveCapture() {
        val p=CapturePolicy();p.attempt(1000);p.stored(1000)
        p.settle(1800,1000);p.settle(2300,1000);p.settle(2800,1000)
        assertEquals(3300L,p.quietUntil);assertEquals(0L,p.delay(3300))
    }
    @Test fun failedIdleAttemptBacksOffInsteadOfHammeringEveryPoll() {
        val p=CapturePolicy();p.attempt(1000)
        assertNull(p.due(2000,true,30));assertEquals("idle",p.due(4000,true,30))
    }
    @Test fun visualChecksAndIdleFallbackAreIndependentOfLongUserInterval() {
        val p=CapturePolicy();p.attempt(1000);p.stored(1000)
        assertNull(p.due(3999,true,600));assertEquals("visual",p.due(4000,true,600))
        p.attempt(4000);assertNull(p.due(6999,true,600));assertEquals("idle",p.due(16000,true,600))
        assertNull(p.due(16000,false,600));assertEquals("idle",p.due(601000,false,600))
        p.reset();assertEquals("idle",p.due(601001,false,600))
    }
    @Test fun visualDiffIgnoresSmallNoiseButFindsChangedRegionsAndDimensions() {
        val original=IntArray(100) { 0xff111111.toInt() };val after=original.copyOf()
        after[0]=0xff222222.toInt();assertEquals(0.0,CapturePolicy.difference(original,after),0.0)
        for(i in 0..5)after[i]=0xffffffff.toInt()
        assertEquals(0.06,CapturePolicy.difference(original,after),0.0001)
        assertEquals(1.0,CapturePolicy.difference(original,IntArray(101)),0.0)
    }
    @Test fun domainBoundaryPreventsSubstringAndPageTitleFalseMatches() {
        assertTrue(TextPrivacy.blockedUrl("https://login.bank.example/pay","bank.example"))
        assertFalse(TextPrivacy.blockedUrl("https://bank.example.evil.com","bank.example"))
        assertFalse(TextPrivacy.blockedUrl("https://example.com/?next=bank.example","bank.example"))
        assertFalse(TextPrivacy.privateLabel("Article about private browsing"))
        assertTrue(TextPrivacy.privateLabel("You're incognito"))
        assertTrue(TextPrivacy.addressField("com.android.chrome:id/url_bar"))
    }
    @Test fun redactionPreservesEvidenceIdsDatesAndOrdinaryText() {
        val input="[42] 2026-10-01 12:00:00 Kotlin\nContact ravi@example.com or +91 98765 43210\nCard 4111 1111 1111 1111\nAIza"+"x".repeat(32)
        val output=TextPrivacy.redact(input)
        assertTrue(output.startsWith("[42] 2026-10-01 12:00:00 Kotlin"))
        assertFalse(output.contains("ravi@example.com"));assertFalse(output.contains("98765"));assertFalse(output.contains("4111"));assertFalse(output.contains("AIza"))
        assertEquals(output,TextPrivacy.redact(output))
    }
}
