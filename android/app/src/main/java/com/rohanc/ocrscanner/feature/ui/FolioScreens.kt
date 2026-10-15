package com.rohanc.ocrscanner.feature.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.rohanc.ocrscanner.R
import com.rohanc.ocrscanner.core.*
import com.rohanc.ocrscanner.data.*
import com.rohanc.ocrscanner.feature.editor.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** All screen composition comes from the approved design/prototype; actions contain no engine work. */
@Composable fun FolioScreens(ui:ScannerUi,onAction:(UiAction)->Unit,camera:@Composable ()->Unit={}){
    val send=onAction
    val doc=ui.document
    val region=ui.region
    val subtitle=when(ui.route){
        "01"->"A place for every page.";"02"->"Choose what you are keeping.";"03"->if(ui.isCards)"ID cards" else "Document"
        "04"->"${(region?.position?:0)+1} of ${doc?.regions?.size?:0} ${if(ui.isCards)"card regions" else "pages"}"
        "05"->"${(region?.position?:0)+1} of ${doc?.regions?.size?:0} · Original kept";"06"->"Check the words against the page."
        "07"->"You decide which sides belong together.";"08"->"${doc?.regions?.size?:0} ${if(ui.isCards)"card regions" else "pages"} · Editable draft"
        "09"->"Give every page room to breathe.";"10"->"Choose how you want to keep it."
        "11"->if(ui.export?.mode=="TEXT")"Regenerated text" else "Searchable scan"
        "12"->"Saved in your library.";"13"->"Titles and recognized text.";"14"->"Make the next scan yours.";"15"->"One image. Every step.";else->""
    }
    val titles=mapOf("01" to "Your library","02" to "New scan","03" to "Camera","04" to "Adjust boundaries","05" to "Enhance","06" to "Review text","07" to "Pair your cards","08" to "Organize","09" to "Page layout","10" to "Create your PDF","11" to "PDF preview","12" to "Document","13" to "Search","14" to "Settings","15" to "Learning lab")
    if(ui.route=="boot"){
        var visible by remember{mutableStateOf(false)}
        val alpha by animateFloatAsState(if(visible)1f else 0f,label="Folio reveal")
        LaunchedEffect(Unit){visible=true;delay(1000);send(UiAction.Go(if(ui.defaults.introDone)"01" else "intro-1"))}
        Column(Modifier.fillMaxSize().background(Silk.surface).alpha(alpha),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
            Image(painterResource(R.drawable.folio_logo),"Folio logo",Modifier.size(88.dp));Spacer(Modifier.height(24.dp));Text("Folio",style=MaterialTheme.typography.headlineLarge);SilkCaption("A place for every page.")
        };return
    }
    if(ui.route.startsWith("intro")){Onboarding(ui,send);return}
    // Keep the editor's pending values outside the body slot so fixed footer actions use the same state.
    val recipeBase=remember(region?.id,region?.imageRevision){ui.recipe()}
    var corners by rememberSaveable(region?.id,region?.imageRevision,ui.detected,stateSaver=listSaver<Quad,Float>(save={it.points.flatMap{p->listOf(p.x,p.y)}},restore={Quad(it.chunked(2).map{p->Point(p[0],p[1])})})){mutableStateOf(ui.detected.firstOrNull()?:recipeBase.quad)}
    var filter by rememberSaveable(region?.id,region?.imageRevision){mutableStateOf(recipeBase.enhancement.name)}
    var rotation by rememberSaveable(region?.id,region?.imageRevision){mutableIntStateOf(recipeBase.rotation)}
    var brightness by rememberSaveable(region?.id,region?.imageRevision){mutableFloatStateOf(recipeBase.brightness)}
    var contrast by rememberSaveable(region?.id,region?.imageRevision){mutableFloatStateOf(recipeBase.contrast)}
    var review by rememberSaveable(region?.id,region?.textRevision){mutableStateOf(region?.reviewedText?:region?.recognizedText.orEmpty())}
    var name by rememberSaveable(doc?.document?.id,ui.route){mutableStateOf(doc?.document?.title.orEmpty())}
    val back={send(if(ui.route in listOf("08","10") && doc!=null && !ui.busy)UiAction.NamedBack(name) else UiAction.Back)}
    androidx.activity.compose.BackHandler(ui.route in listOf("08","10") && doc!=null && !ui.busy){back()}
    // The first Room emission can arrive after the restored route.
    LaunchedEffect(doc?.document?.id){if(name.isBlank())name=doc?.document?.title.orEmpty()}
    var paperChoice by rememberSaveable(doc?.document?.id){mutableStateOf(doc?.document?.paper?:"A4")}
    var output by rememberSaveable(doc?.document?.id){mutableStateOf("SEARCHABLE")}
    var previewPage by rememberSaveable(ui.export?.id){mutableIntStateOf(0)}
    var settings by rememberSaveable(ui.defaults,stateSaver=listSaver<ScannerDefaults,Any>(
        save={listOf(it.paper,it.quality,it.learningLab,it.introDone)},
        restore={ScannerDefaults(it[0] as String,it[1] as String,it[2] as Boolean,it[3] as Boolean)}
    )){mutableStateOf(ui.defaults)}
    val reviewFocus=remember{FocusRequester()}
    var tab by rememberSaveable{mutableStateOf("Image + text")}
    var stage by rememberSaveable{mutableStateOf("Original")}
    var remove by remember{mutableStateOf<String?>(null)}
    var deleting by remember{mutableStateOf(false)}
    var replaceRegions by remember{mutableStateOf(false)}
    // Pending pair edits belong to the screen, including cards currently off screen.
    var cardDraftJson by rememberSaveable(doc?.document?.id){mutableStateOf("{}")}
    val cardDrafts=org.json.JSONObject(cardDraftJson)
    fun cardEdit(r:RegionEntity):CardLabelEdit{
        val draft=cardDrafts.optJSONObject(r.id)
        return CardLabelEdit(r.id,draft?.optString("side")?:r.side,draft?.optString("label")?:r.pairLabel)
    }
    var layoutPage by rememberSaveable(doc?.document?.id){mutableIntStateOf(0)}
    val recipe=recipeBase.copy(rotation=rotation,enhancement=Enhancement.valueOf(filter),brightness=brightness,contrast=contrast)
    if(ui.route=="05" && region!=null){
        LaunchedEffect(region.id,recipe){if(recipe!=recipeBase || region.correctedPath==null)send(UiAction.Preview(recipe)) else send(UiAction.ClearPreview)}
        DisposableEffect(region.id){onDispose{send(UiAction.ClearPreview)}}
    }
    val valid=doc!=null && doc.regions.isNotEmpty()
    val ocrReady=valid && doc!!.regions.all{it.ocrRevision==it.imageRevision && it.recognizedText!=null}
    val sheets=remember(doc){runCatching{doc?.let{layoutSheets(it)}}}
    val footer:(@Composable ColumnScope.()->Unit)?=when(ui.route){
        "01"->{ {SilkButton("New scan","plus",enabled=!ui.busy){send(UiAction.NewScan)}} }
        "02"->{ {SilkButton("Use camera","camera",enabled=!ui.busy){send(UiAction.Go("03"))};SilkButton("Import images","image",false,!ui.busy){send(UiAction.Import)}} }
        "04"->{ {SilkButton("Apply & straighten","check",enabled=!ui.busy && valid){send(UiAction.Correct(recipe.copy(quad=corners),ui.editorReturn?:"05"))}} }
        "05"->{ {SilkButton(if(ui.editorReturn!=null)"Apply changes" else "Read text",if(ui.editorReturn!=null)"check" else "text",enabled=!ui.busy && valid){
            if(ui.editorReturn!=null)send(UiAction.Correct(recipe,ui.editorReturn)) else send(UiAction.EnhanceAndRead(recipe))
        }} }
        "06"->{ {SilkButton(if(ui.editorReturn!=null)"Save text" else "Continue","check",enabled=!ui.busy && region?.ocrRevision==region?.imageRevision && valid){send(UiAction.ReviewAndContinue(review,ui.editorReturn?:if(ui.isCards)"07" else "08"))}} }
        "07"->{ {SilkButton("Confirm cards","check",enabled=!ui.busy && valid){send(UiAction.ConfirmCardLabels(doc!!.regions.map(::cardEdit)))}} }
        "08"->{ {SilkButton("Arrange pages","layout",enabled=!ui.busy && valid){send(UiAction.NamedNavigation(name,"09"))}} }
        "09"->{ {SilkButton("Export options",enabled=!ui.busy && valid && sheets.isSuccess){send(UiAction.Go("10"))}} }
        "10"->{ {SilkButton(if(ocrReady)"Generate preview" else "Read text first",if(ocrReady)"document" else "text",enabled=!ui.busy && valid){if(ocrReady)send(UiAction.CreatePdf(name,output)) else send(UiAction.ReadForExport(name))}} }
        "11"->{ {SilkButton("Save to library","check",enabled=!ui.busy && ui.export!=null){send(UiAction.Go("12"))}} }
        "12"->{ {SilkButton(if(ui.export!=null)"Open PDF preview" else "Continue editing",if(ui.export!=null)"document" else "crop",enabled=!ui.busy){send(UiAction.Go(if(ui.export!=null)"11" else "08"))}} }
        "14"->{ {SilkButton("Save defaults","check",enabled=!ui.busy){send(UiAction.SaveDefaults(settings))}} }
        "15"->{ {SilkButton("Return to editor","check"){send(UiAction.Go("05"))}} }
        else->null
    }
    SilkScreen(ui.route,titles[ui.route]?:"Folio",subtitle,back=if(ui.route=="01")null else back,actions={when(ui.route){
        "01"->{SilkIconButton("search","Search library"){send(UiAction.Go("13"))};SilkIconButton("settings","Settings"){send(UiAction.Go("14"))}}
        "04"->SilkIconButton("rotate","Rotate page",!ui.busy){rotation=(rotation+1)%4}
        "05"->if(ui.defaults.learningLab)SilkIconButton("lab","Open learning lab"){send(UiAction.Go("15"))}
        "06"->SilkIconButton("rotate","Read text again",!ui.busy){send(UiAction.Read)}
        "08"->SilkIconButton("plus","Add pages",!ui.busy){send(UiAction.NamedNavigation(name,"02"))}
        "11"->SilkIconButton("settings","Change PDF format",!ui.busy){send(UiAction.Go("10"))}
        "12"->SilkIconButton("trash","Delete document",!ui.busy){deleting=true}
    }},footer=footer,scroll=ui.route!="03"){
        if(ui.busy){LinearProgressIndicator(Modifier.fillMaxWidth(),color=Silk.accent);Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){SilkCaption("Processing on this device…",Modifier.weight(1f));TextButton(onClick={send(UiAction.Cancel)}){Text("Cancel")}}}
        if(ui.route in listOf("04","05","06","07","08","09","10","11","12","15") && doc==null){SilkEmpty("Opening your scan","Your pages will appear here.");return@SilkScreen}
        if(doc?.document?.deleting==true){SilkEmpty("Deletion needs another try","Some private files could not be removed.");SilkButton("Retry deletion","trash"){send(UiAction.Delete)};return@SilkScreen}
        when(ui.route){
            "01"->LibraryScreen(ui,send)
            "02"->{
                SilkTitle("Start with the right frame.","Choose a workflow. You can review every page before saving.")
                Spacer(Modifier.height(4.dp))
                SilkChoice("Document","Single pages or a whole stack.","document",!ui.isCards,!ui.busy && doc?.regions.isNullOrEmpty()){send(UiAction.Mode("DOCUMENT"))}
                SilkChoice("ID cards","Keep cards together on one page.","cards",ui.isCards,!ui.busy && doc?.regions.isNullOrEmpty()){send(UiAction.Mode("CARDS"))}
                if(ui.isCards){Text("Card workflow",style=MaterialTheme.typography.labelMedium);SilkSegments(listOf("paired","multiple"),ui.workflow,!ui.busy && doc==null,label={if(it=="paired")"Front & back" else "Multiple cards"}){send(UiAction.Workflow(it))};SilkCaption(if(ui.workflow=="paired")"Capture the front and back. Confirm the pair yourself." else "Photograph separated cards. Review each region individually.")}
                Row(Modifier.padding(top=20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){SilkIcon("scan");SilkCaption("Capture first. Fine-tune the details next.")}
            }
            "03"->camera()
            "04"->{
                if(!valid){AddPage(send);return@SilkScreen}
                SilkCaption("Drag the four corners to the edges of ${if(ui.isCards)"the card" else "the page"}.")
                Box(Modifier.fillMaxWidth().height(330.dp).silkPanel(inset=true,radius=20.dp).padding(8.dp)){
                    val c=ui.capture!!;CropEditor(c.uprightPath,c.width,c.height,corners,{corners=it},Modifier.fillMaxSize())
                }
                Row(horizontalArrangement=Arrangement.spacedBy(14.dp)){
                    Box(Modifier.weight(1f)){SilkButton("Reset corners","crop",false,!ui.busy){corners=Quad.full(ui.capture!!.width,ui.capture!!.height)}}
                    Box(Modifier.weight(1f)){SilkButton(if(ui.isCards)"Add region" else "Retake",if(ui.isCards)"plus" else "camera",false,!ui.busy){send(if(ui.isCards)UiAction.AddRegion else UiAction.Retake)}}
                }
                RegionStrip(ui,send)
                TextButton(enabled=!ui.busy,onClick={send(UiAction.Detect)}){Text("Find boundaries")}
                if(ui.detected.size>1 && ui.isCards)SilkButton("Use ${ui.detected.size} detected cards","cards",false,!ui.busy){replaceRegions=true}
            }
            "05"->{
                if(!valid){AddPage(send);return@SilkScreen}
                ImageWell(ui.previewPath?:ui.image,302.dp)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    listOf("ORIGINAL" to "Original","COLOR" to "Color","GRAYSCALE" to "Grayscale","BLACK_WHITE" to "B&W").forEach{(value,label)->
                        Column(Modifier.weight(1f).clickable(enabled=!ui.busy,role=Role.RadioButton){filter=value}.semantics{selected=filter==value}.padding(vertical=3.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(9.dp)){
                            Box(Modifier.fillMaxWidth().height(54.dp).silkPanel(inset=filter==value,radius=12.dp),contentAlignment=Alignment.Center){SilkIcon("document",color=if(filter==value)Silk.accent else Silk.muted)}
                            Text(label,style=MaterialTheme.typography.labelSmall,color=if(filter==value)Silk.accentText else Silk.muted)
                        }
                    }
                }
                SilkRange("Brightness",(100+brightness).toInt().toString()+"%",brightness,-60f..60f,!ui.busy && filter!="ORIGINAL"){brightness=it}
                SilkRange("Contrast",(contrast*100).toInt().toString()+"%",contrast,.5f..1.8f,!ui.busy && filter!="ORIGINAL"){contrast=it}
                // Rotation is a useful compact tool; processing controls remain in their approved places.
                TextButton(enabled=!ui.busy,onClick={rotation=(rotation+1)%4}){SilkIcon("rotate");Spacer(Modifier.width(8.dp));Text("Rotate 90°")}
            }
            "06"->{
                if(!valid){AddPage(send);return@SilkScreen}
                SilkSegments(listOf("Image + text","Text only"),tab){tab=it}
                if(region?.ocrRevision==null)SilkCaption("This page has not been read yet. Use Read text again to begin.")
                else if(region.recognizedText.isNullOrBlank())SilkCaption("No text found. Try a clearer image, then read again.")
                if(tab=="Image + text")Box(Modifier.clickable(enabled=region?.ocrRevision!=null,role=Role.Button){reviewFocus.requestFocus()}.semantics{contentDescription="Edit highlighted text"}){ImageWell(ui.image,250.dp);WordOverlay(ui,250.dp,firstOnly=true)}
                SilkField(review,{review=it},"Recognized text",singleLine=false,minLines=7,enabled=!ui.busy && region?.ocrRevision==region?.imageRevision,requester=reviewFocus)
                SilkCaption("Edits stay in your draft. Large rewrites need alignment review.")
                if((doc?.regions?.size?:0)>1)RegionStrip(ui,send)
            }
            "07"->{
                doc!!.regions.sortedBy{it.position}.forEach{r->CardPair(r,doc,cardEdit(r),ui.busy){edit->
                    cardDraftJson=org.json.JSONObject(cardDraftJson).put(r.id,org.json.JSONObject().put("side",edit.side).put("label",edit.label)).toString()
                }}
                SilkCaption("Unknown card types are allowed. Pairing is always confirmed by you.")
                SilkButton("Capture missing side","plus",false,!ui.busy){send(UiAction.Go("02"))}
            }
            "08"->{
                SilkField(name,{name=it},"Document name")
                SilkCaption("Use the arrows to reorder. Every edit keeps your original.")
                if(!valid)AddPage(send)
                doc!!.regions.sortedBy{it.position}.forEach{r->OrganizerCard(r,doc,ui.busy,{route->send(UiAction.NamedNavigation(name,route,r.id))},{delta->send(UiAction.Move(r.id,delta))},{remove=r.id})}
                if(ui.isCards && valid)SilkButton("Review card pairs","cards",false,!ui.busy){send(UiAction.NamedNavigation(name,"07"))}
            }
            "09"->{
                val document=doc!!
                Text("Paper size",style=MaterialTheme.typography.labelMedium)
                SilkSegments(listOf("A4","Letter","Auto"),paperChoice,!ui.busy){
                    paperChoice=it
                    val ratio=ui.capture?.let{c->c.width.toFloat()/c.height}?:.707f
                    val paper=if(it=="Auto")if(kotlin.math.abs(ratio-595f/842)<kotlin.math.abs(ratio-612f/792))"A4" else "Letter" else it
                    send(UiAction.Layout(doc!!.document.template,paper,document.document.margin))
                }
                if(sheets.isSuccess && !sheets.getOrNull().isNullOrEmpty()){
                    Column(Modifier.fillMaxWidth().silkPanel(inset=true,radius=20.dp).padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)){
                        val pages=sheets.getOrThrow()!!
                        val index=layoutPage.coerceIn(pages.indices)
                        PageSketch(pages[index],ui,Modifier.fillMaxWidth().height(345.dp))
                        if(pages.size>1)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                            TextButton(enabled=index>0,onClick={layoutPage=index-1}){Text("Previous sheet")}
                            Text("${index+1}/${pages.size}",style=MaterialTheme.typography.labelSmall)
                            TextButton(enabled=index<pages.lastIndex,onClick={layoutPage=index+1}){Text("Next sheet")}
                        }
                        Text("${sheets.getOrThrow()!!.size} ${if(sheets.getOrThrow()!!.size==1)"sheet" else "sheets"} · ${document.document.paper} · aspect ratio preserved",style=MaterialTheme.typography.labelSmall,color=Silk.muted,textAlign=TextAlign.Center)
                    }
                }else SilkCaption(sheets.exceptionOrNull()?.message?:"Add a page to arrange your scan.")
                if(ui.isCards){Text("Card arrangement",style=MaterialTheme.typography.labelMedium);SilkSegments(listOf("Stacked","Side by side","Grid"),document.document.template,!ui.busy){send(UiAction.Layout(it,document.document.paper,document.document.margin))}}
                Text("Margins",style=MaterialTheme.typography.labelMedium)
                SilkSegments(listOf("Compact","Comfortable"),if(document.document.margin==36f)"Comfortable" else "Compact",!ui.busy){send(UiAction.Layout(document.document.template,document.document.paper,if(it=="Comfortable")36f else 18f))}
                SilkCaption(if(ui.isCards)"Fit to page. Actual card dimensions have not been supplied." else "Page proportions are preserved. Nothing is stretched.")
            }
            "10"->{
                SilkTitle("Same page.\nTwo possibilities.","Keep the scan, or create a clean text document.")
                SilkChoice("Searchable scan","Original appearance with a text layer.","scan",output=="SEARCHABLE",!ui.busy){output="SEARCHABLE"}
                SilkChoice("Regenerated text","Reviewed text in a new paragraph layout.","text",output=="TEXT",!ui.busy){output="TEXT"}
                SilkField(name,{name=it},"File name",suffix=".pdf")
                Column(verticalArrangement=Arrangement.spacedBy(14.dp)){
                    Summary("Pages / regions",doc!!.regions.size.toString());Summary("Paper",doc.document.paper);Summary("Processing","On device")
                }
                if(output=="TEXT")SilkCaption("Simple paragraphs. Photos, signatures, and original card layouts are not recreated.")
                if(!ocrReady)SilkCaption("Read and review every page before creating the preview.")
            }
            "11"->{
                val export=ui.export
                if(export==null){SilkEmpty("Your PDF starts here","Create a preview to inspect your finished pages.");SilkButton("Create PDF","document"){send(UiAction.Go("10"))}}
                else{
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(doc!!.document.title+".pdf",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall);Text("${previewPage+1}/${export.pageCount}",style=MaterialTheme.typography.labelSmall)}
                    if(export.sourceRevision!=doc?.document?.revision)SilkCaption("Previous export · newer edits are not included.")
                    Box(Modifier.fillMaxWidth().silkPanel(inset=true,radius=18.dp).padding(20.dp)){PdfPage(export.path,previewPage,Modifier.fillMaxWidth().height(400.dp)){send(UiAction.Error(it))}}
                    SilkCaption(if(export.mode=="TEXT")"Newly typeset text; the original scan is kept." else "Original appearance, with searchable recognized text.")
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(enabled=previewPage>0,onClick={previewPage--}){SilkIcon("back");Text("Previous")};TextButton(enabled=previewPage+1<export.pageCount,onClick={previewPage++}){Text("Next");SilkIcon("next")}}

                }
            }
            "12"->{
                ImageWell(ui.image,265.dp)
                Text(doc!!.document.title,style=MaterialTheme.typography.headlineMedium)
                SilkCaption("${doc.regions.size} ${if(ui.isCards)"card regions" else "pages"} · ${when(ui.export?.mode){"TEXT"->"Regenerated text";"SEARCHABLE"->"Searchable scan";else->"Editable draft"}}")
                Row(horizontalArrangement=Arrangement.spacedBy(16.dp)){
                    Box(Modifier.weight(1f)){SilkButton("Edit pages","crop",false,!ui.busy){send(UiAction.Go("08"))}}
                    if(ui.export!=null)Box(Modifier.weight(1f)){SilkButton("Share","share",false,!ui.busy){send(UiAction.SharePdf)}}
                }
                SilkCaption(if(ui.export==null)"Your originals and edits are saved on this device." else "Your original scan and completed PDFs are kept on this device.")
            }
            "13"->{
                Row(Modifier.fillMaxWidth().silkPanel(inset=true,radius=14.dp).padding(horizontal=15.dp),verticalAlignment=Alignment.CenterVertically){SilkIcon("search");androidx.compose.foundation.text.BasicTextField(ui.query,{send(UiAction.Query(it))},singleLine=true,textStyle=MaterialTheme.typography.bodyMedium,modifier=Modifier.weight(1f).padding(15.dp).semantics{contentDescription="Find a word or document"},decorationBox={inner->if(ui.query.isBlank())Text("Find a word or document",style=MaterialTheme.typography.bodySmall,color=Silk.muted);inner()})}
                if(ui.query.isBlank())SilkEmpty("Your words, within reach","Search document names and recognized text.")
                else if(ui.searchResults.isEmpty())SilkEmpty("No matching documents.","Try another word or read the text in your scan first.")
                else ui.searchResults.forEach{item->Row(Modifier.fillMaxWidth().clickable(role=Role.Button){send(UiAction.Open(item.id))}.padding(vertical=20.dp),horizontalArrangement=Arrangement.spacedBy(15.dp),verticalAlignment=Alignment.CenterVertically){SilkIcon("document");Column(Modifier.weight(1f)){Text(item.title,style=MaterialTheme.typography.titleSmall);SilkCaption("${item.pages} pages · Saved on this device")};SilkIcon("next")};HorizontalDivider(color=Silk.line)}
            }
            "14"->{
                Column(Modifier.fillMaxWidth().silkPanel(inset=true,radius=19.dp).padding(horizontal=18.dp)){
                    SettingRow("Paper size","Default for new scans"){SilkSelect(listOf("A4","Letter"),settings.paper){settings=settings.copy(paper=it)}}
                    SettingRow("Scan quality","Original capture always kept"){SilkSelect(listOf("High","Balanced"),settings.quality){settings=settings.copy(quality=it)}}
                    SettingRow("OCR script","On-device recognition"){SilkCaption("Latin")}
                    Row(Modifier.fillMaxWidth().padding(vertical=15.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Learning lab",style=MaterialTheme.typography.titleSmall);SilkCaption("See each processing stage")};Checkbox(settings.learningLab,{settings=settings.copy(learningLab=it)},colors=CheckboxDefaults.colors(checkedColor=Silk.accent))}
                }
                Text("About Folio",style=MaterialTheme.typography.labelMedium)
                SilkCaption("Scan, review and create PDFs on this device. No account is needed.")
                SilkButton("Replay onboarding","scan",false){send(UiAction.Go("intro-1"))}
            }
            "15"->{
                Text("Processing stage",style=MaterialTheme.typography.labelMedium)
                SilkSelect(listOf("Original","Grayscale","Blur","Edges","Corners","Warp","OCR boxes"),stage,fullWidth=true,enabled=!ui.busy){stage=it;if(it in listOf("Grayscale","Blur","Edges","Warp"))send(UiAction.Lab(it))}
                Box {
                    ImageWell(when(stage){"Original","Corners"->ui.capture?.uprightPath;"OCR boxes"->ui.image;else->ui.labPath},300.dp)
                    if(stage=="Corners" && ui.capture!=null)QuadOverlay(recipeBase.quad,ui.capture!!.width,ui.capture!!.height,300.dp)
                    if(stage=="OCR boxes")WordOverlay(ui,300.dp)
                }
                Text(stage,style=MaterialTheme.typography.headlineSmall)
                SilkCaption(when(stage){"Original"->"The capture is a grid of color measurements. Keep it unchanged as your source.";"Grayscale"->"Color channels become intensity values. Shape and brightness remain.";"Edges"->"Rapid changes in intensity become boundary candidates, not necessarily the paper edge.";"Blur"->"Neighboring pixels are averaged to suppress small variations. Too much loses detail.";"Corners"->"Four ordered corners describe the flat surface we want to straighten.";"Warp"->"A projective transform maps the tilted surface to a rectangle.";else->"Word positions link recognized text to the image and the exported PDF."})
            }
        }
    }
    if(remove!=null)SilkConfirm("Remove this page?","The original capture remains available in this draft.",{remove=null}){send(UiAction.Remove(remove!!));remove=null}
    if(deleting)SilkConfirm("Delete this document?","Remove its private images, text and PDFs. Shared copies stay outside Folio.",{deleting=false}){deleting=false;send(UiAction.Delete)}
    if(replaceRegions)SilkConfirm("Replace this photo’s regions?","Review the detected cards after replacing this photo’s existing regions and text.",{replaceRegions=false}){replaceRegions=false;send(UiAction.SplitRegions)}
}

