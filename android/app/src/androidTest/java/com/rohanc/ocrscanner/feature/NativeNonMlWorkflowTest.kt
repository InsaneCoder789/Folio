package com.rohanc.ocrscanner.feature

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.rohanc.ocrscanner.MainActivity
import com.rohanc.ocrscanner.ScannerApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercise real screen -> ViewModel -> Room flows without invoking recognition. */
class NativeNonMlWorkflowTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ScannerApplication
    private fun click(text:String){val node=rule.onNodeWithText(text);if(runCatching{node.assertIsDisplayed()}.isFailure)node.performScrollTo();node.performClick()}
    private fun ready(text:String)=rule.waitUntil(15000){rule.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
    private fun withCards(count:Int,paired:Boolean,action:suspend(String)->Unit)=runBlocking{
        val repo=app.repository
        val doc=repo.create("Non-ML workflow",mode="CARDS",workflow=if(paired)"paired" else "multiple")
        val file=File.createTempFile("non-ml-",".jpg",app.cacheDir)
        val bitmap=Bitmap.createBitmap(240,160,Bitmap.Config.ARGB_8888)
        try{
            bitmap.eraseColor(android.graphics.Color.WHITE);file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)}
            repo.importImages(doc.document.id,0,List(count){Uri.fromFile(file)})
            rule.waitUntil(15000){rule.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithText("Your library").fetchSemanticsNodes().isNotEmpty()}
            if(rule.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty())click("Skip")
            ready("Non-ML workflow");click("Non-ML workflow");click("Edit pages")
            action(doc.document.id)
        }finally{
            bitmap.recycle();file.delete();val s=repo.snapshot(doc.document.id);repo.delete(s.document.id,s.document.revision)
        }
    }
    @Test fun confirmSavesPendingPairLabelsAfterRecreation()=withCards(2,true){id->
        click("Review card pairs");ready("Confirm cards")
        val fields=rule.onAllNodes(hasSetTextAction())
        fields[0].performScrollTo().performTextReplacement("Travel card")
        fields[1].performScrollTo().performTextReplacement("Travel card")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.activityRule.scenario.recreate();ready("Confirm cards")
        click("Confirm cards");ready("Arrange pages")
        val s=app.repository.snapshot(id)
        assertEquals(listOf("FRONT","BACK"),s.regions.sortedBy{it.position}.map{it.side})
        assertTrue(s.regions.all{it.pairLabel=="Travel card"})
    }
    @Test fun everyOverflowSheetCanBePreviewedBeforeExport()=withCards(6,false){_->
        click("Arrange pages");ready("Export options")
        click("Grid")
        ready("Next sheet");click("Next sheet")
        rule.waitUntil(5000){rule.onAllNodesWithContentDescription("Sheet region 5").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithContentDescription("Sheet region 5").assertIsDisplayed()
        rule.onNodeWithContentDescription("Sheet region 6").assertIsDisplayed()
        click("Previous sheet")
        rule.waitUntil(5000){rule.onAllNodesWithContentDescription("Sheet region 1").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithContentDescription("Sheet region 1").assertIsDisplayed()
    }
    @Test fun backSavesDocumentNameAndReturnsToItsDetails()=withCards(1,false){id->
        rule.onNode(hasSetTextAction()).performTextReplacement("Renamed card draft")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.onNodeWithContentDescription("Back").performClick()
        ready("Continue editing")
        assertEquals("Renamed card draft",app.repository.snapshot(id).document.title)
        rule.onNodeWithText("Document",useUnmergedTree=true).assertIsDisplayed()
    }
}
