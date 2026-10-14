package com.rohanc.ocrscanner.feature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.rohanc.ocrscanner.MainActivity
import com.rohanc.ocrscanner.ScannerApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NativeRecoveryTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val application get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ScannerApplication
    private fun click(text:String){val node=rule.onNodeWithText(text);if(runCatching{node.assertIsDisplayed()}.isFailure)node.performScrollTo();node.performClick()}
    private fun ready(text:String)=rule.waitUntil(15000){rule.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
    private fun library(){
        rule.waitUntil(15000){rule.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithText("Your library").fetchSemanticsNodes().isNotEmpty()}
        if(rule.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty())click("Skip")
    }
    private fun withPage(action:suspend(String)->Unit)=runBlocking {
        val repo=application.repository
        val doc=repo.create("Recovery acceptance")
        val file=File.createTempFile("recovery-",".jpg",application.cacheDir)
        try {
            val bitmap=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888)
            try {
                val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
                canvas.drawText("RECOVERY TEST",40f,160f,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=48f})
                file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)}
            } finally {bitmap.recycle()}
            repo.importImage(doc.document.id,0,Uri.fromFile(file))
            library();ready("Recovery acceptance");click("Recovery acceptance");click("Edit pages")
            action(doc.document.id)
        } finally {
            file.delete();val latest=repo.snapshot(doc.document.id);repo.delete(latest.document.id,latest.document.revision)
        }
    }
    @Test fun unsavedEnhancementSurvivesActivityRecreation()=withPage { id ->
        click("Enhance");click("Grayscale");click("Rotate 90°")
        rule.activityRule.scenario.recreate()
        ready("Apply changes")
        rule.onNodeWithText("Grayscale").assertIsSelected()
        click("Apply changes");ready("Arrange pages")
        val region=application.repository.snapshot(id).regions.single()
        assertEquals("GRAYSCALE",region.enhancement);assertEquals(1,region.rotation)
    }
    @Test fun unsavedReviewSurvivesActivityRecreation()=withPage { id ->
        val repo=application.repository
        repo.recognize(id,repo.snapshot(id).regions.single().id)
        click("Text");ready("Review text")
        rule.onNode(hasSetTextAction()).performTextReplacement("My unsaved reviewed paragraph.")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.activityRule.scenario.recreate()
        ready("Review text")
        rule.onNode(hasSetTextAction()).assertTextContains("My unsaved reviewed paragraph.")
        click("Save text")
        rule.waitUntil(10000){runBlocking{repo.snapshot(id).regions.single().reviewedText=="My unsaved reviewed paragraph."}}
    }
    @Test fun editorReturnDestinationIsStoredInSavedState(){
        val state=SavedStateHandle(mapOf("route" to "06","editorReturn" to "10"))
        val vm=ScannerViewModel(application,state)
        try{vm.back();assertEquals("10",vm.route.value);vm.editorReturn="08";assertEquals("08",state.get<String>("editorReturn"))}
        finally{androidx.lifecycle.ViewModelStore().apply{put("test",vm);clear()}}
    }
}
