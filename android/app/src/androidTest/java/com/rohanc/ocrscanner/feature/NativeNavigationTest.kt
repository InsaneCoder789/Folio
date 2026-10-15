package com.rohanc.ocrscanner.feature

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.rohanc.ocrscanner.MainActivity
import com.rohanc.ocrscanner.ScannerApplication
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NativeNavigationTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @Test fun launcherReentryKeepsTheActiveScanInsteadOfOpeningAnotherActivity(){
        rule.waitUntil(10000){rule.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithText("Your library").fetchSemanticsNodes().isNotEmpty()}
        if(rule.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty())rule.onNodeWithText("Skip").performClick()
        rule.onNodeWithText("New scan").performClick();rule.onNodeWithText("ID cards").performClick()
        rule.onNodeWithText("Multiple cards").performClick()
        val original=rule.activity
        rule.runOnUiThread{original.startActivity(android.content.Intent(original,MainActivity::class.java).setAction(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))}
        rule.waitForIdle()
        rule.onNodeWithText("Multiple cards").assertIsSelected()
        rule.onNodeWithText("Import images").assertIsDisplayed()
        org.junit.Assert.assertSame(original,rule.activity)
    }
    @Test fun pendingDefaultsSurviveRecreationBeforeSaving(){
        val preferences=(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ScannerApplication).preferences
        val initial=preferences.state.value
        try{
            rule.waitUntil(10000){rule.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithText("Your library").fetchSemanticsNodes().isNotEmpty()}
            if(rule.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty())rule.onNodeWithText("Skip").performClick()
            rule.onNodeWithContentDescription("Settings").performClick()
            val target=if(initial.paper=="A4")"Letter" else "A4"
            rule.onNodeWithText(initial.paper).performClick();rule.onNodeWithText(target).performClick()
            rule.activityRule.scenario.recreate()
            rule.onNodeWithText(target).assertIsDisplayed()
            rule.onNodeWithText("Save defaults").performClick()
            rule.waitUntil(5000){preferences.state.value.paper==target}
        }finally{runBlocking{withContext(Dispatchers.IO){preferences.save(initial.copy(introDone=preferences.state.value.introDone))}}}
    }
    @Test fun onboardingLibrarySetupSettingsAndSearchAreConnected(){
        val preferences=(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ScannerApplication).preferences
        val initial=preferences.state.value
        try{
        rule.waitUntil(10000){rule.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithText("Your library").fetchSemanticsNodes().isNotEmpty()}
        if(rule.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty())rule.onNodeWithText("Skip").performClick()
        rule.onNodeWithText("New scan").performClick();rule.onNodeWithText("ID cards").performClick()
        rule.onNodeWithText("Import images").assertIsEnabled()
        rule.onNodeWithContentDescription("Back").performClick();rule.onNodeWithContentDescription("Settings").performClick()
        val target=if(initial.paper=="A4")"Letter" else "A4"
        rule.onNodeWithText(initial.paper).performClick();rule.onNodeWithText(target).performClick()
        rule.onNodeWithText("Save defaults").performClick()
        rule.waitUntil(5000){rule.onAllNodesWithText("Your library").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(target,preferences.state.value.paper)
        rule.onNodeWithContentDescription("Search library").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("no-such-folio-document")
        rule.onNodeWithText("No matching documents.").assertExists()
        }finally{runBlocking{withContext(Dispatchers.IO){preferences.save(initial.copy(introDone=preferences.state.value.introDone))}}}
    }
}
