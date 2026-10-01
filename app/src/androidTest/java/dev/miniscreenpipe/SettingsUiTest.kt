package dev.miniscreenpipe

import android.app.UiAutomation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** UI navigation check for a disposable emulator; never selected on the user's phone. */
@RunWith(AndroidJUnit4::class)
class SettingsUiTest {
    @Test fun topQuestionUsesPastHistoryAndGeminiOpensKeyPage() {
        org.junit.Assume.assumeTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val automation=instrument.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val db=HistoryDb.get(context);val image=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888).apply {
            val canvas=android.graphics.Canvas(this);canvas.drawColor(android.graphics.Color.rgb(247,248,253))
            val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=android.graphics.Color.rgb(99,102,241);textSize=24f }
            canvas.drawText("ASTRONOMY JOURNAL",35f,70f,paint);paint.color=android.graphics.Color.rgb(23,30,45);paint.textSize=38f;canvas.drawText("A universe of memories",35f,150f,paint)
            paint.color=android.graphics.Color.rgb(217,214,255);canvas.drawRoundRect(35f,210f,565f,470f,18f,18f,paint)
            paint.color=android.graphics.Color.rgb(61,59,120);canvas.drawCircle(295f,330f,78f,paint)
            paint.color=android.graphics.Color.rgb(105,115,137);paint.textSize=24f
            for(i in 0..6)canvas.drawText("An article about stars and distant galaxies.",35f,540f+i*40,paint)
        }
        val id=db.add(System.currentTimeMillis()-2*86400000L,"Synthetic reader","test.reader","Unique astronomy article remembered yesterday",image,10)
        val activity=instrument.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun click(label:String) { instrument.runOnMainSync { val target=views(activity.window.decorView).first { (it is TextView && it.text.toString()==label) || it.contentDescription?.toString()==label };var v:View=target;while(!v.isClickable)v=v.parent as View;assertTrue(v.performClick()) } }
        fun waitFor(label:String):Boolean { val end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end) { if(automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)?.isNotEmpty()==true)return true;SystemClock.sleep(200) };return false }
        try {
            instrument.waitForIdleSync()
            instrument.runOnMainSync { views(activity.window.decorView).filterIsInstance<EditText>().first { it.hint.toString().contains("Search your memories") }.setText("What astronomy article did I read?") }
            click("Search with Gemini")
            assertTrue("Natural-language search must open evidence preview",waitFor("Send selected evidence to Gemini?"))
            assertTrue("All retained scope must include earlier-day evidence",waitFor("[$id]"))
            val cancel=automation.rootInActiveWindow.findAccessibilityNodeInfosByText("Cancel").first();cancel.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            SystemClock.sleep(300);click("Settings");click("Gemini")
            assertTrue(waitFor("Get from AI Studio"))
            val filter=IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("https");addDataAuthority("aistudio.google.com",null);addDataPath("/app/apikey",android.os.PatternMatcher.PATTERN_LITERAL) }
            val monitor=instrument.addMonitor(filter,null,true)
            try { click("Get from AI Studio ↗");assertEquals("AI Studio must open the API-key page",1,monitor.hits) } finally { instrument.removeMonitor(monitor) }
            // Capture an emulator-only preview for layout review; production always keeps FLAG_SECURE.
            instrument.runOnMainSync { activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
            fun screenshot(name:String) { SystemClock.sleep(500);automation.takeScreenshot().let { bitmap -> java.io.File(context.cacheDir,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() } }
            screenshot("gemini-ui-test.png");click("Back");screenshot("settings-ui-test.png");click("Home");screenshot("home-ui-test.png");click("Search");screenshot("search-ui-test.png");click("Timeline")
            instrument.runOnMainSync { views(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString()=="All" }.performClick() }
            SystemClock.sleep(1000);screenshot("timeline-ui-test.png")
            instrument.runOnMainSync { views(activity.window.decorView).first { it.contentDescription?.toString()=="Open moment #$id" }.performClick() }
            SystemClock.sleep(500);screenshot("viewer-ui-test.png")
            automation.executeShellCommand("input keyevent KEYCODE_BACK").close()
            assertTrue("Back from a memory must restore the timeline",waitFor("Timeline"))
            click("Search")
            instrument.runOnMainSync {
                // Synthetic response fixture for visual review; no key or network request needed.
                MainActivity::class.java.getDeclaredField("answer").apply { isAccessible=true }.set(activity,"Astronomy reading\n\n• You viewed an article about stars and distant galaxies. [$id]\n\nMissing evidence / limitations\n• This observation does not establish how long you read it.")
                MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible=true }.invoke(activity)
            }
            screenshot("answer-ui-test.png")
            instrument.runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
        } finally { db.delete(id);image.recycle() }
    }
    @Test fun privacyGateDoesNotRenderHistoryBeforeAuthentication() {
        org.junit.Assume.assumeTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext;val prefs=Prefs(context);val original=prefs.privacy
        prefs.p.edit().putBoolean("privacy",true).commit()
        var activity:MainActivity?=null
        try {
            activity=instrument.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            instrument.waitForIdleSync()
            instrument.runOnMainSync {
                fun labels(view:View):List<String> = (if(view is TextView)listOf(view.text.toString()) else emptyList())+if(view is ViewGroup)(0 until view.childCount).flatMap { labels(view.getChildAt(it)) } else emptyList()
                val visible=labels(activity!!.window.decorView)
                assertTrue(visible.contains("History is locked"));assertFalse(visible.contains("Mini Screenpipe"))
            }
        } finally { prefs.p.edit().putBoolean("privacy",original).commit();instrument.runOnMainSync { activity?.finish() } }
    }

}
