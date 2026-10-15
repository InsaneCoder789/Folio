package com.rohanc.ocrscanner.feature.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.rohanc.ocrscanner.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.*

@Composable
fun ScanImage(path:String,description:String,modifier:Modifier=Modifier,maxSide:Int=1000,colorFilter:androidx.compose.ui.graphics.ColorFilter?=null) {
    var loaded by remember(path,maxSide){mutableStateOf(false)}
    val bitmap by produceState<Bitmap?>(null,path,maxSide){value=null;loaded=false;value=withContext(Dispatchers.IO){
        val info=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(path,info)
        val options=BitmapFactory.Options().apply{inSampleSize=1};while(max(info.outWidth,info.outHeight)/options.inSampleSize>maxSide)options.inSampleSize*=2
        BitmapFactory.decodeFile(path,options)
    };loaded=true}
    if(bitmap!=null) Image(bitmap!!.asImageBitmap(),description,modifier,contentScale=ContentScale.Fit,colorFilter=colorFilter)
    else Box(modifier){Text(if(loaded)"Image unavailable. Retake or import the page again." else "Loading image…")}
}

@Composable
fun CropEditor(path:String,width:Int,height:Int,quad:Quad,onChange:(Quad)->Unit,modifier:Modifier=Modifier) {
    var editorSize by remember{mutableStateOf(IntSize.Zero)}
    val touchSize=with(LocalDensity.current){48.dp.toPx()}
    Box(modifier.onSizeChanged{editorSize=it}) {
        ScanImage(path,"Original photo and crop boundaries",Modifier.fillMaxSize())
        var dragged by remember{mutableIntStateOf(-1)}
        val latest by rememberUpdatedState(quad)
        Canvas(Modifier.fillMaxSize().pointerInput(width,height){
            fun scale()=min(size.width.toFloat()/width,size.height.toFloat()/height)
            fun origin(s:Float)=Offset((size.width-width*s)/2,(size.height-height*s)/2)
            detectDragGestures(onDragStart={touch ->
                val s=scale();val o=origin(s)
                dragged=latest.points.indices.minBy{ i-> val p=latest.points[i];(Offset(p.x*s,p.y*s)+o-touch).getDistance() }
            },onDragEnd={dragged=-1},onDragCancel={dragged=-1}) { change,delta ->
                if(dragged>=0){change.consume();val s=scale();val points=latest.points.toMutableList();val p=points[dragged]
                    points[dragged]=Point((p.x+delta.x/s).coerceIn(0f,width-1f),(p.y+delta.y/s).coerceIn(0f,height-1f));onChange(Quad(points))}
            }
        }) {
            val s=min(size.width/width,size.height/height);val o=Offset((size.width-width*s)/2,(size.height-height*s)/2)
            val points=quad.points.map{Offset(it.x*s,it.y*s)+o}
            val pathLine=Path().apply{moveTo(points[0].x,points[0].y);points.drop(1).forEach{lineTo(it.x,it.y)};close()}
            drawPath(pathLine,Color(0xFF6267B4),style=Stroke(3.dp.toPx()))
            points.forEach{drawCircle(Color.White,10.dp.toPx(),it);drawCircle(Color(0xFF6267B4),6.dp.toPx(),it)}
        }
        // Virtual handles expose the same corners without adding visible controls.
        // TalkBack actions move in source coordinates and retain the drag bounds.
        if(editorSize.width>0 && editorSize.height>0){
            val scale=min(editorSize.width.toFloat()/width,editorSize.height.toFloat()/height)
            val origin=Offset((editorSize.width-width*scale)/2,(editorSize.height-height*scale)/2)
            val names=listOf("Top left","Top right","Bottom right","Bottom left")
            quad.points.forEachIndexed{index,point->
                fun move(dx:Float,dy:Float):Boolean{
                    val points=latest.points.toMutableList();val current=points[index]
                    val next=Point((current.x+dx).coerceIn(0f,width-1f),(current.y+dy).coerceIn(0f,height-1f))
                    if(next==current)return false
                    points[index]=next;onChange(Quad(points));return true
                }
                val stepX=max(1f,width/100f);val stepY=max(1f,height/100f)
                val position=Offset(point.x*scale,point.y*scale)+origin
                Box(Modifier.offset{IntOffset(
                    (position.x-touchSize/2).roundToInt().coerceIn(0,max(0,editorSize.width-touchSize.roundToInt())),
                    (position.y-touchSize/2).roundToInt().coerceIn(0,max(0,editorSize.height-touchSize.roundToInt()))
                )}.size(48.dp).semantics{
                    contentDescription="${names[index]} crop corner"
                    stateDescription="Horizontal ${(100*point.x/max(1,width-1)).roundToInt()} percent, vertical ${(100*point.y/max(1,height-1)).roundToInt()} percent"
                    customActions=listOf(
                        CustomAccessibilityAction("Move left"){move(-stepX,0f)},
                        CustomAccessibilityAction("Move right"){move(stepX,0f)},
                        CustomAccessibilityAction("Move up"){move(0f,-stepY)},
                        CustomAccessibilityAction("Move down"){move(0f,stepY)}
                    )
                }.focusable())
            }
        }
    }
}

@Composable
fun PdfPage(path:String,index:Int,modifier:Modifier=Modifier,onError:(String)->Unit) {
    var failureText by remember(path,index){mutableStateOf<String?>(null)}
    val bitmap by produceState<Bitmap?>(null,path,index){
        value=withContext(Dispatchers.IO){try{
            ParcelFileDescriptor.open(File(path),ParcelFileDescriptor.MODE_READ_ONLY).use{fd -> PdfRenderer(fd).use{pdf ->
                pdf.openPage(index.coerceIn(0,pdf.pageCount-1)).use{page ->
                    val w=900;val h=(w.toFloat()*page.height/page.width).roundToInt()
                    Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also{it.eraseColor(android.graphics.Color.WHITE);page.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)}
                }
            }}
        }catch(failure:Exception){withContext(Dispatchers.Main){failureText=failure.message?:"PDF cannot be opened.";onError(failureText!!)};null}}
    }
    if(bitmap!=null)Image(bitmap!!.asImageBitmap(),"PDF page ${index+1}",modifier,contentScale=ContentScale.Fit) else Box(modifier){if(failureText!=null)Text("This preview could not be opened. Generate the PDF again.") else CircularProgressIndicator()}
}
