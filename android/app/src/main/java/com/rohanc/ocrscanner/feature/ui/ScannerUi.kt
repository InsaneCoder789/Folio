package com.rohanc.ocrscanner.feature.ui

import com.rohanc.ocrscanner.core.*
import com.rohanc.ocrscanner.data.*

data class LibraryItem(val id:String,val title:String,val mode:String,val pages:Int,val image:String?,val status:String,val updatedAt:Long,val deleting:Boolean=false)
data class ScannerUi(
    val route:String="01",val library:List<LibraryItem> = emptyList(),val document:DocumentSnapshot?=null,
    val activeId:String?=null,val mode:String="DOCUMENT",val busy:Boolean=false,val defaults:ScannerDefaults=ScannerDefaults(),
    val detected:List<Quad> = emptyList(),val labPath:String?=null,val searchResults:List<LibraryItem> = emptyList(),val query:String="",
    val editorReturn:String?=null,val workflow:String="paired"
){
    val region get()=document?.regions?.firstOrNull{it.id==activeId}?:document?.regions?.minByOrNull{it.position}
    val capture get()=document?.captures?.firstOrNull{it.id==region?.captureId}
    val image get()=region?.correctedPath?:capture?.uprightPath
    val export get()=document?.exports?.maxByOrNull{it.createdAt}
    val isCards get()=(document?.document?.mode?:mode)=="CARDS"
    fun recipe():EditRecipe{
        val r=region;val c=capture?:return EditRecipe(Quad.full(1000,1400))
        val quad=if(r?.cornersJson.isNullOrBlank())Quad.full(c.width,c.height) else {
            val values=org.json.JSONArray(r!!.cornersJson)
            Quad((0..3).map{val p=values.getJSONArray(it);Point(p.getDouble(0).toFloat(),p.getDouble(1).toFloat())})
        }
        return EditRecipe(quad,r?.rotation?:0,Enhancement.valueOf(r?.enhancement?:"COLOR"),r?.brightness?:0f,r?.contrast?:1f)
    }
}
sealed interface UiAction{
    data class Go(val route:String):UiAction
    data object Back:UiAction
    data object NewScan:UiAction
    data class Open(val id:String):UiAction
    data class Workflow(val value:String):UiAction
    data class Mode(val value:String):UiAction
    data object Import:UiAction
    data object IntroDone:UiAction
    data class Select(val id:String):UiAction
    data class Edit(val id:String,val route:String):UiAction
    data class EnhanceAndRead(val recipe:EditRecipe):UiAction
    data class ReviewAndContinue(val value:String,val next:String):UiAction
    data class NamedNavigation(val title:String,val route:String,val regionId:String?=null):UiAction
    data class CreatePdf(val title:String,val mode:String):UiAction
    data class SaveDefaults(val value:ScannerDefaults):UiAction
    data class Error(val message:String):UiAction
    data class Correct(val recipe:EditRecipe,val next:String):UiAction
    data object Detect:UiAction
    data object AddRegion:UiAction
    data object SplitRegions:UiAction
    data object Retake:UiAction
    data object Read:UiAction
    data object ReadAll:UiAction
    data class Review(val value:String):UiAction
    data class Label(val id:String,val side:String,val label:String):UiAction
    data object ConfirmCards:UiAction
    data class Rename(val value:String):UiAction
    data class Move(val id:String,val delta:Int):UiAction
    data class Remove(val id:String):UiAction
    data class Layout(val template:String,val paper:String,val margin:Float):UiAction
    data class Export(val mode:String):UiAction
    data object SavePdf:UiAction
    data object SharePdf:UiAction
    data object OpenPdf:UiAction
    data object Delete:UiAction
    data class Query(val value:String):UiAction
    data class Defaults(val value:ScannerDefaults):UiAction
    data class Lab(val stage:String):UiAction
    data object Cancel:UiAction
}