@Composable private fun Onboarding(ui:ScannerUi,send:(UiAction)->Unit){
    val second=ui.route=="intro-2"
    SilkScreen(ui.route,"Folio",actions={TextButton(enabled=!ui.busy,onClick={send(UiAction.IntroDone)}){Text("Skip",style=MaterialTheme.typography.labelMedium,color=Silk.accentText)}},footer={
        Row(horizontalArrangement=Arrangement.spacedBy(5.dp),modifier=Modifier.semantics{contentDescription="Step ${if(second)2 else 1} of 2"}){repeat(2){i->Box(Modifier.width(if((i==1)==second)18.dp else 5.dp).height(5.dp).background(if((i==1)==second)Silk.accent else Color(0xFFC4C9D8),CircleShape))}}
        SilkButton(if(second)"Start scanning" else "Continue",if(second)"scan" else "next",enabled=!ui.busy){send(if(second)UiAction.IntroDone else UiAction.Go("intro-2"))}
    }){
        Spacer(Modifier.height(18.dp))
        Image(painterResource(if(second)R.drawable.onboarding_text else R.drawable.onboarding_capture),if(second)"Paper lines highlighted for search" else "Paper transformed into a straight scan",Modifier.fillMaxWidth().heightIn(max=330.dp).aspectRatio(1f))
        Text(if(second)"Paper, made\nsearchable." else "A clearer way\nto keep things.",style=MaterialTheme.typography.headlineLarge.copy(fontSize=35.sp,lineHeight=44.sp))
        Text(if(second)"Find the words. Create the PDF you need." else "Scan documents and cards. Keep every detail.",style=MaterialTheme.typography.bodyLarge,color=Silk.muted)
    }
}
@Composable private fun LibraryScreen(ui:ScannerUi,send:(UiAction)->Unit){
    var filter by rememberSaveable{mutableStateOf("All")}
    val items=ui.library.filter{filter=="All" || (filter=="Cards")== (it.mode=="CARDS")}
    SilkSegments(listOf("All","Documents","Cards"),filter){filter=it}
    Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.SpaceBetween){Text("YOUR DOCUMENTS",style=MaterialTheme.typography.labelSmall,color=Silk.muted);Text(items.size.toString(),style=MaterialTheme.typography.labelSmall,color=Silk.muted)}
    if(items.isEmpty())SilkEmpty("A little room for paper","Your next scan will appear here.")
    else items.forEach{item->
        Row(Modifier.fillMaxWidth().clickable(role=Role.Button){send(UiAction.Open(item.id))}.padding(vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(17.dp),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.size(66.dp,86.dp).silkPanel(radius=12.dp).padding(8.dp),contentAlignment=Alignment.Center){if(item.image!=null)ScanImage(item.image,item.title,Modifier.fillMaxSize(),maxSide=200) else SilkIcon(if(item.mode=="CARDS")"cards" else "document")}
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)){Text(item.title,style=MaterialTheme.typography.titleMedium);SilkCaption("${item.pages} ${if(item.pages==1)"page" else "pages"} · ${dateLabel(item.updatedAt)}");Text(if(item.deleting)"Deletion pending" else item.status,style=MaterialTheme.typography.labelSmall,color=Silk.accentText)}
            SilkIcon("next",Modifier.size(16.dp),Color(0xFF9BA1B1))
        };HorizontalDivider(color=Silk.line)
    }
    Row(Modifier.fillMaxWidth().padding(vertical=25.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){SilkIcon("folder",Modifier.size(14.dp),Silk.muted);Spacer(Modifier.width(7.dp));Text("Saved on this device",style=MaterialTheme.typography.labelSmall,color=Silk.muted)}
}
@Composable fun ImageWell(path:String?,height:Dp,rotation:Float=0f,colorFilter:ColorFilter?=null,soften:Boolean=false){
    Box(Modifier.fillMaxWidth().height(height).silkPanel(inset=true,radius=20.dp).clip(RoundedCornerShape(20.dp)).padding(20.dp),contentAlignment=Alignment.Center){
        if(path!=null)ScanImage(path,"Current page",Modifier.fillMaxSize().rotate(rotation).then(if(soften)Modifier.blur(1.dp) else Modifier),colorFilter=colorFilter) else SilkCaption("Choose a page to see its preview.")
    }
}
@Composable private fun RegionStrip(ui:ScannerUi,send:(UiAction)->Unit){
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
        ui.document?.regions?.sortedBy{it.position}?.forEach{r->
            val capture=ui.document.captures.single{it.id==r.captureId}
            Box(Modifier.size(56.dp,76.dp).silkPanel(inset=r.id==ui.region?.id,radius=9.dp).border(if(r.id==ui.region?.id)1.dp else 0.dp,if(r.id==ui.region?.id)Silk.accent else Color.Transparent,RoundedCornerShape(9.dp)).clickable{send(UiAction.Select(r.id))}.semantics{contentDescription="Select page ${r.position+1}";selected=r.id==ui.region?.id}.padding(6.dp)){
                ScanImage(r.correctedPath?:capture.uprightPath,"Page ${r.position+1}",Modifier.fillMaxSize(),maxSide=160)
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SilkRange(label:String,valueLabel:String,value:Float,range:ClosedFloatingPointRange<Float>,enabled:Boolean,onChange:(Float)->Unit){
    Column{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label,style=MaterialTheme.typography.labelMedium);Text(valueLabel,style=MaterialTheme.typography.labelMedium,color=Silk.accentText)}
        Slider(value,onChange,enabled=enabled,valueRange=range,modifier=Modifier.fillMaxWidth().semantics{contentDescription=label},
            thumb={Box(Modifier.size(14.dp).background(if(enabled)Silk.accent else Silk.muted.copy(alpha=.4f),CircleShape))},
            track={state->Canvas(Modifier.fillMaxWidth().height(4.dp)){
                val y=size.height/2;val fraction=((state.value-range.start)/(range.endInclusive-range.start)).coerceIn(0f,1f)
                drawLine(Silk.line,androidx.compose.ui.geometry.Offset(0f,y),androidx.compose.ui.geometry.Offset(size.width,y),3.dp.toPx(),StrokeCap.Round)
                drawLine(if(enabled)Silk.accent else Silk.muted.copy(alpha=.4f),androidx.compose.ui.geometry.Offset(0f,y),androidx.compose.ui.geometry.Offset(size.width*fraction,y),3.dp.toPx(),StrokeCap.Round)
            }})
    }
}
@Composable private fun CardPair(r:RegionEntity,doc:DocumentSnapshot,edit:CardLabelEdit,busy:Boolean,onChange:(CardLabelEdit)->Unit){
    val side=edit.side
    val label=edit.label
    val capture=doc.captures.single{it.id==r.captureId}
    Column(Modifier.fillMaxWidth().silkPanel(radius=18.dp).padding(17.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        ScanImage(r.correctedPath?:capture.uprightPath,"Card ${r.position+1}",Modifier.fillMaxWidth().height(155.dp))
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("Region ${r.position+1}",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);SilkSelect(listOf("FRONT","BACK","SINGLE"),side,enabled=!busy,label={it.lowercase().replaceFirstChar(Char::uppercase)}){onChange(edit.copy(side=it))}}
        if(side!="SINGLE")SilkField(label,{onChange(edit.copy(label=it))},"Pair label",enabled=!busy)
        SilkCaption(if(side=="SINGLE")"Single-sided card" else "Use the same label for matching front and back.")
    }
}
@Composable private fun OrganizerCard(r:RegionEntity,doc:DocumentSnapshot,busy:Boolean,edit:(String)->Unit,move:(Int)->Unit,remove:()->Unit){
    val capture=doc.captures.single{it.id==r.captureId}
    Column(Modifier.fillMaxWidth().silkPanel(radius=17.dp).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
            ScanImage(r.correctedPath?:capture.uprightPath,"Page ${r.position+1}",Modifier.size(58.dp,78.dp),maxSide=180)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){Text("Page ${r.position+1}",style=MaterialTheme.typography.titleSmall);SilkCaption(if(doc.document.mode=="CARDS")r.pairLabel.ifBlank{"Unlabeled card"}+" · "+r.side.lowercase() else "Document page")}
            Column{if(r.position>0)SilkIconButton("up","Move page ${r.position+1} up",!busy){move(-1)};if(r.position<doc.regions.size-1)SilkIconButton("down","Move page ${r.position+1} down",!busy){move(1)}}
        }
        HorizontalDivider(color=Silk.line)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
            listOf(Triple("crop","Crop","04"),Triple("scan","Enhance","05"),Triple("text","Text","06")).forEach{(icon,label,route)->
                Row(Modifier.heightIn(min=48.dp).clickable(enabled=!busy,role=Role.Button){edit(route)}.padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){SilkIcon(icon,Modifier.size(15.dp),Silk.muted);Text(label,style=MaterialTheme.typography.labelSmall)}
            };SilkIconButton("trash","Delete page ${r.position+1}",!busy,remove)
        }
    }
}
@Composable fun PageSketch(sheet:Sheet,ui:ScannerUi,modifier:Modifier=Modifier){
    BoxWithConstraints(modifier,contentAlignment=Alignment.Center){
        val width=minOf(maxWidth,maxHeight*(sheet.width/sheet.height));val height=width*(sheet.height/sheet.width)
        Box(Modifier.size(width,height).background(Color(0xFFFBFBF9)).border(.5.dp,Color(0xFFE1E3E8))){
            sheet.placements.forEach{p->val r=ui.document?.regions?.firstOrNull{it.id==p.regionId};val c=ui.document?.captures?.firstOrNull{it.id==r?.captureId}
                if(r!=null && c!=null)ScanImage(r.correctedPath?:c.uprightPath,"Sheet region ${r.position+1}",Modifier.offset(x=width*(p.x/sheet.width),y=height*(p.y/sheet.height)).size(width*(p.width/sheet.width),height*(p.height/sheet.height)),maxSide=500)
            }
        }
    }
}
@Composable private fun Summary(label:String,value:String){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){SilkCaption(label);Text(value,style=MaterialTheme.typography.bodySmall)}}
@Composable private fun SettingRow(title:String,description:String,control:@Composable ()->Unit){Column{Row(Modifier.fillMaxWidth().padding(vertical=20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(title,style=MaterialTheme.typography.titleSmall);SilkCaption(description)};control()};HorizontalDivider(color=Silk.line)}}
@Composable private fun SilkSelect(options:List<String>,selected:String,fullWidth:Boolean=false,enabled:Boolean=true,label:(String)->String={it},onSelect:(String)->Unit){
    var open by remember{mutableStateOf(false)}
    Box{
        Row(Modifier.then(if(fullWidth)Modifier.fillMaxWidth().silkPanel(inset=true,radius=12.dp) else Modifier).heightIn(min=48.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled=enabled,role=Role.DropdownList){open=true}.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Text(label(selected),Modifier.then(if(fullWidth)Modifier.weight(1f) else Modifier),style=MaterialTheme.typography.bodySmall);SilkIcon("down",Modifier.size(14.dp),Silk.muted)
        }
        DropdownMenu(open,{open=false},containerColor=Silk.surface){options.forEach{item->DropdownMenuItem(text={Text(label(item))},onClick={onSelect(item);open=false})}}
    }
}
@Composable private fun AddPage(send:(UiAction)->Unit){SilkEmpty("Add a page first","Capture or import a photo to continue.");SilkButton("Add a page","plus"){send(UiAction.Go("02"))}}
@Composable private fun SilkConfirm(title:String,body:String,dismiss:()->Unit,confirm:()->Unit){AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={SilkCaption(body)},confirmButton={TextButton(onClick=confirm){Text("Confirm")}},dismissButton={TextButton(onClick=dismiss){Text("Keep")}},containerColor=Silk.surface)}
private fun dateLabel(time:Long)=if(System.currentTimeMillis()-time<86400000)"Today" else if(System.currentTimeMillis()-time<172800000)"Yesterday" else SimpleDateFormat("d MMM",Locale.getDefault()).format(Date(time))
fun layoutSheets(doc:DocumentSnapshot):List<Sheet> = PageLayout.sheets(doc.regions.sortedBy{it.position}.map{r->val c=doc.captures.single{it.id==r.captureId};LayoutRegion(r.id,r.correctedWidth?:c.width,r.correctedHeight?:c.height,CardSide.valueOf(r.side),r.pairLabel)},doc.document.mode=="CARDS",doc.document.template,doc.document.paper,doc.document.margin)

