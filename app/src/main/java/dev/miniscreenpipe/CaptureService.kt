package dev.miniscreenpipe

import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.os.*
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.security.MessageDigest

class CaptureService : AccessibilityService() {
    private val handler=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private lateinit var prefs:Prefs
    private lateinit var db:HistoryDb
    private val generation=AtomicInteger()
    private val commitLock=Any()
    private var busy=false
    @Volatile private var destroyed=false
    private var lastApp=""
    private val timing=CapturePolicy()
    private var blocked=false
    private var visualPixels:IntArray?=null // Worker only; last committed eligible frame.
    private var visualText=""
    private var visualPkg=""
    private val ocrPending=java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastObserved=0L
    private var lastMaintenance=0L
    private data class OcrCache(val pkg:String,val hash:String,val text:String,val source:String,val quality:String,val native:String)
    private var cached:OcrCache?=null // Accessed only by worker.
    private val tick=object:Runnable { override fun run() {
        try {
            checkPrivacy()
            timing.due(SystemClock.elapsedRealtime(),prefs.adaptive,prefs.interval)?.let { safeCapture(it) }
        } catch(_:Exception) { status="Capture check failed; retrying" }
        if(!destroyed)handler.postDelayed(this,1000)
    } }
    private val eventCapture=object:Runnable { override fun run() {
        val delay=timing.delay(SystemClock.elapsedRealtime())
        if(delay>0)handler.postDelayed(this,delay) else safeCapture("event")
    } }
    override fun onServiceConnected() {
        prefs=Prefs(this); db=HistoryDb.get(this); instance=this
        // Preserve an explicit Start/Pause choice across Android process restarts.
        if(prefs.recording && prefs.p.getBoolean("consent",false)) {
            timing.reset();status="Reconnected · waiting for an eligible screen"
        } else { prefs.recording=false;status=prefs.p.getString("capture_pause_reason","Connected · paused") ?: "Connected · paused" }
        handler.removeCallbacks(tick); handler.post(tick); notifyState()
    }
    override fun onAccessibilityEvent(event:AccessibilityEvent?) {
        if(!::prefs.isInitialized || destroyed) return
        checkPrivacy()
        val delay=when(event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> 250L
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> 750L
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> 1000L
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,AccessibilityEvent.TYPE_WINDOWS_CHANGED,AccessibilityEvent.TYPE_VIEW_CLICKED -> 0L
            else -> return
        }
        if(prefs.recording) {
            timing.settle(SystemClock.elapsedRealtime(),delay)
            handler.removeCallbacks(eventCapture);handler.postDelayed(eventCapture,timing.delay(SystemClock.elapsedRealtime()))
        }
    }
    private fun breakSession() { worker.execute { db.breakSession();cached=null;visualPixels=null;visualPkg="";visualText="" } }
    private fun checkPrivacy() {
        if(!prefs.recording)return
        val pkg=rootInActiveWindow?.packageName?.toString().orEmpty()
        val nowBlocked=!eligible(pkg)
        if(pkg!=lastApp || nowBlocked!=blocked) {
            lastApp=pkg;blocked=nowBlocked
            synchronized(commitLock) { generation.incrementAndGet();lastObserved=0 }
            breakSession()
        }
        if(nowBlocked)status=skipReason
    }

