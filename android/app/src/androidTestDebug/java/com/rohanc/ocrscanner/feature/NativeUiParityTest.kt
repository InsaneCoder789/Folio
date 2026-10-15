package com.rohanc.ocrscanner.feature

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.rohanc.ocrscanner.feature.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeUiParityTest {
    private val footers=mapOf("01" to "New scan","02" to "Use camera","04" to "Apply & straighten","05" to "Read text","06" to "Continue","07" to "Confirm cards","08" to "Arrange pages","09" to "Export options","10" to "Generate preview","11" to "Save to library","12" to "Open PDF preview","14" to "Save defaults","15" to "Return to editor","intro-1" to "Continue","intro-2" to "Start scanning")
    @get:Rule val rule=createAndroidComposeRule<UiGalleryActivity>()
    @Test fun allApprovedScreensRenderAndHaveTheirOwnBottomAction(){
        val routes=(1..15).map{it.toString().padStart(2,'0')}+listOf("intro-1","intro-2")
        for(route in routes){
            rule.runOnUiThread{rule.activity.state=UiGalleryActivity.fixture(rule.activity,route,route=="07")}
            rule.waitForIdle();rule.onNodeWithTag("screen-$route").assertIsDisplayed()
            footers[route]?.let{rule.onNodeWithText(it).assertIsDisplayed().assertIsEnabled()}
            rule.waitUntil(5000){rule.onAllNodesWithText("Loading image…").fetchSemanticsNodes().isEmpty()}
            if(route=="11")rule.waitUntil(10000){rule.onAllNodesWithContentDescription("PDF page 1").fetchSemanticsNodes().isNotEmpty()}
            saveScreenshot(route)
        }
    }
    @Test fun prototypeJourneysEmitTheExpectedPageConnections(){
        rule.onNodeWithText("New scan").performClick()
        rule.onNodeWithText("Import images").performClick()
        rule.onNodeWithText("Apply & straighten").performClick()
        rule.onNodeWithText("Read text").performClick()
        rule.onNodeWithText("Continue").performClick()
        rule.onNodeWithText("Arrange pages").performClick()
        rule.onNodeWithText("Export options").performClick()
        rule.onNodeWithText("Generate preview").performClick()
        rule.onNodeWithText("Save to library").performClick()
        rule.onNodeWithText("Open PDF preview").performClick()
        rule.onNodeWithTag("screen-11").assertIsDisplayed()
        assertTrue(rule.activity.events.any{it is UiAction.EnhanceAndRead})
        assertTrue(rule.activity.events.any{it is UiAction.CreatePdf})
        rule.runOnUiThread{rule.activity.state=UiGalleryActivity.fixture(rule.activity,"02",true).copy(document=null,mode="CARDS")}
        rule.onNodeWithText("ID cards").assertIsSelected()
        rule.onNodeWithText("Multiple cards").performClick()
        rule.onNodeWithText("Import images").performClick();rule.onNodeWithText("Apply & straighten").performClick();rule.onNodeWithText("Read text").performClick();rule.onNodeWithText("Continue").performClick()
        rule.onNodeWithTag("screen-07").assertIsDisplayed();rule.onNodeWithText("Confirm cards").performClick()
        rule.onNodeWithTag("screen-08").assertIsDisplayed()
        // Camera and secondary editor/search/settings paths use the same screen components.
        rule.runOnUiThread{rule.activity.state=UiGalleryActivity.fixture(rule.activity,"02",true).copy(document=null,mode="CARDS")}
        rule.onNodeWithText("Use camera").performClick();rule.onNodeWithContentDescription("Capture page").performClick();rule.onNodeWithText("Done").performClick()
        rule.onNodeWithTag("screen-04").assertIsDisplayed()
        rule.runOnUiThread{rule.activity.state=UiGalleryActivity.fixture(rule.activity,"01")}
        rule.onNodeWithContentDescription("Search library").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Project")
        rule.onNodeWithText("Project notes").performClick();rule.onNodeWithText("Open PDF preview").performClick()
        rule.onNodeWithContentDescription("Back").performClick();rule.onNodeWithTag("screen-12").assertIsDisplayed()
        rule.onNodeWithText("Edit pages").performClick();rule.onNodeWithText("Enhance").performClick()
        rule.onNodeWithContentDescription("Open learning lab").performClick();rule.onNodeWithText("Return to editor").performClick()
        rule.onNodeWithText("Apply changes").performClick();rule.onNodeWithText("Crop").performClick()
        rule.onNodeWithContentDescription("Back").performClick();rule.onNodeWithTag("screen-08").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick();rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Save defaults").performClick();rule.onNodeWithTag("screen-01").assertIsDisplayed()

    }
    @Test fun largeTextKeepsBottomActionsVisibleAndControlsWithinThePhone(){
        val routes=(1..15).map{it.toString().padStart(2,'0')}+listOf("intro-1","intro-2")
        for(route in routes){
            rule.runOnUiThread{rule.activity.reviewFontScale=2f;rule.activity.state=UiGalleryActivity.fixture(rule.activity,route,route=="07")}
            rule.waitForIdle()
            footers[route]?.let{rule.onNodeWithText(it).assertIsDisplayed()}
            val screen=rule.onNodeWithTag("screen-$route").fetchSemanticsNode().boundsInRoot
            val nodes=rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            nodes.filter{it.boundsInRoot.width>0 && it.boundsInRoot.height>0}.forEach{node->
                assertTrue("S$route control extends past the left edge",node.boundsInRoot.left>=screen.left-1)
                assertTrue("S$route control extends past the right edge",node.boundsInRoot.right<=screen.right+1)
            }
            saveScreenshot("$route-large")
        }
    }
    @Test fun narrowPhoneKeepsEveryScreenReachable(){
        val routes=(1..15).map{it.toString().padStart(2,'0')}+listOf("intro-1","intro-2")
        for(route in routes){
            rule.runOnUiThread{rule.activity.reviewWidth=androidx.compose.ui.unit.Dp(320f);rule.activity.reviewFontScale=2f;rule.activity.state=UiGalleryActivity.fixture(rule.activity,route,route=="07")}
            rule.waitForIdle();footers[route]?.let{rule.onNodeWithText(it).assertIsDisplayed()}
            val screen=rule.onNodeWithTag("screen-$route").fetchSemanticsNode().boundsInRoot
            assertEquals("Narrow layout must actually measure 320 dp",320f,screen.width/rule.activity.resources.displayMetrics.density,1f)
            rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter{it.boundsInRoot.width>0}.forEach{node->
                assertTrue("S$route control outside narrow window",node.boundsInRoot.left>=screen.left-1 && node.boundsInRoot.right<=screen.right+1)
            }
            saveScreenshot("$route-narrow-large")
        }
    }
    private fun saveScreenshot(route:String){
        rule.mainClock.advanceTimeByFrame();rule.waitForIdle()
        // Semantics can update before SurfaceFlinger presents that frame.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(350)

        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?:error("Native capture unavailable")
        try{
            val values=ContentValues().apply{put(MediaStore.Downloads.DISPLAY_NAME,"$route.png");put(MediaStore.Downloads.MIME_TYPE,"image/png");put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/FolioUiParity");put(MediaStore.Downloads.IS_PENDING,1)}
            val resolver=context.contentResolver
            // Replace only this app's generated capture at its exact QA path.
            resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,"relative_path=? AND _display_name=?",arrayOf(Environment.DIRECTORY_DOWNLOADS+"/FolioUiParity/","$route.png"))
            val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)!!
            resolver.openOutputStream(uri)!!.use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            resolver.update(uri,ContentValues().apply{put(MediaStore.Downloads.IS_PENDING,0)},null,null)
        } finally {bitmap.recycle()}
    }
}
