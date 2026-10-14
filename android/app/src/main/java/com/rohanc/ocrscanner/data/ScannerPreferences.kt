package com.rohanc.ocrscanner.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScannerDefaults(val paper:String="A4",val quality:String="High",val learningLab:Boolean=true,val introDone:Boolean=false)
class ScannerPreferences(context:Context) {
    private val prefs=context.getSharedPreferences("scanner-defaults",Context.MODE_PRIVATE)
    private val mutable=MutableStateFlow(ScannerDefaults(prefs.getString("paper","A4")?:"A4",prefs.getString("quality","High")?:"High",prefs.getBoolean("lab",true),prefs.getBoolean("intro",false)))
    val state=mutable.asStateFlow()
    fun save(value:ScannerDefaults) {
        require(value.paper in listOf("A4","Letter") && value.quality in listOf("High","Balanced"))
        check(prefs.edit().putString("paper",value.paper).putString("quality",value.quality).putBoolean("lab",value.learningLab).putBoolean("intro",value.introDone).commit()) { "Settings could not be saved." }
        mutable.value=value
    }
}