@Composable private fun WordOverlay(ui:ScannerUi,height:Dp,firstOnly:Boolean=false){
    val r=ui.region?:return;val c=ui.capture?:return
    val polygons=remember(r.wordsJson,firstOnly){runCatching{val a=org.json.JSONArray(r.wordsJson);(0 until if(firstOnly)minOf(1,a.length()) else a.length()).map{i->val p=a.getJSONObject(i).getJSONArray("polygon");(0 until p.length()).map{j->Point(p.getJSONArray(j).getDouble(0).toFloat(),p.getJSONArray(j).getDouble(1).toFloat())}}}.getOrDefault(emptyList())}
    Canvas(Modifier.fillMaxWidth().height(height).padding(20.dp)){
        val w=r.correctedWidth?:c.width;val h=r.correctedHeight?:c.height
        val s=minOf(size.width/w,size.height/h);val o=androidx.compose.ui.geometry.Offset((size.width-w*s)/2,(size.height-h*s)/2)
        polygons.forEach{points->if(points.size>=3){val path=Path().apply{moveTo(o.x+points[0].x*s,o.y+points[0].y*s);points.drop(1).forEach{lineTo(o.x+it.x*s,o.y+it.y*s)};close()};drawPath(path,Silk.accent.copy(alpha=.17f));drawPath(path,Silk.accent,style=Stroke(1.dp.toPx()))}}
    }
}
@Composable private fun QuadOverlay(quad:Quad,width:Int,height:Int,displayHeight:Dp){
    Canvas(Modifier.fillMaxWidth().height(displayHeight).padding(20.dp)){
        val s=minOf(size.width/width,size.height/height);val o=androidx.compose.ui.geometry.Offset((size.width-width*s)/2,(size.height-height*s)/2)
        val path=Path().apply{moveTo(o.x+quad.points[0].x*s,o.y+quad.points[0].y*s);quad.points.drop(1).forEach{lineTo(o.x+it.x*s,o.y+it.y*s)};close()}
        drawPath(path,Silk.accent,style=Stroke(2.dp.toPx()));quad.points.forEach{drawCircle(Silk.accent,5.dp.toPx(),androidx.compose.ui.geometry.Offset(o.x+it.x*s,o.y+it.y*s))}
    }
}
