package com.rohanc.ocrscanner

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rohanc.ocrscanner.core.*
import com.rohanc.ocrscanner.data.*
import com.rohanc.ocrscanner.feature.ScannerViewModel
import com.rohanc.ocrscanner.feature.capture.CameraCapture
import com.rohanc.ocrscanner.feature.editor.*
import kotlinx.coroutines.*
import java.io.File

class MainActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{
        MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF6267B4),onPrimary=Color.White,
            background=Color(0xFFE8EAF0),surface=Color(0xFFE8EAF0),onSurface=Color(0xFF2E3040),surfaceVariant=Color(0xFFDDE0E8))){FolioApp()}
    }}
}

@Composable
private fun FolioApp(vm:ScannerViewModel=viewModel()){
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val library by vm.library.collectAsStateWithLifecycle();val document by vm.document.collectAsStateWithLifecycle()
    val route by vm.route.collectAsStateWithLifecycle();val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle();val defaults by vm.defaults.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle();val detected by vm.detected.collectAsStateWithLifecycle()
    val work by vm.worker.collectAsStateWithLifecycle();val labPath by vm.labPath.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    var confirmDelete by remember{mutableStateOf(false)};var removeId by remember{mutableStateOf<String?>(null)}
    var replaceRegions by remember{mutableStateOf(false)}
    var query by rememberSaveable{mutableStateOf("")}
    val searchResults by remember(query){vm.repository.search(query)}.collectAsStateWithLifecycle(emptyList())
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){if(it.isNotEmpty())vm.importImages(it)}
    var copyPath by rememberSaveable{mutableStateOf<String?>(null)}
    val copyPicker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")){uri ->
        val source=copyPath;copyPath=null
        if(uri!=null && source!=null)scope.launch{try{withContext(Dispatchers.IO){
            context.contentResolver.openOutputStream(uri,"w")?.use{out->File(source).inputStream().use{it.copyTo(out)}}?:error("Destination could not be opened.")
        }}catch(failure:Exception){vm.notifyError(failure.message?:"PDF could not be saved.")}}
    }
    fun share(export:ExportEntity,open:Boolean){try{
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",File(export.path))
        val intent=if(open)Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/pdf") else Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM,uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.clipData=ClipData.newRawUri("PDF",uri)
        context.startActivity(Intent.createChooser(intent,if(open)"Open PDF" else "Share PDF"))
    }catch(failure:Exception){vm.notifyError(failure.message?:"No compatible PDF app is available.")}}
    BackHandler(route!="01"){if(busy)vm.cancel() else vm.back()}
    val snapshot=document
    val active=snapshot?.regions?.firstOrNull{it.id==activeId}?:snapshot?.regions?.minByOrNull{it.position}
    val capture=active?.let{r->snapshot?.captures?.firstOrNull{it.id==r.captureId}}
    val image=active?.correctedPath?:capture?.uprightPath
    val latest=snapshot?.exports?.maxByOrNull{it.createdAt}
    val currentExport=latest?.takeIf{it.sourceRevision==snapshot?.document?.revision}
    if(route=="boot"){
        var entered by remember{mutableStateOf(false)};val alpha by animateFloatAsState(if(entered)1f else 0f,label="Logo reveal")
        LaunchedEffect(Unit){entered=true;delay(750);vm.go(if(defaults.introDone)"01" else "intro-1")}
        Surface(Modifier.fillMaxSize()){Column(Modifier.fillMaxSize().alpha(alpha),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
            Image(painterResource(R.drawable.folio_logo),"Folio",Modifier.size(100.dp));Text("Folio",style=MaterialTheme.typography.displaySmall);Text("A place for every page.")
        }}
    }else if(route.startsWith("intro")){
        val second=route=="intro-2"
        NativeScreen("Folio",back=null,footer={Primary(if(second)"Start scanning" else "Continue",!busy){if(second)vm.finishIntro() else vm.go("intro-2")}}){
            TextButton(onClick=vm::finishIntro){Text("Skip introduction")}
            Image(painterResource(if(second)R.drawable.onboarding_text else R.drawable.onboarding_capture),null,Modifier.fillMaxWidth().height(280.dp))
            Text(if(second)"Paper, made searchable." else "A clearer way to keep things.",style=MaterialTheme.typography.headlineLarge)
            Text(if(second)"Read, review and find your words. Create the PDF you need." else "Scan documents and cards. Keep originals and refine each page on your device.")
        }
    }else{
        val titles=mapOf("01" to "Your library","02" to "New scan","03" to "Capture","04" to "Page boundaries","05" to "Enhancement","06" to "Review text","07" to "Card sides & pairs","08" to "Organize pages","09" to "Page layout","10" to "Create PDF","11" to "PDF preview","12" to "Document details","13" to "Search","14" to "Settings","15" to "Learning lab")
        val needsDocument=route in listOf("04","05","06","07","08","09","10","11","12","15")
        NativeScreen(titles[route]?:"Folio",back=if(route=="01")null else { {if(busy)vm.cancel() else vm.back()} },scroll=route!="03"){
            if(busy){LinearProgressIndicator(Modifier.fillMaxWidth());Text("Processing on this device${work?.progress?.getInt("total",0)?.takeIf{it>0}?.let{" · ${work?.progress?.getInt("done",0)}/$it"}.orEmpty()}");TextButton(onClick=vm::cancel){Text("Cancel processing")}}
            if(needsDocument && snapshot==null){Text("Opening document…");return@NativeScreen}
            if(snapshot?.document?.deleting==true){Text("Deletion is incomplete. Retry to remove the remaining private files.");Primary("Retry deletion",!busy){vm.delete()};return@NativeScreen}
            when(route){
                "01"->{
                    Text("Keep the paper. Find the words.",style=MaterialTheme.typography.bodyLarge)
                    Primary("New scan",!busy,vm::newScan)
                    Row{TextButton(onClick={vm.go("13")}){Text("Search")};TextButton(onClick={vm.go("14")}){Text("Settings")}}
                    if(library.isEmpty())Notice("Your first scan starts here","Capture a page or import a photo. No account is needed.")
                    library.forEach{d->OutlinedButton(onClick={vm.open(d.id)},modifier=Modifier.fillMaxWidth()){
                        Column(Modifier.fillMaxWidth().padding(8.dp)){Text(d.title,style=MaterialTheme.typography.titleMedium);Text(if(d.deleting)"Deletion pending" else if(d.mode=="CARDS")"ID cards" else "Document",style=MaterialTheme.typography.bodySmall)}
                    }}
                }
                "02"->{
                    Text("What are you scanning?")
                    Choices(listOf("DOCUMENT","CARDS"),snapshot?.document?.mode?:mode,!busy && snapshot?.regions.isNullOrEmpty(),{vm.chooseMode(it)}){if(it=="DOCUMENT")"Document" else "ID cards"}
                    Text(if((snapshot?.document?.mode?:mode)=="CARDS")"Import several cards in a photo, or capture each side separately. You will confirm the pairs." else "Capture or import one or more document photos.")
                    Primary("Use camera",!busy){vm.go("03")};Secondary("Import photos",!busy){picker.launch(arrayOf("image/*"))}
                    if(snapshot?.regions?.isNotEmpty()==true)Secondary("Return to organizer",!busy){vm.go("08")}
                }
                "03"->CameraCapture(onCaptured={vm.importImages(listOf(Uri.fromFile(it)),it)},onError=vm::notifyError,onImport={picker.launch(arrayOf("image/*"))})
                "04"->{
                    if(active==null || capture==null){EmptyPages(vm);return@NativeScreen}
                    var quad by remember(active.id,active.imageRevision,detected){mutableStateOf(detected.firstOrNull()?:vm.repository.recipe(active,capture).quad)}
                    CropEditor(capture.uprightPath,capture.width,capture.height,quad,{quad=it},Modifier.fillMaxWidth().height(350.dp))
                    Text("Drag the four corners. Boundaries use original upright-image coordinates.",style=MaterialTheme.typography.bodySmall)
                    Row{TextButton(enabled=!busy,onClick=vm::detect){Text("Find boundaries")};TextButton(enabled=!busy,onClick={quad=Quad.full(capture.width,capture.height)}){Text("Reset")}}
                    if(snapshot!!.document.mode=="CARDS"){
                        if(detected.size>1)Secondary("Use all ${detected.size} detected cards",!busy){replaceRegions=true}
                        Secondary("Add a missed card",!busy,vm::addRegion)
                    }
                    Primary("Apply boundaries",!busy){vm.correct(vm.repository.recipe(active,capture).copy(quad=quad),"05")}
                }
                "05"->{
                    if(active==null || capture==null || image==null){EmptyPages(vm);return@NativeScreen}
                    var recipe by remember(active.id,active.imageRevision){mutableStateOf(vm.repository.recipe(active,capture))}
                    ScanImage(image,"Current corrected page",Modifier.fillMaxWidth().height(310.dp))
                    Choices(Enhancement.entries.map{it.name},recipe.enhancement.name,!busy,{recipe=recipe.copy(enhancement=Enhancement.valueOf(it))}){it.lowercase().replace('_',' ')}
                    Text("Brightness ${recipe.brightness.toInt()}");Slider(recipe.brightness,{recipe=recipe.copy(brightness=it)},valueRange=-60f..60f,enabled=!busy && recipe.enhancement!=Enhancement.ORIGINAL)
                    Text("Contrast ${(recipe.contrast*100).toInt()}%");Slider(recipe.contrast,{recipe=recipe.copy(contrast=it)},valueRange=.5f..1.8f,enabled=!busy && recipe.enhancement!=Enhancement.ORIGINAL)
                    Secondary("Rotate 90°",!busy){recipe=recipe.copy(rotation=(recipe.rotation+1)%4)}
                    Primary("Apply enhancement",!busy){vm.correct(recipe,"08")}
                    Secondary("Read all pages",!busy,vm::recognizeAll)
                    Secondary("Retake this page",!busy,vm::retake)
                    if(defaults.learningLab)TextButton(enabled=!busy,onClick={vm.go("15")}){Text("Understand the image pipeline")}
                }
                "06"->{
                    if(active==null || image==null){EmptyPages(vm);return@NativeScreen}
                    RegionSelector(snapshot!!,active,vm,!busy)
                    var showImage by remember{mutableStateOf(false)}
                    Choices(listOf("Text","Image"),if(showImage)"Image" else "Text",true,{showImage=it=="Image"}){it}
                    if(showImage)ScanImage(image,"OCR source image",Modifier.fillMaxWidth().height(340.dp))
                    else{
                        var text by rememberSaveable(active.id,active.textRevision){mutableStateOf(active.reviewedText?:active.recognizedText.orEmpty())}
                        if(active.ocrRevision==null)Text("This page has not been read yet.") else if(active.recognizedText.isNullOrBlank())Text("No text found. Try a clearer image.")
                        OutlinedTextField(text,{text=it},enabled=!busy && active.ocrRevision==active.imageRevision,label={Text("Reviewed text")},minLines=7,modifier=Modifier.fillMaxWidth())
                        Secondary("Save text review",!busy && active.ocrRevision!=null && text!=(active.reviewedText?:active.recognizedText)){vm.review(text)}
                    }
                    Secondary("Re-read this page",!busy,vm::recognize)
                    Secondary("Read all pages",!busy,vm::recognizeAll)
                    Primary("Continue",!busy){vm.go(if(snapshot!!.document.mode=="CARDS")"07" else "08")}
                }
                "07"->{
                    if(snapshot!!.regions.isEmpty()){EmptyPages(vm);return@NativeScreen}
                    Text("Use Single for a card with one side. Matching labels need one Front and one Back.")
                    snapshot.regions.sortedBy{it.position}.forEach{region->CardPairEditor(region,vm,!busy)}
                    Primary("Confirm sides & pairs",!busy,vm::confirmCards)
                }
                "08"->{
                    val s=snapshot!!
                    var title by rememberSaveable(s.document.id,s.document.title){mutableStateOf(s.document.title)}
                    OutlinedTextField(title,{title=it},enabled=!busy,label={Text("Document title")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    if(title.trim()!=s.document.title)Secondary("Save title",!busy){vm.rename(title)}
                    if(s.regions.isEmpty())EmptyPages(vm)
                    s.regions.sortedBy{it.position}.forEach{r->val c=s.captures.single{it.id==r.captureId}
                        Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp)){
                            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                                ScanImage(r.correctedPath?:c.uprightPath,"Page ${r.position+1}",Modifier.fillMaxWidth().height(160.dp),maxSide=400)
                                Text("Page ${r.position+1} · ${if(r.ocrRevision==r.imageRevision)"Text read" else "Needs OCR"}")
                                Row(Modifier.horizontalScroll(rememberScrollState())){TextButton(enabled=!busy,onClick={vm.editRegion(r.id,"04")}){Text("Crop")};TextButton(enabled=!busy,onClick={vm.editRegion(r.id,"05")}){Text("Enhance")};TextButton(enabled=!busy,onClick={vm.editRegion(r.id,"06")}){Text("Text")}}
                                Row{TextButton(enabled=!busy && r.position>0,onClick={vm.move(r.id,-1)}){Text("Up")};TextButton(enabled=!busy && r.position<s.regions.size-1,onClick={vm.move(r.id,1)}){Text("Down")};TextButton(enabled=!busy,onClick={removeId=r.id}){Text("Remove")}}
                            }
                        }
                    }
                    Secondary("Add pages",!busy){vm.go("02")}
                    if(s.document.mode=="CARDS")Secondary("Review card pairs",!busy){vm.go("07")}
                    Secondary("Read all pages",!busy && s.regions.isNotEmpty(),vm::recognizeAll)
                    Primary("Arrange pages",!busy && s.regions.isNotEmpty()){vm.go("09")}
                }
                "09"->{
                    val s=snapshot!!;val d=s.document
                    if(d.mode=="CARDS")Choices(listOf("Stacked","Side by side","Grid"),d.template,!busy,{vm.layout(it,d.paper,d.margin)}){it}
                    Choices(listOf("A4","Letter"),d.paper,!busy,{vm.layout(d.template,it,d.margin)}){it}
                    Choices(listOf("Comfortable","Compact"),if(d.margin==36f)"Comfortable" else "Compact",!busy,{vm.layout(d.template,d.paper,if(it=="Comfortable")36f else 18f)}){it}
                    val sheets=remember(s){runCatching{vm.repository.layoutSheets(s)}}
                    if(sheets.isSuccess){Text("${sheets.getOrThrow().size} PDF sheet(s) · aspect ratio preserved");LayoutSketch(sheets.getOrThrow().first())}else Text(sheets.exceptionOrNull()?.message?:"Review card pairing.")
                    Primary("Export options",!busy && sheets.isSuccess){vm.go("10")}
                    if(sheets.isFailure && d.mode=="CARDS")Secondary("Review sides & pairs",!busy){vm.go("07")}
                }
                "10"->{
                    var output by rememberSaveable{mutableStateOf("SEARCHABLE")}
                    Choices(listOf("SEARCHABLE","TEXT"),output,!busy,{output=it}){if(it=="TEXT")"Regenerated text" else "Searchable scan"}
                    Text(if(output=="SEARCHABLE")"Keep the page image and add aligned, invisible recognized words." else "Typeset the reviewed text into new single-column pages.")
                    if(output=="SEARCHABLE")Text("Rewritten text needs regenerated output. Read every page before export.",style=MaterialTheme.typography.bodySmall)
                    Primary("Generate PDF",!busy && snapshot!!.regions.isNotEmpty()){vm.export(output)}
                    Secondary("Review recognized text",!busy){vm.go("06")}
                }
                "11"->{
                    val export=latest
                    if(export==null){Text("Generate a PDF first.");Primary("Create PDF",!busy){vm.go("10")}}
                    else{
                        var page by rememberSaveable(export.id){mutableIntStateOf(0)}
                        if(currentExport==null)Text("Previous export · edits are not included. Generate again for the latest revision.")
                        PdfPage(export.path,page,Modifier.fillMaxWidth().height(450.dp),vm::notifyError)
                        Row(verticalAlignment=Alignment.CenterVertically){TextButton(onClick={page--},enabled=page>0){Text("Previous")};Text("${page+1} / ${export.pageCount}");TextButton(onClick={page++},enabled=page+1<export.pageCount){Text("Next")}}
                        Primary("Save PDF to files",!busy){copyPath=export.path;copyPicker.launch(snapshot!!.document.title.replace('/','_').replace('\\','_')+".pdf")}
                        Secondary("Share PDF",!busy){share(export,false)}
                        TextButton(onClick={share(export,true)}){Text("Open in PDF reader")}
                        Secondary("Done",!busy){vm.go("12")}
                    }
                }
                "12"->{
                    val s=snapshot!!;Text(s.document.title,style=MaterialTheme.typography.headlineMedium)
                    Text("${s.regions.size} region(s) · ${s.exports.size} completed export(s)")
                    if(latest!=null){Text(if(currentExport==null)"Previous PDF available; newer edits are not exported." else "Current PDF saved privately on this device.");Primary("Open PDF",!busy){vm.go("11")}}
                    Primary("Edit document",!busy){vm.go("08")}
                    TextButton(enabled=!busy,onClick={confirmDelete=true}){Text("Delete document")}
                }
                "13"->{
                    OutlinedTextField(query,{query=it},label={Text("Search titles and recognized text")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                    if(searchResults.isEmpty())Text("No matching documents.")
                    searchResults.forEach{item->Secondary(item.title,true){vm.open(item.id)}}
                }
                "14"->{
                    Text("Default page size");Choices(listOf("A4","Letter"),defaults.paper,!busy,{vm.saveDefaults(defaults.copy(paper=it))}){it}
                    Text("Working image quality");Choices(listOf("High","Balanced"),defaults.quality,!busy,{vm.saveDefaults(defaults.copy(quality=it))}){it}
                    Text("High: 3000 px. Balanced: 2000 px. Originals are always retained.",style=MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment=Alignment.CenterVertically){Text("Learning lab",Modifier.weight(1f));Switch(defaults.learningLab,{vm.saveDefaults(defaults.copy(learningLab=it))},enabled=!busy)}
                    Text("Recognition: bundled Latin script. Processing and documents stay on this device.")
                    Secondary("Replay introduction",!busy){vm.go("intro-1")}
                }
                "15"->{
                    if(active==null){EmptyPages(vm);return@NativeScreen}
                    Choices(listOf("Grayscale","Edges","Threshold"),"",!busy,{vm.lab(it)}){it}
                    if(labPath!=null)ScanImage(labPath!!,"Actual intermediate processing image",Modifier.fillMaxWidth().height(400.dp))
                    Text("Grayscale removes color. Edges emphasize boundaries. Adaptive thresholding separates local light and dark pixels; it can erase fine strokes.")
                    Secondary("Return to enhancement",!busy){vm.go("05")}
                }
            }
        }
    }
    if(message!=null)AlertDialog(onDismissRequest=vm::clearMessage,title={Text("Could not finish")},text={Text(message!!)},confirmButton={TextButton(onClick=vm::clearMessage){Text("OK")}})
    if(confirmDelete)Confirm("Delete this document?","Remove its images, text and private PDFs. Shared copies remain outside Folio.",{confirmDelete=false}){confirmDelete=false;vm.delete()}
    if(removeId!=null)Confirm("Remove this region?","The original capture remains in the document until the document is deleted.",{removeId=null}){val id=removeId!!;removeId=null;vm.remove(id)}
    if(replaceRegions)Confirm("Replace this photo’s regions?","New detected cards replace the existing regions and text from this photo. Review each boundary afterward.",{replaceRegions=false}){replaceRegions=false;vm.splitDetected()}
}

