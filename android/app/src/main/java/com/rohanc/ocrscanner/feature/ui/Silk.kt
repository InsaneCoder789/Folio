package com.rohanc.ocrscanner.feature.ui

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.graphics.PathParser
import com.rohanc.ocrscanner.R

object Silk {
    val surface=Color(0xFFE8EAF0)
    val ink=Color(0xFF2E3040)
    val muted=Color(0xFF5B5E70)
    val accent=Color(0xFF6265CE)
    val accentText=Color(0xFF4F52AD)
    val line=Color(0xFFD4D7E0)
    val font=FontFamily(Font(R.font.jakarta_regular,FontWeight.Normal),Font(R.font.jakarta_medium,FontWeight.Medium),Font(R.font.jakarta_semibold,FontWeight.SemiBold),Font(R.font.jakarta_bold,FontWeight.Bold))
}

@Composable fun FolioTheme(content:@Composable ()->Unit){
    fun type(size:Int,weight:FontWeight=FontWeight.Normal,line:Int=(size*1.55).toInt())=TextStyle(fontFamily=Silk.font,fontSize=size.sp,fontWeight=weight,lineHeight=line.sp,color=Silk.ink,letterSpacing=if(size>=24)(-.8).sp else (-.2).sp)
    MaterialTheme(colorScheme=lightColorScheme(primary=Silk.accent,onPrimary=Color.White,background=Silk.surface,surface=Silk.surface,onSurface=Silk.ink,onSurfaceVariant=Silk.muted,outline=Silk.line),typography=Typography(
        headlineLarge=type(29,FontWeight.SemiBold,36).copy(letterSpacing=(-1.15).sp),headlineMedium=type(27,FontWeight.SemiBold,36),headlineSmall=type(24,FontWeight.SemiBold,32),
        titleLarge=type(18,FontWeight.SemiBold,24),titleMedium=type(15,FontWeight.SemiBold,22),titleSmall=type(14,FontWeight.SemiBold,21),
        bodyLarge=type(14,line=23),bodyMedium=type(13,line=21),bodySmall=type(12,line=20),
        labelLarge=type(14,FontWeight.SemiBold,21),labelMedium=type(12,FontWeight.Medium,18),labelSmall=type(11,FontWeight.Medium,17)
    ),content=content)
}

/** Opposing Silk shadows translated from the approved prototype's raised/inset tokens. */
fun Modifier.silkPanel(inset:Boolean=false,radius:Dp=18.dp):Modifier=drawBehind {
    val r=radius.toPx()
    drawIntoCanvas { canvas ->
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        val rect=RectF(0f,0f,size.width,size.height)
        if(!inset){
            paint.color=Silk.surface.toArgb()
            paint.setShadowLayer(14.dp.toPx(),5.dp.toPx(),5.dp.toPx(),0x70B6BDCC)
            canvas.nativeCanvas.drawRoundRect(rect,r,r,paint)
            paint.setShadowLayer(14.dp.toPx(),(-5).dp.toPx(),(-5).dp.toPx(),0xB0FFFFFF.toInt())
            canvas.nativeCanvas.drawRoundRect(rect,r,r,paint)
            paint.clearShadowLayer();canvas.nativeCanvas.drawRoundRect(rect,r,r,paint)
        } else {
            paint.color=Silk.surface.toArgb();canvas.nativeCanvas.drawRoundRect(rect,r,r,paint)
            val path=android.graphics.Path().apply{addRoundRect(rect,r,r,android.graphics.Path.Direction.CW)}
            canvas.nativeCanvas.save();canvas.nativeCanvas.clipPath(path)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=2.dp.toPx()
            paint.color=0x00FFFFFF;paint.setShadowLayer(8.dp.toPx(),4.dp.toPx(),4.dp.toPx(),0xA0AEB7CB.toInt())
            canvas.nativeCanvas.drawRoundRect(RectF(-2.dp.toPx(),-2.dp.toPx(),size.width+2.dp.toPx(),size.height+2.dp.toPx()),r,r,paint)
            paint.setShadowLayer(8.dp.toPx(),(-4).dp.toPx(),(-4).dp.toPx(),0xE0FFFFFF.toInt())
            canvas.nativeCanvas.drawRoundRect(RectF(-2.dp.toPx(),-2.dp.toPx(),size.width+2.dp.toPx(),size.height+2.dp.toPx()),r,r,paint)
            canvas.nativeCanvas.restore()
        }
    }
}

