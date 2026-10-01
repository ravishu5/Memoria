package dev.miniscreenpipe

import android.app.NotificationManager
import android.app.UiAutomation
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit phone diagnostic: keeps the current browser tab and the requested capture. */
@RunWith(AndroidJUnit4::class)
class CurrentPageCaptureTest {
    @Test fun captureCurrentlyOpenChromePage() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val target=instrument.targetContext
        val automation=instrument.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        fun await(ms:Long,condition:()->Boolean):Boolean {
            val end=SystemClock.elapsedRealtime()+ms
            while(SystemClock.elapsedRealtime()<end) { if(condition())return true;SystemClock.sleep(250) };return condition()
        }
        val prefs=Prefs(target);val db=HistoryDb.get(target)
        println("CURRENT_PAGE_DIAGNOSTIC: requested=${prefs.recording}, notifications=${target.getSystemService(NotificationManager::class.java).areNotificationsEnabled()}")
        val own="dev.miniscreenpipe/dev.miniscreenpipe.CaptureService"
        val existing=shell("settings get secure enabled_accessibility_services").trim().takeUnless { it=="null" }.orEmpty()
        val others=existing.split(':').filter { it.isNotBlank() && it !in setOf(own,"dev.miniscreenpipe/.CaptureService") }.joinToString(":")
        shell(if(others.isBlank())"settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services $others")
        SystemClock.sleep(750)
        shell("settings put secure enabled_accessibility_services ${listOf(others,own).filter { it.isNotBlank() }.joinToString(":")}")
        shell("settings put secure accessibility_enabled 1")
        assertTrue("Service must bind",await(15000) { CaptureService.instance!=null })
        // Instrumentation can put the launcher in front; resume Chrome's existing
        // task without passing a URL or opening/replacing a tab.
        target.startActivity(target.packageManager.getLaunchIntentForPackage("com.android.chrome")!!)
        assertTrue("Chrome must be visible",await(10000) { CaptureService.instance?.rootInActiveWindow?.packageName?.toString()=="com.android.chrome" })
        SystemClock.sleep(1000)
        instrument.runOnMainSync {
            val root=CaptureService.instance!!.rootInActiveWindow
            assertEquals("Keep the requested Chrome page visible","com.android.chrome",root?.packageName?.toString())
            val queue=java.util.ArrayDeque<Pair<AccessibilityNodeInfo,Int>>()
            queue.add(root!! to 0)
            var count=0;var depth=0;var visiblePasswords=0;var privateMarkers=0
            while(queue.isNotEmpty() && count<10000) {
                val (node,d)=queue.removeFirst();count++;depth=maxOf(depth,d)
                if(node.isVisibleToUser) {
                    if(node.isPassword)visiblePasswords++
                    if(TextPrivacy.privateLabel(node.text?.toString().orEmpty()) || TextPrivacy.privateLabel(node.contentDescription?.toString().orEmpty()))privateMarkers++
                }
                for(i in 0 until node.childCount)node.getChild(i)?.let { queue.add(it to d+1) }
            }
            println("CURRENT_PAGE_TREE: nodes=$count, depth=$depth, visiblePasswords=$visiblePasswords, privateMarkers=$privateMarkers")
            CaptureService.instance!!.resume()
        }
        val start=System.currentTimeMillis()
        assertTrue("Current Chrome page did not capture: ${CaptureService.status}",await(35000) {
            db.search("",start,Long.MAX_VALUE,0).any { it.pkg=="com.android.chrome" && it.text.isNotBlank() && it.image.isNotBlank() && java.io.File(db.images,it.image).length()>0 }
        })
        println("CURRENT_PAGE_CAPTURE_OK: ${CaptureService.status}")
        assertTrue("Recording must stay enabled",prefs.recording)
        // No navigation, data deletion or key changes. The user requested this page's recording.
    }
}
