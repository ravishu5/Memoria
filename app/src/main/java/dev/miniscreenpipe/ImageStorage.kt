package dev.miniscreenpipe

import android.graphics.*
import java.io.ByteArrayOutputStream

object ImageStorage {
    // Resolution is the short edge (480p/720p/1080p); never upscale.
    fun encode(image:Bitmap,shortEdge:Int,grayscale:Boolean):ByteArray {
        val scale=if(shortEdge==0)1.0 else minOf(1.0,shortEdge.toDouble()/minOf(image.width,image.height))
        val resized=if(scale<1)Bitmap.createScaledBitmap(image,maxOf(1,(image.width*scale).toInt()),maxOf(1,(image.height*scale).toInt()),true) else image
        var output=resized
        try {
            if(grayscale) {
                output=Bitmap.createBitmap(resized.width,resized.height,Bitmap.Config.ARGB_8888)
                val paint=Paint().apply { colorFilter=ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
                Canvas(output).drawBitmap(resized,0f,0f,paint)
            }
            return ByteArrayOutputStream().use { check(output.compress(Bitmap.CompressFormat.WEBP_LOSSY,80,it));it.toByteArray() }
        } finally { if(output!==resized)output.recycle();if(resized!==image)resized.recycle() }
    }
}
