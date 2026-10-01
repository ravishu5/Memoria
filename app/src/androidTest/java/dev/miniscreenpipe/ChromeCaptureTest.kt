package dev.miniscreenpipe

import android.app.UiAutomation
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real browser regression; never clears existing user history or changes their API key. */
@RunWith(AndroidJUnit4::class)
class ChromeCaptureTest {
    @Test fun normalChromeAndAnotherAppRecord() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val target=instrument.targetContext
        val automation=instrument.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        fun await(ms:Long, condition:()->Boolean):Boolean {
            val end=SystemClock.elapsedRealtime()+ms
            while(SystemClock.elapsedRealtime()<end) { if(condition())return true;SystemClock.sleep(250) }
            return condition()
        }
        val prefs=Prefs(target);val db=HistoryDb.get(target)
        val restoreRecording=prefs.recording || InstrumentationRegistry.getArguments().getString("resumeAfterTest")=="true"
        val before=db.search("",0,Long.MAX_VALUE,0).map { it.id }.toSet()
        val original=prefs.p.all.filterKeys { it in setOf("only_selected","interval","foreground") }
        val own="dev.miniscreenpipe/dev.miniscreenpipe.CaptureService"
        val existing=shell("settings get secure enabled_accessibility_services").trim().takeUnless { it=="null" }.orEmpty()
        val others=existing.split(':').filter { it.isNotBlank() && it !in setOf(own,"dev.miniscreenpipe/.CaptureService") }.joinToString(":")
        try {
            prefs.recording=false
            prefs.p.edit().putBoolean("only_selected",false).putInt("interval",10).commit()
            shell("pm grant dev.miniscreenpipe android.permission.POST_NOTIFICATIONS")
            shell(if(others.isBlank())"settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services $others")
            SystemClock.sleep(750)
            shell("settings put secure enabled_accessibility_services ${listOf(others,own).filter { it.isNotBlank() }.joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
            assertTrue("Service must bind",await(15000) { CaptureService.instance!=null })
            target.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://example.com")).setPackage("com.android.chrome").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SystemClock.sleep(5000)
            instrument.runOnMainSync { CaptureService.instance!!.resume() }
            assertTrue("Normal Chrome did not record: ${CaptureService.status}",await(30000) {
                db.search("Example Domain",0,Long.MAX_VALUE).any { it.id !in before && it.pkg=="com.android.chrome" && it.image.isNotBlank() }
            })
            println("CHROME_CAPTURE_OK: real Chrome screenshot and searchable OCR")
            instrument.runOnMainSync { CaptureService.instance!!.pause() }
            val fixture=instrument.context.packageName
            target.startActivity(Intent().setClassName(fixture,CaptureFixtureActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("hiddenPassword",true))
            SystemClock.sleep(2000)
            instrument.runOnMainSync { CaptureService.instance!!.resume() }
            assertTrue("Another app did not record: ${CaptureService.status}",await(20000) {
                db.search("Kotlin memory capture verification",0,Long.MAX_VALUE).any { it.id !in before && it.pkg==fixture }
            })
            println("OTHER_APP_CAPTURE_OK: ordinary screen with hidden password field")
            instrument.runOnMainSync { CaptureService.instance!!.onInterrupt() }
            assertTrue("Feedback interruption must not pause recording",prefs.recording)
            val activity=instrument.startActivitySync(Intent(target,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrument.runOnMainSync {
                val save=MainActivity::class.java.getDeclaredMethod("saveCapture",String::class.java,Int::class.javaPrimitiveType)
                save.isAccessible=true;save.invoke(activity,"interval",10)
            }
            assertTrue("Changing capture settings must retain the Start choice",prefs.recording)
            shell(if(others.isBlank())"settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services $others")
            assertTrue("Old service must disconnect",await(10000) { CaptureService.instance==null })
            assertTrue("Disconnect must preserve the Start choice",prefs.recording)
            shell("settings put secure enabled_accessibility_services ${listOf(others,own).filter { it.isNotBlank() }.joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
            assertTrue("Service must recover recording",await(15000) { CaptureService.instance!=null && prefs.recording })
            val resumedAt=System.currentTimeMillis()
            target.startActivity(Intent().setClassName(fixture,CaptureFixtureActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("hiddenPassword",true))
            assertTrue("Reconnected recorder must store another screenshot",await(20000) {
                db.search("Kotlin memory capture verification",resumedAt,Long.MAX_VALUE).any { it.pkg==fixture && it.image.isNotBlank() }
            })
            println("CAPTURE_RECOVERY_OK: feedback interruption, settings change, reconnection and subsequent screenshot")
            instrument.runOnMainSync { CaptureService.instance!!.pause() }
            target.startActivity(Intent().setClassName(fixture,CaptureFixtureActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("password",true))
            SystemClock.sleep(2000)
            val count=db.search("",0,Long.MAX_VALUE,0).size
            instrument.runOnMainSync { CaptureService.instance!!.resume() }
            SystemClock.sleep(4500)
            assertTrue("Visible password must be blocked",CaptureService.status.startsWith("Skipping password"))
            assertEquals("Visible password screen must not enter history",count,db.search("",0,Long.MAX_VALUE,0).size)
            println("PASSWORD_GUARD_OK: visible password still blocks recording")
            instrument.runOnMainSync { CaptureService.instance!!.pause() }
            shell(if(others.isBlank())"settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services $others")
            assertTrue("Paused service must disconnect",await(10000) { CaptureService.instance==null })
            shell("settings put secure enabled_accessibility_services ${listOf(others,own).filter { it.isNotBlank() }.joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
            assertTrue("Paused service must reconnect",await(15000) { CaptureService.instance!=null })
            assertFalse("Reconnection must also preserve explicit Pause",prefs.recording)
            println("PAUSE_RECOVERY_OK: explicit Pause survives reconnection")
        } finally {
            instrument.runOnMainSync { CaptureService.instance?.pause() }
            prefs.recording=false
            val edit=prefs.p.edit()
            for(name in setOf("only_selected","interval","foreground")) {
                when(val value=original[name]) { null->edit.remove(name);is Boolean->edit.putBoolean(name,value);is Int->edit.putInt(name,value) }
            }
            edit.commit()
            db.search("",0,Long.MAX_VALUE,0).filter { it.id !in before }.forEach { db.delete(it.id) }
            target.startActivity(Intent(target,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if(restoreRecording)instrument.runOnMainSync { CaptureService.instance?.resume() }
            assertEquals("Final recording choice must be restored",restoreRecording,prefs.recording)
            val saved=java.io.File(target.applicationInfo.dataDir,"shared_prefs/settings.xml").readText()
            assertTrue("Recording intent must be durable before instrumentation kills the process",saved.contains("name=\"recording\" value=\"$restoreRecording\""))
        }
    }
}
