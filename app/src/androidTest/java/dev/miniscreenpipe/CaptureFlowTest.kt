package dev.miniscreenpipe

import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureFlowTest {
    @Test fun captureOcrPauseExclusionAndPasswordScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String): String = automation.executeShellCommand(command).use { fd -> java.io.FileInputStream(fd.fileDescriptor).bufferedReader().use { it.readText() } }
        fun waitUntil(ms: Long, condition: () -> Boolean): Boolean {
            val end = SystemClock.elapsedRealtime() + ms
            while(SystemClock.elapsedRealtime() < end) { if(condition()) return true; SystemClock.sleep(250) }
            return condition()
        }
        val originalServices = shell("settings get secure enabled_accessibility_services").trim()
        val originalEnabled = shell("settings get secure accessibility_enabled").trim()
        val prefs = Prefs(target); val db = HistoryDb.get(target)
        try {
            db.clear(); prefs.p.edit().clear().putInt("interval", 10).putBoolean("consent", true).commit()
            shell("pm grant dev.miniscreenpipe android.permission.POST_NOTIFICATIONS")
            shell("settings put secure enabled_accessibility_services dev.miniscreenpipe/dev.miniscreenpipe.CaptureService")
            shell("settings put secure accessibility_enabled 1")
            assertTrue("Capture service should connect", waitUntil(15000) { CaptureService.instance != null })
            fun show(password: Boolean = false, sensitive:Boolean=false,url:String?=null) {
                val intent = Intent().setClassName(instrumentation.context.packageName, CaptureFixtureActivity::class.java.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("password", password).putExtra("sensitive",sensitive).putExtra("url",url)
                target.startActivity(intent); SystemClock.sleep(2500)
            }
            show()
            instrumentation.runOnMainSync { CaptureService.instance!!.resume() }
            assertTrue("Screenshot/OCR should create history: ${CaptureService.status}", waitUntil(25000) { db.search("Kotlin memory", 0, Long.MAX_VALUE).isNotEmpty() })
            instrumentation.runOnMainSync { CaptureService.instance!!.pause() }
            val count = db.search("", 0, Long.MAX_VALUE, 0).size
            SystemClock.sleep(11500)
            assertEquals("Pause must stop capture", count, db.search("", 0, Long.MAX_VALUE, 0).size)
            prefs.p.edit().putString("exclusions", instrumentation.context.packageName).commit()
            instrumentation.runOnMainSync { CaptureService.instance!!.resume() }
            SystemClock.sleep(11500)
            assertEquals("Excluded screen must not be stored", count, db.search("", 0, Long.MAX_VALUE, 0).size)
            instrumentation.runOnMainSync { CaptureService.instance!!.pause() }
            prefs.p.edit().putString("exclusions", "").commit()
            show(password = true)
            instrumentation.runOnMainSync { CaptureService.instance!!.resume() }
            SystemClock.sleep(11500)
            assertEquals("Password screen must not be stored", count, db.search("", 0, Long.MAX_VALUE, 0).size)
            instrumentation.runOnMainSync { CaptureService.instance!!.pause() }
            prefs.p.edit().putString("blocked_domains","bank.example").commit()
            show(url="https://login.bank.example/account")
            instrumentation.runOnMainSync { CaptureService.instance!!.resume() }
            SystemClock.sleep(4500)
            assertEquals("Detected blocked address must not be stored",count,db.search("",0,Long.MAX_VALUE,0).size)
            instrumentation.runOnMainSync { CaptureService.instance!!.pause() }
            prefs.p.edit().putString("blocked_domains","").putBoolean("redact_text",true).commit()
            val previousId=db.search("",0,Long.MAX_VALUE,0).maxOf { it.id }
            show(sensitive=true)
            instrumentation.runOnMainSync { CaptureService.instance!!.resume() }
            assertTrue("Redacted capture should be stored",waitUntil(25000) { db.search("",0,Long.MAX_VALUE,0).any { it.id>previousId } })
            instrumentation.runOnMainSync { CaptureService.instance!!.pause() }
            val redacted=db.search("",0,Long.MAX_VALUE,0).first { it.id>previousId }.text
            assertTrue(redacted.contains("[REDACTED EMAIL]"));assertFalse(redacted.contains("person@example.com"));assertFalse(redacted.contains("98765"))
        } finally {
            instrumentation.runOnMainSync { CaptureService.instance?.pause() }
            db.clear(); prefs.p.edit().clear().commit()
            shell("settings put secure enabled_accessibility_services ${if(originalServices == "null") "''" else originalServices}")
            shell("settings put secure accessibility_enabled ${if(originalEnabled == "null") "0" else originalEnabled}")
        }
    }
}
