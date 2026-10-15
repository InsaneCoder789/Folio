package com.rohanc.ocrscanner.feature.ui

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.rohanc.ocrscanner.data.*
import com.rohanc.ocrscanner.feature.editor.ScanImage
import java.io.File

/** Debug-only presentation review. Fictional fixtures never enter the production document store. */
class UiGalleryActivity:ComponentActivity(){
    var state by mutableStateOf(ScannerUi())
    var reviewWidth by mutableStateOf<androidx.compose.ui.unit.Dp?>(null)
    var reviewFontScale by mutableFloatStateOf(1f)
    private val returns=mutableMapOf<String,String>()
    val events=mutableListOf<UiAction>()
    private var count by mutableIntStateOf(0)
    private var reviewMessage by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.light(Silk.surface.toArgb(),Silk.surface.toArgb()),navigationBarStyle=SystemBarStyle.light(Silk.surface.toArgb(),Silk.surface.toArgb()))
        state=fixture(this,intent.getStringExtra("screen")?:"01",intent.getBooleanExtra("cards",false))
        setContent{val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,reviewFontScale)){Box(Modifier.fillMaxSize().then(if(reviewWidth!=null)Modifier.requiredWidth(reviewWidth!!) else Modifier)){FolioTheme{FolioScreens(state,::dispatch,camera={CaptureUi(count,false,true,{count++},{dispatch(UiAction.Import)},{dispatch(UiAction.Go("04"))},cards=state.isCards,paired=state.workflow=="paired"){
            ScanImage(state.image!!,"Fictional camera sample",Modifier.fillMaxSize().padding(45.dp))
        }})
            if(reviewMessage!=null)androidx.compose.material3.AlertDialog(onDismissRequest={reviewMessage=null},title={androidx.compose.material3.Text("Review card sides")},text={SilkCaption(reviewMessage!!)},confirmButton={androidx.compose.material3.TextButton(onClick={reviewMessage=null}){androidx.compose.material3.Text("OK")}},containerColor=Silk.surface)
        }}}}
    }
    fun dispatch(action:UiAction){
        events+=action
        val target=when(action){is UiAction.Go->action.route;is UiAction.NamedNavigation->action.route;is UiAction.Open->"12";is UiAction.CreatePdf->"11";UiAction.NewScan->"02";else->null}
        if(target=="11")returns["11"]=if(state.route=="12")"12" else "10"
        if(target=="12" && state.route in listOf("01","13"))returns["12"]=state.route
        if(target=="02")returns["02"]=if(state.route in listOf("08","07"))state.route else "01"
        state=when(action){
            is UiAction.Go->if(action.route=="03" && state.document==null)fixture(this,"03",state.isCards).copy(workflow=state.workflow) else state.copy(route=action.route)
            UiAction.Back->state.copy(route=if(state.route in listOf("04","05","06") && state.editorReturn!=null)state.editorReturn!! else returns[state.route]?:when(state.route){"02","12","13","14"->"01";"03"->"02";"04"->"03";"05"->"04";"06"->"05";"07"->"06";"08"->"01";"09"->"08";"10"->"09";"11"->"10";"15"->"05";else->"01"})
            UiAction.NewScan->{count=0;state.copy(route="02",document=null,editorReturn=null)}
            is UiAction.Workflow->state.copy(workflow=action.value)
            is UiAction.Mode->state.copy(mode=action.value,document=null)
            UiAction.Import->fixture(this,"04",state.isCards)
            UiAction.IntroDone->state.copy(route="01")
            is UiAction.Open->fixture(this,"12",action.id=="cards")
            is UiAction.Select->state.copy(activeId=action.id)
            is UiAction.Correct->state.copy(route=action.next)
            is UiAction.EnhanceAndRead->state.copy(route="06")
            is UiAction.ReviewAndContinue->state.copy(route=action.next)
            UiAction.ConfirmCards->try{layoutSheets(state.document!!);state.copy(route="08")}catch(failure:Exception){reviewMessage=failure.message;state}
            is UiAction.ConfirmCardLabels->try{
                val proposed=state.document!!.copy(regions=state.document!!.regions.map{r->val edit=action.edits.single{it.id==r.id};r.copy(side=edit.side,pairLabel=if(edit.side=="SINGLE")"" else edit.label.trim())})
                layoutSheets(proposed);state.copy(route="08",document=proposed)
            }catch(failure:Exception){reviewMessage=failure.message;state}
            is UiAction.NamedNavigation->state.copy(document=state.document?.let{it.copy(document=it.document.copy(title=action.title))},route=action.route,activeId=action.regionId?:state.activeId,editorReturn=if(action.regionId!=null)"08" else null)
            is UiAction.Label->state.copy(document=state.document!!.copy(regions=state.document!!.regions.map{if(it.id==action.id)it.copy(side=action.side,pairLabel=action.label) else it}))
            is UiAction.CreatePdf->state.copy(route="11",document=state.document!!.copy(exports=listOf(state.export!!.copy(mode=action.mode,path=File(cacheDir,if(action.mode=="TEXT")"ui-review-text.pdf" else "ui-review-scan.pdf").path))))
            is UiAction.NamedBack->state.copy(route=if(state.route=="08")"01" else "09",document=state.document?.let{it.copy(document=it.document.copy(title=action.title))})
            is UiAction.ReadForExport->state.copy(route="06",editorReturn="10")
            is UiAction.Query->state.copy(query=action.value,searchResults=state.library.filter{it.title.contains(action.value,true)})
            is UiAction.SaveDefaults->state.copy(route="01",defaults=action.value)
            is UiAction.Layout->state.copy(document=state.document!!.copy(document=state.document!!.document.copy(template=action.template,paper=action.paper,margin=action.margin)))
            is UiAction.Lab->state.copy(labPath=state.image)
            UiAction.Read,UiAction.ReadAll->state.copy(route="06")
            UiAction.Retake->state.copy(route="03")
            else->state
        }
    }
    companion object {
        fun fixture(context:Context,route:String,cards:Boolean=false):ScannerUi {
            val paper=File(context.cacheDir,"ui-review-paper.jpg")
            val card=File(context.cacheDir,"ui-review-card.jpg")
            if(!paper.exists())drawPaper(paper,false)
            if(!card.exists())drawPaper(card,true)
            val now=System.currentTimeMillis()
            val image=if(cards)card else paper
            val captures=listOf(CaptureEntity("capture","sample",image.path,image.path,if(cards)1000 else 700,if(cards)630 else 990))
            val text=if(cards)"STUDIO MEMBERSHIP\n\nSample member\nPaper Study Studio" else "PROJECT NOTES\n\nA clearer archive\n\nCapture important pages, review the words,\nand keep the result in one place."
            val region=RegionEntity("page","capture","sample",0,ocrRevision=0,recognizedText=text,wordsJson="""[{"text":"Project notes","polygon":[[65,100],[345,100],[345,155],[65,155]]}]""",correctedPath=image.path,correctedWidth=captures[0].width,correctedHeight=captures[0].height,side=if(cards)"FRONT" else "SINGLE",pairLabel=if(cards)"Studio card" else "")
            val regions=if(cards)listOf(region,region.copy(id="back",position=1,side="BACK")) else listOf(region)
            val pdf=File(context.cacheDir,"ui-review-scan.pdf")
            if(!pdf.exists())makePdf(pdf,paper,false)
            val typed=File(context.cacheDir,"ui-review-text.pdf")
            if(!typed.exists())makePdf(typed,paper,true)
            val snapshot=DocumentSnapshot(DocumentEntity("sample",if(cards)"Studio card set" else "Project notes",updatedAt=now,mode=if(cards)"CARDS" else "DOCUMENT"),captures,regions,listOf(ExportEntity("export","sample",0,"SEARCHABLE",pdf.path,1,now)))
            return ScannerUi(route,listOf(LibraryItem("sample","Project notes","DOCUMENT",2,paper.path,"Searchable scan",now),LibraryItem("cards","Studio card set","CARDS",1,card.path,"Searchable scan",now-86400000)),snapshot,region.id,defaults=ScannerDefaults(introDone=true),labPath=image.path)
        }
        private fun drawPaper(file:File,card:Boolean){
            val bitmap=Bitmap.createBitmap(if(card)1000 else 700,if(card)630 else 990,Bitmap.Config.ARGB_8888)
            val c=Canvas(bitmap);c.drawColor(Color.rgb(251,251,249));val p=Paint(Paint.ANTI_ALIAS_FLAG)
            fun text(value:String,x:Float,y:Float,size:Float,color:Int=Color.rgb(47,52,67),bold:Boolean=false){p.color=color;p.textSize=size;p.typeface=if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT;c.drawText(value,x,y,p)}
            if(card){
                text("STUDIO / MEMBERSHIP",55f,80f,20f);text("DEMO",860f,80f,18f)
                p.color=Color.rgb(220,224,233);c.drawRect(55f,170f,245f,410f,p)
                text("Sample member",300f,220f,38f,bold=true);text("Paper Study Studio",300f,280f,25f);text("Front · Design sample",300f,340f,24f)
                text("NOT AN IDENTITY DOCUMENT",55f,550f,18f)
            }else{
                text("PAPER STUDY / 2026",65f,75f,14f,Color.GRAY);text("Project notes",65f,145f,42f,bold=true)
                p.color=Color.rgb(208,212,222);c.drawRect(65f,175f,635f,178f,p)
                text("A clearer archive",65f,220f,21f)
                repeat(5){i->p.color=Color.rgb(205,210,221);c.drawRect(65f,255f+i*24,if(i==4)480f else 635f,261f+i*24,p)}
                text("PROJECT",65f,430f,12f,Color.GRAY);text("DATE",365f,430f,12f,Color.GRAY)
                text("Paper Study",65f,463f,18f);text("14 October 2026",365f,463f,18f)
                repeat(9){i->p.color=Color.rgb(205,210,221);c.drawRect(65f,510f+i*25,if(i%3==2)535f else 635f,516f+i*25,p)}
                text("Keep the original. Make the words useful.",65f,910f,14f,Color.GRAY)
            }
            file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,98,it)};bitmap.recycle()
        }
        private fun makePdf(file:File,image:File,typed:Boolean){
            val pdf=PdfDocument();try{val page=pdf.startPage(PdfDocument.PageInfo.Builder(595,842,1).create())
                if(typed){val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.DKGRAY;textSize=27f;typeface=Typeface.DEFAULT_BOLD};page.canvas.drawText("Project notes",45f,85f,paint);paint.typeface=Typeface.DEFAULT;paint.textSize=16f;listOf("A clearer archive","Capture important pages, review the words,","and keep the result in one place.").forEachIndexed{i,t->page.canvas.drawText(t,45f,145f+i*35,paint)}}
                else {val bitmap=BitmapFactory.decodeFile(image.path);page.canvas.drawBitmap(bitmap,null,RectF(25f,25f,570f,817f),null);bitmap.recycle()}
                pdf.finishPage(page);file.outputStream().use{pdf.writeTo(it)}
            }finally{pdf.close()}
        }
    }
}
