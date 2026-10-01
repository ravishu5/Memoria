package dev.miniscreenpipe

import android.graphics.Bitmap
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.mlkit.vision.text.Text
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Test

class OcrRecoveryTest {
    @Test fun timeoutStoresIndependentFallbackAndReleasesOriginalOnlyWhenTaskFinishes() {
        val pending=TaskCompletionSource<Text>();val input=Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888)
        val result=OcrAwaiter.await(pending.task,input,"Accessible fixture",1,TimeUnit.MILLISECONDS)
        assertEquals("ocr-timeout",result.failure);assertNull(result.text)
        assertNotSame(input,result.image);assertFalse(input.isRecycled);assertFalse(result.image.isRecycled)
        result.image.recycle();assertFalse(input.isRecycled)
        pending.setException(IllegalStateException("synthetic OCR failure"));assertTrue(input.isRecycled)
    }
    @Test fun timeoutWithoutAccessibleTextKeepsTaskPixelsAliveUntilCompletion() {
        val pending=TaskCompletionSource<Text>();val input=Bitmap.createBitmap(40,40,Bitmap.Config.ARGB_8888)
        try { OcrAwaiter.await(pending.task,input,"",1,TimeUnit.MILLISECONDS);fail("Expected timeout") } catch(_:TimeoutException) { }
        assertFalse(input.isRecycled);pending.setException(IllegalStateException("fixture"));assertTrue(input.isRecycled)
    }
    @Test fun failedOcrReturnsAccessibleFallbackWithoutPrematureRecycle() {
        val pending=TaskCompletionSource<Text>();pending.setException(IllegalStateException("fixture"))
        val input=Bitmap.createBitmap(40,40,Bitmap.Config.ARGB_8888)
        val result=OcrAwaiter.await(pending.task,input,"Accessible text")
        assertSame(input,result.image);assertEquals("ocr-failed",result.failure);assertFalse(input.isRecycled);input.recycle()
    }
}
