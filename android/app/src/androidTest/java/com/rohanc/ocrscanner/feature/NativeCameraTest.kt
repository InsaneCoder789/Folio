package com.rohanc.ocrscanner.feature

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.rohanc.ocrscanner.MainActivity
import com.rohanc.ocrscanner.ScannerApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NativeCameraTest {
    @get:Rule(order=0) val permission=GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @get:Rule(order=1) val rule=createAndroidComposeRule<MainActivity>()
    @Test fun cameraXCaptureBecomesAnOwnedDraftPage()=runBlocking{
        val repo=(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ScannerApplication).repository
        val before=repo.library.first().map{it.id}.toSet()
        try{
            rule.waitUntil(10000){rule.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithText("Your library").fetchSemanticsNodes().isNotEmpty()}
            if(rule.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty())rule.onNodeWithText("Skip").performClick()
            rule.onNodeWithText("New scan").performClick();rule.onNodeWithText("Use camera").performClick()
            rule.waitUntil(45000){rule.onAllNodes(hasContentDescription("Capture page") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
            rule.onNodeWithContentDescription("Capture page").performClick()
            rule.waitUntil(15000){rule.onAllNodes(hasText("Done") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
            rule.onNodeWithText("Done").performClick()
            rule.waitUntil(30000){rule.onAllNodesWithText("Apply & straighten").fetchSemanticsNodes().isNotEmpty()}
            val created=repo.library.first().single{it.id !in before};val snapshot=repo.snapshot(created.id)
            assertEquals(1,snapshot.regions.size);assertTrue(File(snapshot.captures.single().originalPath).length()>0)
            assertTrue(snapshot.captures.single().width>0 && snapshot.captures.single().height>0)
        }finally{for(document in repo.library.first().filter{it.id !in before}){val s=repo.snapshot(document.id);repo.delete(document.id,s.document.revision)}}
    }
}
