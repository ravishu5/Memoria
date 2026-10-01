package dev.miniscreenpipe

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StorageV2Test {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun deduplicationSharedImagesSearchAndCap() {
        val name="v2-storage-test.db";context.deleteDatabase(name)
        val db=HistoryDb.isolated(context,name)
        val image=Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        try {
            val now=System.currentTimeMillis()
            val first=db.add(now,"Browser","browser","Kotlin coroutine 100%",image,30,"blue")
            assertEquals(first,db.add(now+10000,"Browser","browser","Kotlin coroutine 100%",image,30,"blue"))
            assertEquals(1,db.search("",0,Long.MAX_VALUE).size)
            assertEquals(2,db.getEntry(first)!!.samples)
            assertEquals(1,db.images.listFiles()!!.size)
            assertEquals(first,db.search("Kotl coro",0,Long.MAX_VALUE).single().id)
            assertEquals(0,db.search("' OR 1=1",0,Long.MAX_VALUE).size)
            db.breakSession()
            val second=db.add(now+12000,"Browser","browser","Kotlin coroutine 100%",image,30,"blue")
            assertNotEquals(first,second);assertEquals(1,db.images.listFiles()!!.size)
            val file=File(db.images,db.getEntry(second)!!.image)
            val bytes=file.length();assertEquals(bytes,db.imageBytes())
            db.delete(first);assertTrue(file.exists())
            db.delete(second);assertFalse(file.exists())
            db.add(now,"App","app","Keep test",image,30,"blue");db.prune(7,0)
            assertEquals(0L,db.imageBytes());assertEquals(0,db.images.listFiles()!!.size)
            assertEquals(1,db.search("Keep test",0,Long.MAX_VALUE).size)
            assertTrue(db.search("Keep test",0,Long.MAX_VALUE).single().image.isBlank())
        } finally { db.clear();db.close();image.recycle();context.deleteDatabase(name);db.images.deleteRecursively() }
    }
    @Test fun versionOneHistoryMigratesWithoutLosingTextOrImages() {
        val name="migration-test.db";context.deleteDatabase(name)
        val folder=File(context.filesDir,"test-screens-$name").apply { mkdirs() }
        val bitmap=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888)
        val file=File(folder,"legacy.jpg");file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,65,it) };bitmap.recycle()
        val now=System.currentTimeMillis()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null).use { old ->
            old.execSQL("CREATE TABLE captures(id INTEGER PRIMARY KEY,time INTEGER NOT NULL,app TEXT NOT NULL,package TEXT NOT NULL,text TEXT NOT NULL,image TEXT NOT NULL,interval INTEGER NOT NULL)")
            old.execSQL("INSERT INTO captures VALUES(1,?,'Legacy Browser','legacy.browser','Historic Kotlin evidence','legacy.jpg',30)",arrayOf(now))
            old.version=1
        }
        val db=HistoryDb.isolated(context,name)
        try {
            val record=db.search("Historic Kotlin",0,Long.MAX_VALUE).single()
            assertEquals(1L,record.id);assertEquals(now,record.lastSeen);assertEquals(1,record.samples)
            assertTrue(file.exists());assertEquals(file.length(),db.imageBytes())
        } finally { db.clear();db.close();context.deleteDatabase(name);folder.deleteRecursively() }
    }
}
