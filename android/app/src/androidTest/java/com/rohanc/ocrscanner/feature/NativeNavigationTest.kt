package com.rohanc.ocrscanner.feature

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.rohanc.ocrscanner.MainActivity
import org.junit.Rule
import org.junit.Test

class NativeNavigationTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @Test fun onboardingLibrarySetupSettingsAndSearchAreConnected(){
        rule.waitUntil(10000){rule.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithText("Your library").fetchSemanticsNodes().isNotEmpty()}
        if(rule.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty())rule.onNodeWithText("Skip").performClick()
        rule.onNodeWithText("New scan").performClick();rule.onNodeWithText("ID cards").performClick()
        rule.onNodeWithText("Import images").assertIsEnabled()
        rule.onNodeWithContentDescription("Back").performClick();rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("A4").performClick();rule.onNodeWithText("Letter").performClick()
        rule.onNodeWithText("Save defaults").performClick()
        rule.waitUntil(5000){rule.onAllNodesWithText("Your library").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithContentDescription("Search library").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("no-such-folio-document")
        rule.onNodeWithText("No matching documents.").assertExists()
    }
}