@Composable private fun NativeScreen(title:String,back:(()->Unit)?,scroll:Boolean=true,footer:(@Composable ()->Unit)?=null,content:@Composable ColumnScope.()->Unit){
    Scaffold{padding->Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically){
            if(back!=null)TextButton(onClick=back){Text("Back")}else Image(painterResource(R.drawable.folio_logo),null,Modifier.size(40.dp))
            Text(title,style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f))
        }
        Column(Modifier.weight(1f).fillMaxWidth().then(if(scroll)Modifier.verticalScroll(rememberScrollState()) else Modifier),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)
        if(footer!=null)footer()
        Spacer(Modifier.height(4.dp))
    }}
}
@Composable private fun Primary(text:String,enabled:Boolean=true,action:()->Unit){Button(action,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=RoundedCornerShape(18.dp)){Text(text)}}
@Composable private fun Secondary(text:String,enabled:Boolean=true,action:()->Unit){OutlinedButton(action,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=RoundedCornerShape(16.dp)){Text(text)}}
@Composable private fun Choices(options:List<String>,selected:String,enabled:Boolean,onChoose:(String)->Unit,label:(String)->String){
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        options.forEach{option->FilterChip(selected==option,{onChoose(option)},enabled=enabled,label={Text(label(option))},modifier=Modifier.heightIn(min=48.dp))}
    }
}
@Composable private fun Notice(title:String,body:String){Card(shape=RoundedCornerShape(20.dp)){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(title,style=MaterialTheme.typography.titleMedium);Text(body)}}}
@Composable private fun EmptyPages(vm:ScannerViewModel){Text("Add a photo before editing pages.");Primary("Add pages"){vm.go("02")}}
@Composable private fun Confirm(title:String,body:String,dismiss:()->Unit,confirm:()->Unit){AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={Text(body)},confirmButton={TextButton(onClick=confirm){Text("Confirm")}},dismissButton={TextButton(onClick=dismiss){Text("Keep")}})}
@Composable private fun RegionSelector(s:DocumentSnapshot,active:RegionEntity,vm:ScannerViewModel,enabled:Boolean){Choices(s.regions.sortedBy{it.position}.map{it.id},active.id,enabled,vm::selectRegion){id->"Page ${s.regions.single{it.id==id}.position+1}"}}
@Composable private fun CardPairEditor(region:RegionEntity,vm:ScannerViewModel,enabled:Boolean){
    var side by rememberSaveable(region.id,region.side){mutableStateOf(region.side)};var label by rememberSaveable(region.id,region.pairLabel){mutableStateOf(region.pairLabel)}
    Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("Card ${region.position+1}");Choices(CardSide.entries.map{it.name},side,enabled,{side=it}){it.lowercase()}
        if(side!="SINGLE")OutlinedTextField(label,{label=it},label={Text("Matching pair label")},enabled=enabled,singleLine=true,modifier=Modifier.fillMaxWidth())
        Secondary("Save card labels",enabled && (side!=region.side || label.trim()!=region.pairLabel)){vm.label(region.id,side,if(side=="SINGLE")"" else label)}
    }}
}
@Composable private fun LayoutSketch(sheet:Sheet){
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(340.dp).background(Color(0xFFDDE0E8),RoundedCornerShape(16.dp))){
        val scale=minOf(size.width/sheet.width,size.height/sheet.height)*.95f;val x=(size.width-sheet.width*scale)/2;val y=(size.height-sheet.height*scale)/2
        drawRect(Color.White,androidx.compose.ui.geometry.Offset(x,y),androidx.compose.ui.geometry.Size(sheet.width*scale,sheet.height*scale))
        sheet.placements.forEach{p->drawRect(Color(0xFF9DA1CE),androidx.compose.ui.geometry.Offset(x+p.x*scale,y+p.y*scale),androidx.compose.ui.geometry.Size(p.width*scale,p.height*scale))}
    }
}
