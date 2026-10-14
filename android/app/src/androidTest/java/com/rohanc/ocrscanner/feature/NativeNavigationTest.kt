package com.rohanc.ocrscanner.feature

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.rohanc.ocrscanner.MainActivity
import org.junit.Rule
import org.junit.Test

class NativeNavigationTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @Test fun onboardingLibrarySetupSettingsAndSearchAreConnected(){
        rule.waitUntil(10000){rule.onAllNodesWithText("Skip introduction").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithText("Your library").fetchSemanticsNodes().isNotEmpty()}
        if(rule.onAllNodesWithText("Skip introduction").fetchSemanticsNodes().isNotEmpty())rule.onNodeWithText("Skip introduction").performClick()
        rule.onNodeWithText("New scan").performClick();rule.onNodeWithText("ID cards").performClick()
        rule.onNodeWithText("Import photos").assertIsEnabled()
        rule.onNodeWithText("Back").performClick();rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Letter").performClick();rule.waitUntil(5000){rule.onAllNodes(hasText("Letter") and isSelected()).fetchSemanticsNodes().isNotEmpty()};rule.onNodeWithText("Letter").assertIsSelected()
        rule.onNodeWithText("Back").performClick();rule.onNodeWithText("Search").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("no-such-folio-document")
        rule.onNodeWithText("No matching documents.").assertExists()
    }
}