    // Android interrupts accessibility feedback (e.g. speech), not recording consent.
    override fun onInterrupt() {}
    override fun onDestroy() {
        synchronized(commitLock) { destroyed=true; generation.incrementAndGet() }
        handler.removeCallbacksAndMessages(null)
        worker.execute { recognizer.close(); if(::db.isInitialized)db.breakSession() }; worker.shutdown()
        getSystemService(NotificationManager::class.java).cancel(1)
        if(instance===this) { instance=null; status="Service disconnected" }
        super.onDestroy()
    }
    fun pause(reason:String="Paused from app") {
        synchronized(commitLock) { generation.incrementAndGet(); prefs.recording=false }
        handler.removeCallbacks(eventCapture); timing.reset();breakSession()
        prefs.p.edit().putString("capture_pause_reason",reason).putLong("capture_pause_time",System.currentTimeMillis()).commit()
        status=reason; notifyState()
    }
    fun resume() {
        synchronized(commitLock) { generation.incrementAndGet(); prefs.recording=true }
        timing.reset();lastObserved=0;breakSession();status="Waiting for an eligible screen"; notifyState(); handler.removeCallbacks(tick); handler.post(tick)
    }
    private fun password(node:AccessibilityNodeInfo?,depth:Int=0,budget:IntArray=intArrayOf(2500)):Boolean {
        if(node==null)return false
        if((node.isVisibleToUser && node.isPassword) || depth>40 || --budget[0]<0)return true
        for(i in 0 until node.childCount) if(password(node.getChild(i),depth+1,budget))return true
        return false
    }
    private fun visibleText(node:AccessibilityNodeInfo?,depth:Int=0,budget:IntArray=intArrayOf(1200)):String {
        if(node==null || depth>30 || --budget[0]<0 || node.isPassword)return ""
        return buildString { if(node.isVisibleToUser) { node.text?.let { append(it).append('\n') } }; for(i in 0 until node.childCount) append(visibleText(node.getChild(i),depth+1,budget)) }.take(60000)
    }
    private fun browserPrivate(node:AccessibilityNodeInfo?,checkPrivate:Boolean,depth:Int=0,budget:IntArray=intArrayOf(1500)):Boolean {
        if(node==null)return false
        if(depth>40 || --budget[0]<0)return true
        if(node.isVisibleToUser) {
            if(TextPrivacy.addressField(node.viewIdResourceName.orEmpty()) && TextPrivacy.blockedUrl(node.text?.toString().orEmpty(),prefs.blockedDomains))return true
            if(checkPrivate && (TextPrivacy.privateLabel(node.text?.toString().orEmpty()) || TextPrivacy.privateLabel(node.contentDescription?.toString().orEmpty())))return true
        }
        for(i in 0 until node.childCount)if(browserPrivate(node.getChild(i),checkPrivate,depth+1,budget))return true
        return false
    }
    private var skipReason="Waiting for a visible app"
    private fun eligible(pkg:String):Boolean {
        fun skip(reason:String):Boolean { skipReason=reason;return false }
        if(!prefs.recording || destroyed)return skip("Paused")
        if(!getSystemService(PowerManager::class.java).isInteractive || getSystemService(KeyguardManager::class.java).isKeyguardLocked)return skip("Skipping locked screen")
        val root=rootInActiveWindow ?: return skip("Waiting for a visible app")
        if(pkg.isBlank() || root.packageName?.toString()!=pkg)return skip("Waiting for the active window")
        if(pkg==packageName)return skip("Capture running · switch to another app")
        if(prefs.excluded(pkg))return skip("Skipping excluded app: $pkg")
        if(password(root))return skip("Skipping password screen or an unverifiable view tree")
        if((TextPrivacy.browser(pkg) || prefs.blockedDomains.isNotBlank()) && browserPrivate(root,TextPrivacy.browser(pkg)))return skip("Skipping private tab, blocked website, or an unverifiable browser tree")
        val protectedOverlay=windows.any { w ->
            if(w.type!=AccessibilityWindowInfo.TYPE_APPLICATION && !w.isFocused && !w.isActive)return@any false
            val other=w.root ?: return@any false
            val otherPkg=other.packageName?.toString().orEmpty()
            otherPkg!=pkg && (prefs.excluded(otherPkg) || password(other) ||
                ((TextPrivacy.browser(otherPkg) || prefs.blockedDomains.isNotBlank()) && browserPrivate(other,TextPrivacy.browser(otherPkg))))
        }
        if(protectedOverlay)return skip("Skipping screen with a protected app window")
        return true
    }
    private fun safeCapture(reason:String) {
        if(destroyed)return
        try { capture(reason) } catch(_:Exception) { busy=false; status="Capture failed; retrying on the next sample" }
    }
    private fun capture(reason:String) {
        val uptime=SystemClock.elapsedRealtime()
        if(uptime-lastMaintenance>15*60*1000L || lastMaintenance==0L) {
            lastMaintenance=uptime
            worker.execute { try { db.prune(prefs.retention) } catch(_:Exception) { status="Storage maintenance failed; check free space" } }
        }
        if(!prefs.recording || busy)return
        if(!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) { pause("Paused · capture notifications disabled");return }
        val root=rootInActiveWindow
        val pkg=root?.packageName?.toString().orEmpty()
        if(!eligible(pkg))return
        if(timing.delay(uptime)>0)return
        timing.attempt(uptime)
        val time=System.currentTimeMillis(); val version=generation.get(); val windowId=root!!.windowId
        val nativeText=EvidencePolicy.normalize(visibleText(root))
        val label=try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg,0)).toString() } catch(_:Exception) { pkg }
        busy=true
        val callback=object:TakeScreenshotCallback {
            override fun onSuccess(result:ScreenshotResult) {
                if(destroyed) { result.hardwareBuffer.close();return }
                worker.execute {
                    var bitmap:Bitmap?=null
                    var transferred=false
                    try {
                        val buffer=result.hardwareBuffer
                        try { val wrapped=Bitmap.wrapHardwareBuffer(buffer,result.colorSpace); bitmap=try { wrapped?.copy(Bitmap.Config.ARGB_8888,false) } finally { wrapped?.recycle() } } finally { buffer.close() }
                        var image=bitmap ?: error("No screenshot pixels")
                        if(generation.get()!=version || destroyed)return@execute
                        val sample=Bitmap.createScaledBitmap(image,96,96,true)
                        val pixels=IntArray(96*96)
                        try { sample.getPixels(pixels,0,96,0,0,96,96) } finally { if(sample!==image)sample.recycle() }
                        if(reason=="visual" && visualPkg==pkg && visualText==nativeText && CapturePolicy.difference(visualPixels,pixels)<0.05)return@execute
                        val digest=MessageDigest.getInstance("SHA-256")
                        val row=IntArray(image.width); val bytes=ByteBuffer.allocate(image.width*4)
                        for(y in 0 until image.height) { image.getPixels(row,0,image.width,0,y,image.width,1);bytes.clear();bytes.asIntBuffer().put(row);digest.update(bytes.array()) }
                        val hash="${image.width}x${image.height}:"+digest.digest().joinToString("") { "%02x".format(it) }
                        val prior=cached
                        val ocr=if(prior?.hash==hash && prior.pkg==pkg && prior.native==nativeText)prior else {
                            var failure="ocr-failed"
                            val recognized=if(ocrPending.get()) { failure="ocr-busy";null } else {
                                val input=image
                                val task=recognizer.process(InputImage.fromBitmap(input,0))
                                ocrPending.set(true)
                                task.addOnCompleteListener { ocrPending.set(false) }
                                bitmap=null // Awaiter owns input through success, failure or timeout.
                                val result=OcrAwaiter.await(task,input,nativeText)
                                image=result.image;bitmap=image;failure=result.failure
                                result.text
                            }
                            if(recognized==null && nativeText.isBlank())error("No fallback text")
                            val raw=EvidencePolicy.normalize(recognized?.text.orEmpty())
                            val weak=raw.length<20 || recognized?.textBlocks.isNullOrEmpty()
                            val text=if(weak && nativeText.length>raw.length) EvidencePolicy.normalize(raw+"\n"+nativeText) else raw
                            OcrCache(pkg,hash,text,if(recognized==null)"accessibility-fallback" else if(weak && nativeText.length>raw.length)"ocr+accessibility" else "ocr",if(recognized==null)failure else if(weak)"sparse" else "readable",nativeText).also { if(recognized!=null)cached=it }
                        }
                        val committedImage=image

                        // Check privacy on the main thread again before the background commit.
                        handler.post {
                            if(generation.get()!=version || !eligible(pkg)) { committedImage.recycle();busy=false;return@post }
                            worker.execute {
                                try {
                                    synchronized(commitLock) {
                                        if(generation.get()==version && prefs.recording && !destroyed) {
                                            val sampleSeconds=if(lastObserved==0L)minOf(prefs.interval,5) else minOf(prefs.interval,maxOf(1,((time-lastObserved)/1000).toInt()))
                                            db.add(time,label,pkg,if(prefs.redactText)TextPrivacy.redact(ocr.text) else ocr.text,committedImage,sampleSeconds,hash,ocr.source,ocr.quality)
                                            lastObserved=time
                                            visualPixels=pixels;visualText=nativeText;visualPkg=pkg
                                            handler.post { if(generation.get()==version)timing.stored(SystemClock.elapsedRealtime()) }
                                            if(db.imageBytes()>500L*1024*1024)db.prune(prefs.retention)
                                            status="Captured $label · ${ocr.quality} text${if(prior?.hash==hash)" · reused OCR" else ""}"
                                        }
                                    }
                                } catch(_:Exception) { status="Could not store capture. Check available storage." }
                                finally { committedImage.recycle();handler.post { busy=false } }
                            }
                        }
                        bitmap=null; transferred=true // Commit callback now owns pixels.
                    } catch(_:java.util.concurrent.TimeoutException) { status="OCR timed out with no accessible text; retrying when OCR recovers" }
                    catch(_:Exception) { status="OCR failed; retrying on the next sample" }
                    finally { bitmap?.recycle(); handler.post { if(!transferred || generation.get()!=version)busy=false } }
                }
            }
            override fun onFailure(errorCode:Int) { busy=false;status="Screenshot skipped (Android code $errorCode). Protected screens stay private." }
        }
        try { if(Build.VERSION.SDK_INT>=34)takeScreenshotOfWindow(windowId,mainExecutor,callback) else takeScreenshot(Display.DEFAULT_DISPLAY,mainExecutor,callback) }
        catch(_:Exception) { busy=false;status="Capture unavailable; check accessibility permission" }
    }
    fun notifyState() {
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("capture","Capture status",NotificationManager.IMPORTANCE_LOW))
        if(!prefs.recording) { stopForeground(STOP_FOREGROUND_REMOVE);manager.cancel(1);return }
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause=PendingIntent.getBroadcast(this,1,Intent(this,PauseReceiver::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=Notification.Builder(this,"capture").setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Aevra is capturing")
            .setContentText("${if(prefs.adaptive)"Adaptive screens + ${minOf(prefs.interval,15)}s idle" else "Changed screens + ${prefs.interval}s idle"} · Pause to stop").setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"Pause",pause).build()).build()
        if(prefs.foreground) {
            try { if(Build.VERSION.SDK_INT>=34)startForeground(1,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1,notification) }
            catch(_:Exception) { prefs.recording=false;status="Foreground capture unavailable. Disable foreground mode or start again from the app.";stopForeground(STOP_FOREGROUND_REMOVE);manager.cancel(1) }
        } else { stopForeground(STOP_FOREGROUND_DETACH);manager.notify(1,notification) }
    }
    private fun persistStatus(value:String) {
        if(::prefs.isInitialized)prefs.p.edit().putString("capture_status",value).putLong("capture_status_time",System.currentTimeMillis()).apply()
    }
    companion object {
        @Volatile var instance:CaptureService?=null
        @Volatile var status="Enable the capture service to begin"
            set(value) { if(field==value)return;field=value;instance?.persistStatus(value) }
    }
}
class PauseReceiver:BroadcastReceiver() { override fun onReceive(context:Context,intent:Intent) { CaptureService.instance?.pause("Paused from notification") ?: run { val prefs=Prefs(context);prefs.recording=false;prefs.p.edit().putString("capture_pause_reason","Paused from notification").putLong("capture_pause_time",System.currentTimeMillis()).commit() } } }
