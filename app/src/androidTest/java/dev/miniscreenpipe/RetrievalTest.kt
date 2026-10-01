package dev.miniscreenpipe

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Uses an isolated database; safe to select explicitly on a real phone. */
class RetrievalTest {
    @Test fun ftsBm25FindsOldRareEvidenceBeforeRecentNoiseAndHonorsFilters() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="retrieval-fixture.db";context.deleteDatabase(name);val db=HistoryDb.isolated(context,name)
        val now=System.currentTimeMillis();val base=now-3*86400000L
        try {
            val sql=db.writableDatabase;sql.beginTransaction()
            try {
                repeat(800) { i ->
                    sql.insertOrThrow("captures",null,ContentValues().apply {
                        put("id",i+1);put("time",base+i*1000L);put("last_seen",base+i*1000L);put("app","Test reader");put("package","test.reader")
                        put("text",if(i==16)"Kotlin coroutine quantum entanglement investigation" else "Kotlin common UI "+"irrelevant filler ".repeat(160))
                        put("image","");put("interval",10)
                    })
                };sql.setTransactionSuccessful()
            } finally { sql.endTransaction() }
            val found=db.retrieve("",0,Long.MAX_VALUE,"What was I reading about Kotlin quantum?")
            assertEquals(17L,found.entries.first().id);assertTrue(found.entries.size<=24)
            assertTrue(found.note.contains("800 matching moments"))
            assertTrue(db.retrieve("",0,Long.MAX_VALUE,"unrecorded astronomy topic").entries.isEmpty())
            assertEquals(listOf(17L),db.retrieve("quantum",0,Long.MAX_VALUE,"Kotlin").entries.map { it.id })
            assertTrue(db.retrieve("",base+400000,Long.MAX_VALUE,"quantum").entries.isEmpty())
            assertTrue(db.retrieve("",0,Long.MAX_VALUE,"quantum yesterday").entries.isEmpty())
            val overview=db.retrieve("",0,Long.MAX_VALUE,"Summarize my history")
            assertTrue(overview.entries.size<=48);assertEquals(1L,overview.entries.first().id);assertEquals(800L,overview.entries.last().id)
            assertTrue(RetrievalPolicy.fit(EvidencePolicy.context(found.entries,"Kotlin quantum",true,found.note)))
        } finally { db.clear();db.close();context.deleteDatabase(name);db.images.deleteRecursively() }
    }
    @Test fun modelPickerMigrationAndSaveUseOnePreference() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val wrapped=object:android.content.ContextWrapper(context) { override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("model-fixture",mode) }
        val p=wrapped.getSharedPreferences("settings",0)
        try {
            p.edit().clear().putString("model",Prefs.FLASH_LITE_MODELS.first()).putString("gemini_model",Prefs.FLASH_LITE_MODELS.last()).commit()
            val prefs=Prefs(wrapped);assertEquals(Prefs.FLASH_LITE_MODELS.last(),prefs.model);assertFalse(p.contains("gemini_model"))
            prefs.saveModel(Prefs.FLASH_LITE_MODELS.first());assertEquals(Prefs.FLASH_LITE_MODELS.first(),Prefs(wrapped).model)
        } finally { p.edit().clear().commit() }
    }
}
