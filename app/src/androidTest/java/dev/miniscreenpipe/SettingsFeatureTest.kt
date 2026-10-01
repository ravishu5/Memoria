package dev.miniscreenpipe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class SettingsFeatureTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun imageModesPreserveOriginalAndScaleShortEdge() {
        val original=Bitmap.createBitmap(600,1200,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            val grayBytes=ImageStorage.encode(original,480,true);val gray=BitmapFactory.decodeByteArray(grayBytes,0,grayBytes.size)
            try { assertEquals(480,gray.width);assertEquals(960,gray.height);val pixel=gray.getPixel(100,100);assertTrue(kotlin.math.abs(Color.red(pixel)-Color.green(pixel))<=3);assertTrue(kotlin.math.abs(Color.green(pixel)-Color.blue(pixel))<=3) } finally { gray.recycle() }
            assertEquals(Color.RED,original.getPixel(100,100));assertFalse(original.isRecycled)
            val encoded=ImageStorage.encode(original,0,false);val color=BitmapFactory.decodeByteArray(encoded,0,encoded.size)
            try { assertEquals(600,color.width);assertEquals(1200,color.height);assertTrue(Color.red(color.getPixel(100,100))>Color.green(color.getPixel(100,100))+100) } finally { color.recycle() }
            val small=Bitmap.createBitmap(40,80,Bitmap.Config.ARGB_8888)
            try { val bytes=ImageStorage.encode(small,1080,true);val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size);assertEquals(40,decoded.width);decoded.recycle() } finally { small.recycle() }
        } finally { original.recycle() }
    }
    @Test fun neverDeleteAndZipKeepTextImageMappings() {
        val name="settings-export-test.db";context.deleteDatabase(name);val db=HistoryDb.isolated(context,name)
        val image=Bitmap.createBitmap(20,40,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) };val zip=File(context.cacheDir,"test-screens.zip")
        try {
            val old=System.currentTimeMillis()-40*86400000L
            db.add(old,"Test app","test.app","Old searchable history",image,30)
            db.prune(0);assertEquals(1,db.summary("",0,Long.MAX_VALUE).count)
            db.exportZip(zip)
            ZipFile(zip).use { archive ->
                val history=org.json.JSONObject(archive.getInputStream(archive.getEntry("history.json")).bufferedReader().readText())
                val record=history.getJSONArray("captures").getJSONObject(0)
                assertEquals("Old searchable history",record.getString("text"));assertNotNull(archive.getEntry("screenshots/"+record.getString("image")))
            }
            db.prune(30);assertEquals(0,db.summary("",0,Long.MAX_VALUE).count)
        } finally { db.clear();db.close();context.deleteDatabase(name);db.images.deleteRecursively();image.recycle();zip.delete() }
    }
    @Test fun appSelectionCannotOverridePrivateExclusions() {
        // Separate preference file, so this test is safe to select on the user's phone.
        val wrapped=object:android.content.ContextWrapper(context) { override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("isolated-selection-test",mode) }
        val prefs=Prefs(wrapped)
        try {
            prefs.p.edit().clear().putBoolean("only_selected",true).putStringSet("selected_apps",setOf("test.reader","test.bank")).commit()
            assertFalse(prefs.excluded("test.reader"));assertTrue(prefs.excluded("test.other"));assertTrue(prefs.excluded("test.bank"));assertTrue(prefs.excluded("dev.miniscreenpipe"))
            prefs.p.edit().putStringSet("selected_apps",emptySet()).commit();assertTrue(prefs.excluded("test.reader"))
        } finally { prefs.p.edit().clear().commit() }
    }
}
