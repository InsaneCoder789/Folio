package com.rohanc.ocrscanner.feature.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.rohanc.ocrscanner.feature.editor.ScanImage

@Composable fun CaptureUi(count:Int,capturing:Boolean,ready:Boolean,onCapture:()->Unit,onImport:()->Unit,onDone:()->Unit,thumbnails:List<String> = emptyList(),cards:Boolean=false,paired:Boolean=false,preview:@Composable BoxScope.()->Unit){
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(20.dp)).background(androidx.compose.ui.graphics.Color(0xFF8B90A0))){
            preview()
            Canvas(Modifier.fillMaxSize().padding(horizontal=30.dp,vertical=65.dp)){
                val color=Silk.accent.copy(alpha=.55f);val len=22.dp.toPx();val stroke=2.dp.toPx()
                listOf(androidx.compose.ui.geometry.Offset.Zero,androidx.compose.ui.geometry.Offset(size.width,0f),androidx.compose.ui.geometry.Offset(0f,size.height),androidx.compose.ui.geometry.Offset(size.width,size.height)).forEachIndexed{i,p->
                    drawLine(color,p,p+androidx.compose.ui.geometry.Offset(if(i%2==0)len else -len,0f),stroke)
                    drawLine(color,p,p+androidx.compose.ui.geometry.Offset(0f,if(i<2)len else -len),stroke)
                }
            }
            Text("Align the page inside the frame",Modifier.align(Alignment.TopCenter).padding(14.dp).background(Color(0xCC454A5C),RoundedCornerShape(9.dp)).padding(horizontal=12.dp,vertical=10.dp),style=MaterialTheme.typography.labelSmall,color=Color.White)
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){SilkCaption("$count ${if(cards)"card regions" else if(count==1)"page" else "pages"} captured");SilkCaption(if(cards && paired)if(count%2==0)"Next: front side" else "Next: back side" else "Manual capture")}
        if(thumbnails.isNotEmpty())Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp)){thumbnails.forEachIndexed{i,p->ScanImage(p,"Captured page ${i+1}",Modifier.size(38.dp,50.dp).clip(RoundedCornerShape(5.dp)),maxSide=120)}}
        Row(Modifier.fillMaxWidth().heightIn(min=88.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
            SilkIconButton("image","Import images",!capturing,onImport)
            Box(Modifier.size(76.dp).silkPanel(radius=38.dp).clip(CircleShape).clickable(enabled=ready && !capturing,role=Role.Button,onClick=onCapture).semantics{contentDescription=if(capturing)"Capturing page" else "Capture page"},contentAlignment=Alignment.Center){SilkIcon("camera",Modifier.size(27.dp),if(ready)Silk.accent else Silk.muted)}
            TextButton(enabled=count>0 && !capturing,onClick=onDone){Text("Done",style=MaterialTheme.typography.labelMedium);SilkIcon("next",Modifier.size(16.dp))}
        }
    }
}
