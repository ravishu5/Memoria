package dev.miniscreenpipe

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.graphics.Bitmap
import java.io.File

@RunWith(AndroidJUnit4::class)
class HistoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun testSearchRetentionAndDeletion() {
        val db = HistoryDb.get(context)
        db.clear()
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val now = System.currentTimeMillis()
        db.add(now, "Browser", "test.browser", "Kotlin reading 100% helpful", bitmap, 30)
        db.add(now - 10 * 86400000L, "Notes", "test.notes", "old observation", bitmap, 30)
        assertEquals(1, db.search("Kotlin Browser", 0, Long.MAX_VALUE).size)
        assertEquals(1, db.search("100%", 0, Long.MAX_VALUE).size)
        assertEquals(0, db.search("' OR 1=1", 0, Long.MAX_VALUE).size)
        assertEquals(1, db.search("", now - 1000, now + 1000).size)
        db.prune(7)
        assertEquals(1, db.search("", 0, Long.MAX_VALUE).size)
        val entry = db.search("", 0, Long.MAX_VALUE).single()
        assertTrue(File(db.images, entry.image).exists())
        db.delete(entry.id)
        assertFalse(File(db.images, entry.image).exists())
        assertEquals(0, db.search("", 0, Long.MAX_VALUE).size)
        bitmap.recycle(); db.clear()
    }
    @Test fun testPrivacyAndEncryptedKey() {
        val prefs = Prefs(context)
        prefs.p.edit().clear().commit()
        assertFalse(prefs.recording)
        assertTrue(prefs.excluded("com.example.bank"))
        assertTrue(prefs.excluded("com.x.BITWARDEN"))
        assertTrue(prefs.excluded("dev.miniscreenpipe"))
        assertFalse(prefs.excluded("com.example.reader"))
        prefs.saveKey("secret-test-key")
        assertEquals("secret-test-key", prefs.key())
        assertFalse(prefs.p.getString("key", "")!!.contains("secret-test-key"))
        prefs.saveKey(""); assertEquals("", prefs.key())
    }
}
