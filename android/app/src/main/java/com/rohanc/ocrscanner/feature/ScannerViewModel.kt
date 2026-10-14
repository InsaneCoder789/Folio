package com.rohanc.ocrscanner.feature

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.rohanc.ocrscanner.ScannerApplication
import com.rohanc.ocrscanner.core.*
import com.rohanc.ocrscanner.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
class ScannerViewModel(application:Application,private val saved:SavedStateHandle):AndroidViewModel(application) {
    val repository=(application as ScannerApplication).repository
    private val preferences=(application as ScannerApplication).preferences
    private val manager=WorkManager.getInstance(application)
    val defaults=preferences.state
    val library=repository.library.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    private val selected=saved.getStateFlow<String?>("document",null)
    val document=selected.flatMapLatest { id -> if(id==null) flowOf(null) else repository.observe(id) }
        .stateIn(viewModelScope,SharingStarted.Eagerly,null)
    val route=saved.getStateFlow("route","boot")
    val activeId=saved.getStateFlow<String?>("region",null)
    val mode=saved.getStateFlow("mode","DOCUMENT")
    private val working=MutableStateFlow(false)
    val worker=selected.flatMapLatest { id -> if(id==null) flowOf(null) else manager.getWorkInfosForUniqueWorkFlow("ocr:$id").map{it.firstOrNull { info -> !info.state.isFinished } ?: it.lastOrNull()} }
        .stateIn(viewModelScope,SharingStarted.Eagerly,null)
    val busy=combine(working,worker){b,w -> b || (w!=null && !w.state.isFinished)}
        .stateIn(viewModelScope,SharingStarted.Eagerly,false)
    private val _message=MutableStateFlow<String?>(null);val message=_message.asStateFlow()
    private val _detected=MutableStateFlow<List<Quad>>(emptyList());val detected=_detected.asStateFlow()
    private val _labPath=MutableStateFlow<String?>(null);val labPath=_labPath.asStateFlow()
    private var actionJob:Job?=null
    var editorReturn="08"
    init { viewModelScope.launch { worker.collect { info -> if(info?.state==WorkInfo.State.FAILED) _message.value=info.outputData.getString("error")?:"Text recognition failed. Retry Read all pages." } } }
    // Prefer active work; KEEP prevents overlapping recognition batches.
    fun clearMessage(){_message.value=null}
    fun go(value:String){saved["route"]=value}
    fun back(){when(route.value){"01"->Unit;"02","12","13","14"->go("01");"03"->{if(saved.get<Boolean>("retake")==true){saved["retake"]=false;go("05")}else go("02")};"04","05","06","07","09"->go(editorReturn);"10"->go("09");"11"->go("12");"15"->go("05");else->go("01")}}
    fun newScan(){saved["retake"]=false;saved["document"]=null;saved["region"]=null;saved["mode"]="DOCUMENT";go("02")}
    fun chooseMode(value:String){if(document.value?.regions.isNullOrEmpty()) saved["mode"]=value}
    fun open(id:String){saved["document"]=id;saved["region"]=null;go("12")}
    fun selectRegion(id:String){saved["region"]=id;_detected.value=emptyList()}
    fun editRegion(id:String,target:String){selectRegion(id);editorReturn="08";go(target)}
    private suspend fun ensureDocument():DocumentSnapshot {
        val id=selected.value
        if(id!=null) return repository.snapshot(id)
        val created=repository.create(mode=mode.value,paper=defaults.value.paper)
        saved["document"]=created.document.id
        return created
    }
    fun importImages(uris:List<Uri>,temporary:java.io.File?=null)=work {
        try {
        require(uris.size<=12){"Import at most twelve photos at once."}
        var current=ensureDocument()
        if(saved.get<Boolean>("retake")==true) {
            require(uris.size==1){"Choose one replacement image."}
            repository.retake(current.document.id,current.document.revision,active(current).id,uris.single())
            saved["retake"]=false;go("04");return@work
        }
        val priorIds=current.regions.map{it.id}.toSet()
        for(uri in uris) { repository.importImage(current.document.id,current.document.revision,uri,if(defaults.value.quality=="High")3000 else 2000);current=repository.snapshot(current.document.id) }
        current.regions.firstOrNull{it.id !in priorIds}?.let{selectRegion(it.id)}
        editorReturn="08";go("04")
        } finally { temporary?.delete() }
    }
    fun retake(){saved["retake"]=true;go("03")}
    fun rename(title:String)=edit{repository.rename(it.document.id,it.document.revision,title)}
    fun correct(recipe:EditRecipe,next:String)=edit { s -> repository.correct(s.document.id,s.document.revision,active(s).id,recipe);go(next) }
    fun detect()=work { val s=current();_detected.value=repository.candidates(s.document.id,active(s).id);if(_detected.value.isEmpty())_message.value="No clear boundaries found. Adjust the four corners manually." }
    fun splitDetected()=edit { s ->
        repository.splitCapture(s.document.id,s.document.revision,active(s).id,_detected.value)
        val updated=repository.snapshot(s.document.id);selectRegion(updated.regions.maxBy{it.position}.id);go("08")
    }
    fun addRegion()=edit { s -> val c=s.captures.single{it.id==active(s).captureId};repository.addRegion(s.document.id,s.document.revision,c.id);val n=repository.snapshot(s.document.id);selectRegion(n.regions.maxBy{it.position}.id);go("04") }
    fun recognize()=edit { repository.recognize(it.document.id,active(it).id);go("06") }
    fun recognizeAll(){val id=selected.value?:return;_message.value=null;manager.enqueueUniqueWork("ocr:$id",ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<OcrBatchWorker>().setInputData(workDataOf("documentId" to id)).build());go("06")}
    fun review(text:String)=edit { repository.saveReview(it.document.id,it.document.revision,active(it).id,text) }
    fun label(regionId:String,side:String,label:String)=edit { repository.labelCard(it.document.id,it.document.revision,regionId,side,label) }
    fun confirmCards()=work { val s=current();repository.layoutSheets(s);go("08") }
    fun layout(template:String,paper:String,margin:Float)=edit { repository.layout(it.document.id,it.document.revision,template,paper,margin) }
    fun move(regionId:String,delta:Int)=edit { s ->
        val ids=s.regions.sortedBy{it.position}.map{it.id}.toMutableList();val i=ids.indexOf(regionId);val to=i+delta
        if(i>=0 && to in ids.indices){ids.removeAt(i);ids.add(to,regionId);repository.reorder(s.document.id,s.document.revision,ids)}
    }
    fun remove(regionId:String)=edit { repository.removeRegion(it.document.id,it.document.revision,regionId) }
    fun export(output:String)=edit { repository.export(it.document.id,it.document.revision,output);go("11") }
    fun lab(stage:String)=work { val s=current();_labPath.value=repository.lab(s.document.id,active(s).id,stage).path }
    fun saveDefaults(value:ScannerDefaults)=work { withContext(Dispatchers.IO){preferences.save(value)} }
    fun finishIntro()=work {withContext(Dispatchers.IO){preferences.save(defaults.value.copy(introDone=true))};go("01")}
    fun delete()=work {val s=current();manager.cancelUniqueWork("ocr:${s.document.id}");repository.delete(s.document.id,s.document.revision);saved["document"]=null;go("01")}
    fun cancel(){actionJob?.cancel();selected.value?.let{manager.cancelUniqueWork("ocr:$it")}}
    fun notifyError(message:String){_message.value=message}
    fun active(snapshot:DocumentSnapshot)=snapshot.regions.firstOrNull{it.id==activeId.value}?:snapshot.regions.minBy{it.position}
    private suspend fun current()=selected.value?.let{repository.snapshot(it)}?:error("Open a document first.")
    private fun edit(action:suspend(DocumentSnapshot)->Unit)=work {action(current())}
    private fun work(action:suspend()->Unit) {
        if(working.value)return
        working.value=true
        actionJob=viewModelScope.launch {try{action()}catch(cancelled:CancellationException){throw cancelled}
            catch(failure:Exception){_message.value=failure.message?:"This action could not finish. Retry when ready."}finally{working.value=false}}
    }
}
