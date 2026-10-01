package dev.miniscreenpipe

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.text.Text
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ExecutionException

/** Takes ownership of input, including when an OCR task outlives the timeout. */
object OcrAwaiter {
    data class Result(val image:Bitmap,val text:Text?,val failure:String)
    fun await(task:Task<Text>,input:Bitmap,nativeText:String,timeout:Long=20,unit:TimeUnit=TimeUnit.SECONDS):Result {
        try { return Result(input,Tasks.await(task,timeout,unit),"") }
        catch(e:TimeoutException) {
            val fallback=try { if(nativeText.isNotBlank())input.copy(Bitmap.Config.ARGB_8888,false) else null }
            finally { releaseWhenDone(task,input) }
            // Attach the release listener only AFTER copying, even if task completes meanwhile.
            return if(fallback!=null)Result(fallback,null,"ocr-timeout") else throw e
        } catch(e:ExecutionException) {
            if(nativeText.isNotBlank())return Result(input,null,"ocr-failed")
            input.recycle();throw e
        } catch(e:Exception) { releaseWhenDone(task,input);throw e }
    }
    private fun releaseWhenDone(task:Task<Text>,input:Bitmap) {
        task.addOnCompleteListener(java.util.concurrent.Executor { it.run() }) { input.recycle() }
    }
}
