package dev.miniscreenpipe

import android.content.Context
import android.graphics.*
import android.view.View

/** Small scalable line icons, drawn natively so they stay crisp at every density. */
class UiIcon(context:Context,private val name:String,private val tint:Int):View(context) {
    override fun onDraw(canvas:Canvas) {
        val size=minOf(width,height).toFloat();canvas.save();canvas.translate((width-size)/2,(height-size)/2);canvas.scale(size/24,size/24)
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=tint;style=Paint.Style.STROKE;strokeWidth=1.6f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND }
        fun line(a:Float,b:Float,c:Float,d:Float)=canvas.drawLine(a,b,c,d,p)
        fun circle(x:Float,y:Float,r:Float)=canvas.drawCircle(x,y,r,p)
        fun rect(a:Float,b:Float,c:Float,d:Float,r:Float=3f)=canvas.drawRoundRect(a,b,c,d,r,r,p)
        fun path(vararg points:Float) { val path=Path();path.moveTo(points[0],points[1]);for(i in 2 until points.size step 2)path.lineTo(points[i],points[i+1]);canvas.drawPath(path,p) }
        when(name) {
            "home" -> { path(3f,10f,12f,3f,21f,10f);path(5f,9f,5f,21f,10f,21f,10f,14f,14f,14f,14f,21f,19f,21f,19f,9f) }
            "search" -> { circle(10f,10f,6f);line(15f,15f,21f,21f) }
            "timeline" -> { circle(12f,12f,9f);line(12f,7f,12f,12f);line(12f,12f,16f,14f) }
            "settings" -> { circle(12f,12f,4f);circle(12f,12f,8f);for(i in 0..7) { val a=i*Math.PI/4;line((12+8*kotlin.math.cos(a)).toFloat(),(12+8*kotlin.math.sin(a)).toFloat(),(12+10*kotlin.math.cos(a)).toFloat(),(12+10*kotlin.math.sin(a)).toFloat()) } }
            "lock" -> { rect(5f,10f,19f,21f);canvas.drawArc(8f,2f,16f,15f,180f,180f,false,p);line(12f,14f,12f,17f) }
            "screen" -> { rect(3f,3f,21f,18f);line(8f,22f,16f,22f);line(12f,18f,12f,22f) }
            "apps" -> { for(x in listOf(4f,14f))for(y in listOf(4f,14f))rect(x,y,x+6,y+6,1.5f) }
            "arrow" -> { line(4f,12f,20f,12f);path(14f,6f,20f,12f,14f,18f) }
            "back" -> { line(4f,12f,20f,12f);path(10f,6f,4f,12f,10f,18f) }
            "chevron" -> path(9f,5f,16f,12f,9f,19f)
            "download" -> { line(12f,3f,12f,15f);path(7f,10f,12f,15f,17f,10f);path(4f,16f,4f,21f,20f,21f,20f,16f) }
            "moon" -> { val path=Path();path.moveTo(18f,17f);path.cubicTo(6f,22f,0f,9f,11f,3f);path.cubicTo(7f,12f,12f,18f,18f,17f);canvas.drawPath(path,p) }
            "spark" -> { path(12f,2f,15f,9f,22f,12f,15f,15f,12f,22f,9f,15f,2f,12f,9f,9f,12f,2f) }
            "bell" -> { path(5f,17f,7f,14f,7f,8f);canvas.drawArc(7f,3f,17f,13f,180f,180f,false,p);path(17f,8f,17f,14f,19f,17f,5f,17f);canvas.drawArc(9f,16f,15f,22f,0f,180f,false,p) }
            "check" -> path(5f,12f,10f,17f,20f,7f)
            "pause" -> { line(8f,5f,8f,19f);line(16f,5f,16f,19f) }
            "play" -> path(8f,4f,20f,12f,8f,20f,8f,4f)
            "trash" -> { line(4f,6f,20f,6f);rect(7f,6f,17f,21f,2f);line(9f,3f,15f,3f);line(10f,10f,10f,17f);line(14f,10f,14f,17f) }
            "text" -> { rect(4f,3f,20f,21f);for(y in listOf(8f,12f,16f))line(8f,y,16f,y) }
            "image" -> { rect(3f,3f,21f,21f);circle(8f,8f,1.5f);path(4f,18f,10f,12f,14f,16f,18f,11f,21f,14f) }
            "external" -> { path(14f,3f,21f,3f,21f,10f);line(21f,3f,11f,13f);path(10f,4f,4f,4f,4f,20f,20f,20f,20f,14f) }
            "user" -> { circle(12f,7f,4f);canvas.drawArc(5f,14f,19f,24f,180f,180f,false,p) }
            "copy" -> { rect(8f,8f,20f,20f,2f);path(16f,5f,5f,5f,5f,16f) }
            "refresh" -> { canvas.drawArc(4f,4f,20f,20f,45f,270f,false,p);path(17f,5f,20f,11f,14f,11f) }
            else -> { circle(12f,12f,9f);line(12f,10f,12f,17f);circle(12f,6f,0.5f) }
        }
        canvas.restore()
    }
}
