package com.rohanc.ocrscanner.core.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.rohanc.ocrscanner.core.*
import kotlinx.coroutines.*
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.UUID
import kotlin.math.*
import kotlin.coroutines.coroutineContext

class OpenCvProcessor {
    private fun initialize() { check(OpenCVLoader.initLocal()) { "Vision engine could not initialize." } }
    suspend fun detect(path: String, multiple: Boolean): List<Quad> = withContext(Dispatchers.Default) {
        initialize()
        val bitmap=BitmapFactory.decodeFile(path) ?: error("Image is unreadable.")
        val rgba=Mat(); val small=Mat(); val gray=Mat(); val edges=Mat(); val hierarchy=Mat()
        val contours=mutableListOf<MatOfPoint>()
        try {
            Utils.bitmapToMat(bitmap,rgba)
            val scale=min(1.0,1200.0/max(bitmap.width,bitmap.height))
            Imgproc.resize(rgba,small,Size(),scale,scale)
            Imgproc.cvtColor(small,gray,Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray,gray,Size(5.0,5.0),0.0)
            Imgproc.Canny(gray,edges,60.0,180.0)
            Imgproc.findContours(edges,contours,hierarchy,Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_SIMPLE)
            val selected=mutableListOf<Quad>()
            contours.sortedByDescending { Imgproc.contourArea(it) }.forEach { contour ->
                coroutineContext.ensureActive()
                val area=Imgproc.contourArea(contour)
                if(area < small.width()*small.height()*(if(multiple) .025 else .12)) return@forEach
                val curve=MatOfPoint2f(*contour.toArray()); val approx=MatOfPoint2f()
                try {
                    Imgproc.approxPolyDP(curve,approx,.02*Imgproc.arcLength(curve,true),true)
                    if(approx.total()!=4L) return@forEach
                    val pts=approx.toArray().map { com.rohanc.ocrscanner.core.Point((it.x/scale).toFloat().coerceIn(0f,bitmap.width-1f),(it.y/scale).toFloat().coerceIn(0f,bitmap.height-1f)) }
                    val cx=pts.sumOf { it.x.toDouble() }/4; val cy=pts.sumOf { it.y.toDouble() }/4
                    val sorted=pts.sortedBy { atan2(it.y-cy,it.x-cx) }
                    val start=sorted.indices.minBy { sorted[it].x+sorted[it].y }
                    val quad=Quad(List(4) { sorted[(start+it)%4] })
                    try { quad.validate(bitmap.width,bitmap.height) } catch(_:IllegalArgumentException) { return@forEach }
                    fun bounds(q:Quad)=listOf(q.points.minOf{it.x},q.points.minOf{it.y},q.points.maxOf{it.x},q.points.maxOf{it.y})
                    val a=bounds(quad)
                    if(selected.any { previous ->
                        val b=bounds(previous); val overlap=max(0f,min(a[2],b[2])-max(a[0],b[0]))*max(0f,min(a[3],b[3])-max(a[1],b[1]))
                        overlap/min((a[2]-a[0])*(a[3]-a[1]),(b[2]-b[0])*(b[3]-b[1])) > .7f
                    }) return@forEach
                    if(selected.size < (if(multiple) 12 else 1)) selected.add(quad)
                } finally { curve.release(); approx.release() }
            }
            selected
        } finally { contours.forEach { it.release() }; listOf(rgba,small,gray,edges,hierarchy).forEach{it.release()}; bitmap.recycle() }
    }
    suspend fun process(path: String, recipe: EditRecipe, directory: File, labStage: String? = null): ProcessedImage = withContext(Dispatchers.Default) {
        initialize(); coroutineContext.ensureActive()
        val bitmap=BitmapFactory.decodeFile(path) ?: error("Image is unreadable.")
        val source=Mat(); val warped=Mat(); val rotated=Mat(); val output=Mat()
        var sourcePoints:MatOfPoint2f?=null; var destPoints:MatOfPoint2f?=null; var transform:Mat?=null
        var file:File?=null
        try {
            recipe.quad.validate(bitmap.width,bitmap.height)
            val (width,height)=recipe.quad.outputSize()
            sourcePoints=MatOfPoint2f(*recipe.quad.points.map { org.opencv.core.Point(it.x.toDouble(),it.y.toDouble()) }.toTypedArray())
            destPoints=MatOfPoint2f(org.opencv.core.Point(0.0,0.0),org.opencv.core.Point(width-1.0,0.0),org.opencv.core.Point(width-1.0,height-1.0),org.opencv.core.Point(0.0,height-1.0))
            transform=Imgproc.getPerspectiveTransform(sourcePoints,destPoints)
            Utils.bitmapToMat(bitmap,source)
            Imgproc.warpPerspective(source,warped,transform,Size(width.toDouble(),height.toDouble()),Imgproc.INTER_CUBIC)
            when(recipe.rotation) { 1->Core.rotate(warped,rotated,Core.ROTATE_90_CLOCKWISE);2->Core.rotate(warped,rotated,Core.ROTATE_180);3->Core.rotate(warped,rotated,Core.ROTATE_90_COUNTERCLOCKWISE);else->warped.copyTo(rotated) }
            if(labStage=="Edges") {
                Imgproc.cvtColor(rotated,output,Imgproc.COLOR_RGBA2GRAY);Imgproc.Canny(output,output,60.0,180.0)
            } else if(recipe.enhancement==Enhancement.GRAYSCALE || recipe.enhancement==Enhancement.BLACK_WHITE || labStage=="Grayscale") {
                Imgproc.cvtColor(rotated,output,Imgproc.COLOR_RGBA2GRAY)
                if(recipe.enhancement==Enhancement.BLACK_WHITE) Imgproc.adaptiveThreshold(output,output,255.0,Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,Imgproc.THRESH_BINARY,31,12.0)
            } else rotated.copyTo(output)
            if(recipe.enhancement!=Enhancement.ORIGINAL && labStage==null) output.convertTo(output,-1,recipe.contrast.toDouble(),recipe.brightness.toDouble())
            coroutineContext.ensureActive()
            val result=Bitmap.createBitmap(output.width(),output.height(),Bitmap.Config.ARGB_8888)
            try {
                Utils.matToBitmap(output,result)
                directory.mkdirs(); file=File(directory,"${UUID.randomUUID()}.jpg")
                file!!.outputStream().use { check(result.compress(Bitmap.CompressFormat.JPEG,95,it)) }
                coroutineContext.ensureActive()
                ProcessedImage(file!!.absolutePath,result.width,result.height)
            } finally { result.recycle() }
        } catch(failure:Throwable) { file?.delete();throw failure }
        finally { sourcePoints?.release();destPoints?.release();transform?.release();listOf(source,warped,rotated,output).forEach{it.release()};bitmap.recycle() }
    }
}
