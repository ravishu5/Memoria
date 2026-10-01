package dev.miniscreenpipe

/** Monotonic timing; accessed on the accessibility service's main thread. */
class CapturePolicy {
    var lastAttempt = -750L; private set
    var lastStored = -600_000L; private set
    private var lastProbe = -3000L
    private var burstStart = -1L
    var quietUntil = 0L; private set
    fun reset() { lastAttempt=-750;lastStored=-600_000;lastProbe=-3000;quietUntil=0;burstStart=-1 }
    fun settle(now:Long,delay:Long) {
        if(burstStart<0 || now>=quietUntil)burstStart=now
        // Continuous content events must not starve passive/video capture forever.
        quietUntil=minOf(now+delay,burstStart+1500)
    }
    fun delay(now:Long)=maxOf(0,quietUntil-now,750-(now-lastAttempt))
    fun attempt(now:Long) { lastAttempt=now;lastProbe=now;burstStart=-1;quietUntil=0 }
    fun stored(now:Long) { lastStored=now }
    fun due(now:Long,adaptive:Boolean,intervalSeconds:Int):String? {
        if(delay(now)>0)return null
        val idle=if(adaptive)minOf(intervalSeconds,15)*1000L else intervalSeconds*1000L
        if(now-lastStored>=idle && (lastAttempt<0 || now-lastAttempt>=3000))return "idle"
        return if(adaptive && now-lastProbe>=3000)"visual" else null
    }
    companion object {
        // Sampled RGB distance, not an exact fingerprint or OCR-equivalence test.
        fun difference(before:IntArray?,after:IntArray):Double {
            if(before==null || before.size!=after.size || after.isEmpty())return 1.0
            val changed=after.indices.count { i -> (0..2).any { c -> kotlin.math.abs((before[i] ushr (c*8) and 255)-(after[i] ushr (c*8) and 255))>24 } }
            return changed.toDouble()/after.size
        }
    }
}
