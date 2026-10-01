package dev.miniscreenpipe

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicitly selected device smoke test; preserves existing user history. */
@RunWith(AndroidJUnit4::class)
class PhysicalDeviceSmokeTest {
    @Test fun captureAndGeminiOnConnectedDevice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        fun waitUntil(ms: Long, condition: () -> Boolean): Boolean {
            val end = SystemClock.elapsedRealtime() + ms
            while (SystemClock.elapsedRealtime() < end) { if (condition()) return true; SystemClock.sleep(250) }
            return condition()
        }
        val prefs = Prefs(target); val db = HistoryDb.get(target)
        val testPackage = instrumentation.context.packageName
        val originalForeground=prefs.foreground
        val originalOnlySelected=prefs.onlySelected
        val originalGray=prefs.grayscale
        val originalResolution=prefs.resolution
        val originalInterval = prefs.interval
        val originalExclusions = prefs.exclusions
        val existingServices = shell("settings get secure enabled_accessibility_services").trim().takeUnless { it == "null" }.orEmpty()
        val service = "dev.miniscreenpipe/dev.miniscreenpipe.CaptureService"
        val aliases=setOf(service,"dev.miniscreenpipe/.CaptureService")
        val services = (existingServices.split(':').filter { it.isNotBlank() && it !in aliases } + service).distinct().joinToString(":")
        val idsBefore = db.search("", 0, Long.MAX_VALUE, 0).map { it.id }.toSet()
        val keyFile = File(target.filesDir, "device-test-key")
        val useSavedKey=InstrumentationRegistry.getArguments().getString("useSavedKey")=="true"
        org.junit.Assume.assumeTrue("Phone-specific test requires explicitly authorized key input", keyFile.exists() || useSavedKey)
        try {
            check(!target.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Unlock your phone, then rerun the test." }
            if(keyFile.exists()) { prefs.saveKey(keyFile.readText().trim()); keyFile.delete() }
            check(prefs.key().isNotBlank()) { "No saved key is available" }
            prefs.p.edit().putInt("interval", 10).putBoolean("foreground",true).putBoolean("only_selected",false).putBoolean("grayscale",true).putInt("resolution",480).putBoolean("consent", true).commit()
            shell("pm grant dev.miniscreenpipe android.permission.POST_NOTIFICATIONS")
            // Instrumentation force-stops the target process. An unchanged enabled-services
            // value leaves Accessibility Manager's old dead binding in place: reconnect ours only.
            val others=existingServices.split(':').filter { it.isNotBlank() && it !in aliases }.joinToString(":")
            // UiAutomation executes argv directly; shell quoting would become literal data.
            shell(if(others.isBlank())"settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services $others")
            SystemClock.sleep(750)
            shell("settings put secure enabled_accessibility_services $services")
            assertEquals("Enabled-service command must write canonical component names",services,shell("settings get secure enabled_accessibility_services").trim())
            shell("settings put secure accessibility_enabled 1")
            assertTrue("Accessibility service did not connect", waitUntil(15000) { CaptureService.instance != null })
            fun show(password: Boolean = false) {
                target.startActivity(Intent().setClassName(testPackage, CaptureFixtureActivity::class.java.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("password", password))
                SystemClock.sleep(2500)
            }
            show()
            instrumentation.runOnMainSync { CaptureService.instance!!.resume() }
            assertTrue("Screenshot/OCR did not create a test observation: ${CaptureService.status}", waitUntil(30000) {
                db.search("Kotlin memory capture verification", 0, Long.MAX_VALUE).any { it.id !in idsBefore && it.pkg == testPackage }
            })
            assertTrue("Foreground capture service must be active",shell("dumpsys activity services dev.miniscreenpipe").contains("isForeground=true"))
            instrumentation.runOnMainSync { CaptureService.instance!!.pause() }
            val fixtureEntries = db.search("Kotlin memory capture verification", 0, Long.MAX_VALUE).filter { it.id !in idsBefore && it.pkg == testPackage }
            val stored=android.graphics.BitmapFactory.decodeFile(java.io.File(db.images,fixtureEntries.first().image).absolutePath)
            assertTrue("Resolution setting must apply",minOf(stored.width,stored.height)<=480);stored.recycle()
            println("DEVICE_CAPTURE_OK: ${fixtureEntries.size} screenshot/OCR observations")
            val afterCapture = db.search("", 0, Long.MAX_VALUE, 0).size
            SystemClock.sleep(11500)
            assertEquals("Pause must stop capture", afterCapture, db.search("", 0, Long.MAX_VALUE, 0).size)
            prefs.p.edit().putString("exclusions", "$originalExclusions\n$testPackage").commit()
            instrumentation.runOnMainSync { CaptureService.instance!!.resume() }
            SystemClock.sleep(11500)
            assertEquals("Excluded app must not be captured", afterCapture, db.search("", 0, Long.MAX_VALUE, 0).size)
            instrumentation.runOnMainSync { CaptureService.instance!!.pause() }
            prefs.p.edit().putString("exclusions", originalExclusions).commit()
            show(password = true)
            instrumentation.runOnMainSync { CaptureService.instance!!.resume() }
            SystemClock.sleep(11500)
            assertEquals("Password screen must not be captured", afterCapture, db.search("", 0, Long.MAX_VALUE, 0).size)
            instrumentation.runOnMainSync { CaptureService.instance!!.pause() }
            println("DEVICE_PRIVACY_OK: pause, exclusion, password screen")
            val question="Which Kotlin memory phrase was visible? Quote the phrase and cite the observation id. This is a test screen."
            val retrieved=db.retrieve("Kotlin memory capture verification",fixtureEntries.minOf { it.time },fixtureEntries.maxOf { it.lastSeen }+1,question)
            assertTrue("Retrieval must contain only synthetic observations",retrieved.entries.isNotEmpty() && retrieved.entries.all { it.pkg==testPackage && it.id !in idsBefore })
            val evidence=EvidencePolicy.context(retrieved.entries,question,true,retrieved.note)
            assertTrue("Retrieved evidence must obey the prompt budget",RetrievalPolicy.fit(evidence))
            val answer = Gemini.ask(prefs.key(), prefs.model, question, evidence)
            assertTrue("Gemini must return supported findings", answer.isNotBlank() && fixtureEntries.any { answer.contains("[${it.id}]") })
            println("DEVICE_GEMINI_OK: response received (${answer.length} characters); model ${prefs.model}")
        } finally {
            keyFile.delete()
            instrumentation.runOnMainSync { CaptureService.instance?.pause() }
            prefs.recording = false
            prefs.p.edit().putInt("interval", originalInterval).putBoolean("foreground",originalForeground).putBoolean("only_selected",originalOnlySelected).putBoolean("grayscale",originalGray).putInt("resolution",originalResolution).putString("exclusions", originalExclusions).commit()
            // Remove only observations made by this test; leave all existing history intact.
            db.search("", 0, Long.MAX_VALUE, 0).filter { it.id !in idsBefore }.forEach { db.delete(it.id) }
            target.startActivity(Intent(target, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
