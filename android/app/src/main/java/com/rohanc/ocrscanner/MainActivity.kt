package com.rohanc.ocrscanner

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rohanc.ocrscanner.data.*
import com.rohanc.ocrscanner.feature.ScannerViewModel
import com.rohanc.ocrscanner.feature.capture.CameraCapture
import com.rohanc.ocrscanner.feature.ui.*
import kotlinx.coroutines.*
import java.io.File

class MainActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.light(Silk.surface.toArgb(),Silk.surface.toArgb()),navigationBarStyle=SystemBarStyle.light(Silk.surface.toArgb(),Silk.surface.toArgb()))
        setContent{FolioTheme{FolioApp()}}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun FolioApp(vm:ScannerViewModel=viewModel()){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val library by vm.library.collectAsStateWithLifecycle()
    val document by vm.document.collectAsStateWithLifecycle()
    val route by vm.route.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    val workflow by vm.workflow.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val defaults by vm.defaults.collectAsStateWithLifecycle()
    val detected by vm.detected.collectAsStateWithLifecycle()
    val labPath by vm.labPath.collectAsStateWithLifecycle()
    val previewPath by vm.previewPath.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val editorCaller by vm.editorCaller.collectAsStateWithLifecycle()
    var query by rememberSaveable{mutableStateOf("")}
    val search by remember(query){vm.repository.search(query)}.collectAsStateWithLifecycle(emptyList())
    val items by produceState<List<LibraryItem>>(emptyList(),library){
        value=withContext(Dispatchers.IO){library.mapNotNull{entry->runCatching{
            val snapshot=vm.repository.snapshot(entry.id)
            val region=snapshot.regions.minByOrNull{it.position}
            val capture=snapshot.captures.firstOrNull{it.id==region?.captureId}
            val export=snapshot.exports.maxByOrNull{it.createdAt}
            LibraryItem(entry.id,entry.title,entry.mode,snapshot.regions.size,region?.correctedPath?:capture?.uprightPath,
                when{export==null->"Editable draft";export.sourceRevision!=entry.revision->"Draft · previous PDF kept";export.mode=="TEXT"->"Regenerated text";else->"Searchable scan"},entry.updatedAt,entry.deleting)
        }.getOrNull()}}
    }
    var cameraFiles by rememberSaveable{mutableStateOf(arrayListOf<String>())}
    var shareOptions by remember{mutableStateOf(false)}
    var leaveCamera by remember{mutableStateOf(false)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->if(uris.isNotEmpty()){
        val files=cameraFiles.map(::File)
        vm.importImages(files.map(Uri::fromFile)+uris,temporaries=files,onSuccess={cameraFiles=arrayListOf()})
    }}
    var copyPath by rememberSaveable{mutableStateOf<String?>(null)}
    val copyPicker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")){uri->
        val source=copyPath;copyPath=null
        if(uri!=null && source!=null)scope.launch{try{withContext(Dispatchers.IO){
            context.contentResolver.openOutputStream(uri,"w")?.use{out->File(source).inputStream().use{it.copyTo(out)}}?:error("Destination could not be opened.")
        }}catch(cancelled:CancellationException){throw cancelled}catch(failure:Exception){vm.notifyError(failure.message?:"PDF could not be saved.")}}
    }
    fun share(export:ExportEntity,open:Boolean){try{
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",File(export.path),pdfFileName(document?.document?.title?:"Scan"))
        val intent=if(open)Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/pdf") else Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM,uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.clipData=ClipData.newRawUri("PDF",uri)
        context.startActivity(Intent.createChooser(intent,if(open)"Open PDF" else "Share PDF"))
    }catch(failure:Exception){vm.notifyError(failure.message?:"No compatible PDF app is available.")}}
    val ui=ScannerUi(route,items,document,activeId,mode,busy,defaults,detected,labPath,items.filter{item->search.any{it.id==item.id}},query,editorCaller,document?.document?.workflow?:workflow,previewPath)
    fun goBack(){if(route=="03" && cameraFiles.isNotEmpty()){leaveCamera=true;return};if(busy){vm.cancel();return};when(route){
        "04"->vm.go(editorCaller?:"02")
        "05"->vm.go(editorCaller?:"04")
        "06"->vm.go(editorCaller?:"05")
        "09"->vm.go("08")
        else->vm.back()
    }}
    BackHandler(route!="01" && route!="boot"){goBack()}
    FolioScreens(ui,{action->when(action){
        is UiAction.Go->vm.go(action.route)
        UiAction.Back->goBack()
        UiAction.NewScan->vm.newScan()
        is UiAction.Open->vm.open(action.id)
        is UiAction.Workflow->vm.chooseWorkflow(action.value)
        is UiAction.Mode->vm.chooseMode(action.value)
        UiAction.Import->picker.launch(arrayOf("image/*"))
        UiAction.IntroDone->vm.finishIntro()
        is UiAction.Select->vm.selectRegion(action.id)
        is UiAction.Edit->vm.editRegion(action.id,action.route)
        is UiAction.Correct->vm.correct(action.recipe,action.next)
        is UiAction.Preview->vm.preview(action.recipe)
        UiAction.ClearPreview->vm.clearPreview()
        is UiAction.EnhanceAndRead->vm.enhanceAndRead(action.recipe)
        is UiAction.ReviewAndContinue->vm.reviewAndContinue(action.value,action.next)
        is UiAction.NamedNavigation->vm.namedNavigation(action.title,action.route,action.regionId)
        is UiAction.NamedBack->vm.namedBack(action.title)
        is UiAction.ReadForExport->vm.readForExport(action.title)
        is UiAction.CreatePdf->vm.createPdf(action.title,action.mode)
        is UiAction.SaveDefaults->vm.saveDefaultsAndReturn(action.value)
        is UiAction.Error->vm.notifyError(action.message)
        UiAction.Detect->vm.detect()
        UiAction.AddRegion->vm.addRegion()
        UiAction.SplitRegions->vm.splitDetected()
        UiAction.Retake->vm.retake()
        UiAction.Read->vm.recognize()
        UiAction.ReadAll->vm.recognizeAll()
        is UiAction.Review->vm.review(action.value)
        is UiAction.Label->vm.label(action.id,action.side,action.label)
        UiAction.ConfirmCards->vm.confirmCards()
        is UiAction.ConfirmCardLabels->vm.confirmCardLabels(action.edits)
        is UiAction.Rename->vm.rename(action.value)
        is UiAction.Move->vm.move(action.id,action.delta)
        is UiAction.Remove->vm.remove(action.id)
        is UiAction.Layout->vm.layout(action.template,action.paper,action.margin)
        is UiAction.Export->vm.export(action.mode)
        UiAction.SavePdf->ui.export?.let{copyPath=it.path;copyPicker.launch(pdfFileName(ui.document!!.document.title))}
        UiAction.SharePdf->{shareOptions=true}
        UiAction.OpenPdf->ui.export?.let{share(it,true)}
        UiAction.Delete->vm.delete()
        is UiAction.Query->query=action.value
        is UiAction.Defaults->vm.saveDefaults(action.value)
        is UiAction.Lab->vm.lab(action.stage)
        UiAction.Cancel->vm.cancel()
    }},camera={CameraCapture(onCaptured={cameraFiles=ArrayList(cameraFiles+it.absolutePath)},onError=vm::notifyError,onImport={picker.launch(arrayOf("image/*"))},count=cameraFiles.size,thumbnails=cameraFiles,cards=ui.isCards,paired=ui.workflow=="paired",onDone={
        val files=cameraFiles.map(::File);vm.importImages(files.map(Uri::fromFile),temporaries=files,onSuccess={cameraFiles=arrayListOf()})
    })})
    if(leaveCamera)AlertDialog(onDismissRequest={leaveCamera=false},title={Text("Keep this scan?")},text={SilkCaption("Your captured pages can stay as an editable draft.")},confirmButton={TextButton(onClick={
        leaveCamera=false;val files=cameraFiles.map(::File);vm.importImages(files.map(Uri::fromFile),temporaries=files,destination="01",onSuccess={cameraFiles=arrayListOf()})
    }){Text("Save draft")}},dismissButton={androidx.compose.foundation.layout.Column{
        TextButton(onClick={leaveCamera=false}){Text("Keep scanning")}
        TextButton(onClick={cameraFiles.forEach{File(it).delete()};cameraFiles=arrayListOf();leaveCamera=false;vm.back()}){Text("Discard captured photos")}
    }},containerColor=Silk.surface)

    if(shareOptions && ui.export!=null)ModalBottomSheet(onDismissRequest={shareOptions=false},containerColor=Silk.surface){
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(horizontal=24.dp,vertical=12.dp),verticalArrangement=androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)){
            Text("Share your document",style=MaterialTheme.typography.headlineSmall)
            SilkCaption(ui.document!!.document.title+".pdf")
            SilkButton("Share PDF","share"){shareOptions=false;share(ui.export!!,false)}
            SilkButton("Save PDF to files","download",false){shareOptions=false;copyPath=ui.export!!.path;copyPicker.launch(pdfFileName(ui.document!!.document.title))}
            SilkButton("Open in PDF reader","document",false){shareOptions=false;share(ui.export!!,true)}
        }
    }
    if(message!=null)AlertDialog(onDismissRequest=vm::clearMessage,title={Text("Could not finish")},text={SilkCaption(message!!)},confirmButton={TextButton(onClick=vm::clearMessage){Text("OK")}},containerColor=Silk.surface)
}

private fun pdfFileName(title:String):String {
    val clean=title.trim().replace('/','_').replace('\\','_').take(160).ifBlank{"Scan"}
    return (if(clean.endsWith(".pdf",ignoreCase=true))clean.dropLast(4) else clean)+".pdf"
}