private val paths=mapOf(
    "back" to "m14 6-6 6 6 6","next" to "m10 6 6 6-6 6","plus" to "M12 5v14M5 12h14","close" to "m6 6 12 12M6 18 18 6",
    "search" to "M20 20l-5-5M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0","settings" to "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8M9 3h6l1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1Z",
    "document" to "M6 3h8l4 4v14H6ZM14 3v5h4M9 12h6M9 16h6","cards" to "M3 7h18v13H3ZM7 3h13v4M7 11h3v4H7ZM13 11h5M13 15h5",
    "camera" to "M4 7h4l2-3h4l2 3h4v13H4ZM16 13a4 4 0 1 1-8 0 4 4 0 0 1 8 0","image" to "M3 4h18v16H3ZM3 16l5-5 5 5 3-3 5 5M8 8h.01",
    "check" to "m5 12 4 4L19 6","rotate" to "M4 7v5h5M4 12a8 8 0 1 1 4 7","crop" to "M7 3v14h14M3 7h14v14",
    "text" to "M4 5h16M12 5v15M8 20h8","layout" to "M3 3h18v18H3ZM3 9h18M12 9v12","trash" to "M4 6h16M9 3h6M6 6l1 15h10l1-15M10 10v7M14 10v7",
    "share" to "M12 16V3m-4 4 4-4 4 4M5 11v10h14V11","download" to "M12 3v13m-4-4 4 4 4-4M4 17v4h16v-4",
    "scan" to "M3 8V3h5M16 3h5v5M21 16v5h-5M8 21H3v-5M6 12h12","up" to "m6 14 6-6 6 6","down" to "m6 10 6 6 6-6",
    "lab" to "M9 3h6M10 3v7l-5 9v2h14v-2l-5-9V3M8 15h8","folder" to "M3 5h7l2 3h9v12H3Z"
)
@Composable fun SilkIcon(name:String,modifier:Modifier=Modifier,color:Color=Silk.accentText){
    val path=remember(name){PathParser.createPathFromPathData(paths[name]?:paths.getValue("document"))!!.asComposePath()}
    Canvas(modifier.size(20.dp)){scale(size.width/24f,size.height/24f,pivot=androidx.compose.ui.geometry.Offset.Zero){drawPath(path,color,style=Stroke(1.6f,cap=StrokeCap.Round,join=StrokeJoin.Round))}}
}
@Composable fun SilkIconButton(icon:String,label:String,enabled:Boolean=true,onClick:()->Unit){
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled=enabled,role=Role.Button,onClick=onClick).semantics{contentDescription=label},contentAlignment=Alignment.Center){SilkIcon(icon,color=if(enabled)Silk.ink else Silk.muted.copy(alpha=.4f))}
}
@Composable fun SilkButton(label:String,icon:String="next",primary:Boolean=true,enabled:Boolean=true,onClick:()->Unit){
    val shape=RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().heightIn(min=54.dp).then(if(primary)Modifier.background(if(enabled)Silk.accent else Silk.accent.copy(alpha=.4f),shape) else Modifier.silkPanel(radius=16.dp))
        .clip(shape).clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=18.dp,vertical=14.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
        if(icon.isNotEmpty()){SilkIcon(icon,Modifier.size(18.dp),if(primary)Color.White else Silk.accentText);Spacer(Modifier.width(10.dp))}
        Text(label,style=MaterialTheme.typography.labelLarge,color=if(primary)Color.White else Silk.ink)
    }
}
@Composable fun SilkSegments(options:List<String>,selected:String,enabled:Boolean=true,label:(String)->String={it},onSelect:(String)->Unit){
    Row(Modifier.fillMaxWidth().silkPanel(inset=true,radius=13.dp).padding(4.dp),verticalAlignment=Alignment.CenterVertically){
        options.forEach { value ->
            Box(Modifier.weight(1f).heightIn(min=48.dp).then(if(value==selected)Modifier.silkPanel(radius=10.dp) else Modifier)
                .clip(RoundedCornerShape(10.dp)).selectable(value==selected,enabled=enabled,role=Role.Tab,onClick={onSelect(value)}).padding(horizontal=4.dp,vertical=8.dp),contentAlignment=Alignment.Center){
                Text(label(value),style=MaterialTheme.typography.labelSmall,color=if(value==selected)Silk.accentText else Silk.muted)
            }
        }
    }
}
@Composable fun SilkChoice(title:String,detail:String,icon:String,selected:Boolean,enabled:Boolean=true,onClick:()->Unit){
    Row(Modifier.fillMaxWidth().silkPanel(inset=selected,radius=19.dp).clip(RoundedCornerShape(19.dp))
        .selectable(selected,enabled=enabled,role=Role.RadioButton,onClick=onClick).padding(horizontal=18.dp,vertical=22.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
        SilkIcon(icon,Modifier.size(24.dp));Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){
            Text(title,style=MaterialTheme.typography.titleSmall);Text(detail,style=MaterialTheme.typography.bodySmall,color=Silk.muted)
        }
        Box(Modifier.size(20.dp).background(if(selected)Silk.accent else Color.Transparent,CircleShape).border(1.dp,if(selected)Silk.accent else Color(0xFFB7BED0),CircleShape),contentAlignment=Alignment.Center){if(selected)SilkIcon("check",Modifier.size(14.dp),Color.White)}
    }
}
@Composable fun SilkField(value:String,onChange:(String)->Unit,label:String,modifier:Modifier=Modifier,singleLine:Boolean=true,minLines:Int=1,enabled:Boolean=true,suffix:String="",requester:FocusRequester?=null){
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)){
        if(label.isNotEmpty())Text(label,style=MaterialTheme.typography.labelMedium)
        androidx.compose.foundation.text.BasicTextField(value,onChange,enabled=enabled,singleLine=singleLine,minLines=minLines,
            textStyle=MaterialTheme.typography.bodyMedium,cursorBrush=SolidColor(Silk.accent),modifier=Modifier.fillMaxWidth().silkPanel(inset=true,radius=13.dp).padding(17.dp).then(if(requester!=null)Modifier.focusRequester(requester) else Modifier).semantics{contentDescription=label},decorationBox={inner->Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.weight(1f)){inner()};if(suffix.isNotEmpty())Text(suffix,style=MaterialTheme.typography.bodySmall,color=Silk.muted)}})
    }
}
@Composable fun SilkCaption(text:String,modifier:Modifier=Modifier){Text(text,modifier,style=MaterialTheme.typography.bodySmall,color=Silk.muted)}
@Composable fun SilkTitle(title:String,description:String){Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Text(title,style=MaterialTheme.typography.headlineLarge);SilkCaption(description)}}
@Composable fun SilkEmpty(title:String,description:String){Column(Modifier.fillMaxWidth().padding(vertical=56.dp,horizontal=12.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(18.dp)){
    SilkIcon("folder",Modifier.size(40.dp),Color(0xFF8E94AB));Text(title,style=MaterialTheme.typography.titleLarge);Text(description,style=MaterialTheme.typography.bodySmall,color=Silk.muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
}}

@Composable fun SilkScreen(route:String,title:String,subtitle:String="",back:(()->Unit)?=null,actions:@Composable RowScope.()->Unit={},footer:(@Composable ColumnScope.()->Unit)?=null,scroll:Boolean=true,content:@Composable ColumnScope.()->Unit){
    Column(Modifier.fillMaxSize().background(Silk.surface).windowInsetsPadding(WindowInsets.safeDrawing).imePadding().testTag("screen-$route")){
        Row(Modifier.fillMaxWidth().heightIn(min=79.dp).padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
            if(back!=null)SilkIconButton("back","Back",onClick=back) else Image(painterResource(R.drawable.folio_logo),null,Modifier.size(40.dp).padding(3.dp))
            Spacer(Modifier.width(8.dp));Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                Text(title,style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
                if(subtitle.isNotEmpty())Text(subtitle,style=MaterialTheme.typography.labelSmall,color=Silk.muted)
            };actions()
        }
        key(route){Column(Modifier.weight(1f).fillMaxWidth().then(if(scroll)Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(start=24.dp,end=24.dp,top=10.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp),content=content)}
        if(footer!=null)Column(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=footer)
    }
}
