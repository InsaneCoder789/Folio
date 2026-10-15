package com.rohanc.ocrscanner.feature

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.rohanc.ocrscanner.core.Point
import com.rohanc.ocrscanner.core.Quad
import com.rohanc.ocrscanner.feature.editor.CropEditor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CropAccessibilityTest {
    @get:Rule val rule=createComposeRule()
    @Test fun allCornersExposeDirectionalActionsAndRetainChanges(){
        val quad=mutableStateOf(Quad.full(1000,800))
        rule.setContent{CropEditor("/missing-preview.jpg",1000,800,quad.value,{quad.value=it},Modifier.size(300.dp))}
        for(name in listOf("Top left","Top right","Bottom right","Bottom left")){
            val node=rule.onNodeWithContentDescription("$name crop corner").assertIsDisplayed().fetchSemanticsNode()
            assertEquals(listOf("Move left","Move right","Move up","Move down"),node.config[SemanticsActions.CustomActions].map{it.label})
        }
        fun action(label:String){
            val node=rule.onNodeWithContentDescription("Top left crop corner").fetchSemanticsNode()
            rule.runOnIdle{assertTrue(node.config[SemanticsActions.CustomActions].single{it.label==label}.action())}
        }
        action("Move right");action("Move down")
        rule.runOnIdle{assertEquals(Point(10f,8f),quad.value.points.first())}
        rule.onNodeWithContentDescription("Top left crop corner")
            .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"Horizontal 1 percent, vertical 1 percent"))
    }
    @Test fun accessibilityActionsCannotMoveCornersOutsideSourceImage(){
        val quad=mutableStateOf(Quad.full(1000,800))
        rule.setContent{CropEditor("/missing-preview.jpg",1000,800,quad.value,{quad.value=it},Modifier.size(300.dp))}
        for((name,label) in listOf("Top left" to "Move left","Top left" to "Move up","Bottom right" to "Move right","Bottom right" to "Move down")){
            val node=rule.onNodeWithContentDescription("$name crop corner").fetchSemanticsNode()
            rule.runOnIdle{assertFalse(node.config[SemanticsActions.CustomActions].single{it.label==label}.action())}
        }
        rule.runOnIdle{assertEquals(Quad.full(1000,800),quad.value)}
    }
}
