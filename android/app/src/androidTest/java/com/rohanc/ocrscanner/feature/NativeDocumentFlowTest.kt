package com.rohanc.ocrscanner.feature

import android.content.ContentValues
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.rohanc.ocrscanner.MainActivity
import com.rohanc.ocrscanner.ScannerApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NativeDocumentFlowTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private fun click(text:String){rule.onNodeWithText(text).performScrollTo().performClick()}
    private fun ready(text:String){rule.waitUntil(30000){rule.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty()}}
    @Test fun correctedDocumentRunsThroughOcrAndBothPdfScreens()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repository=(context.applicationContext as ScannerApplication).repository
        val document=repository.create("Folio backend acceptance")
        val fixture=File.createTempFile("folio-native-flow-",".jpg",context.cacheDir)
        try{
            val image=Bitmap.createBitmap(1000,1400,Bitmap.Config.ARGB_8888)
            try{val canvas=Canvas(image);canvas.drawColor(Color.WHITE);val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=60f}
                canvas.drawText("FOLIO BACKEND",80f,180f,paint);canvas.drawText("A working document.",80f,300f,paint)
                fixture.outputStream().use{image.compress(Bitmap.CompressFormat.JPEG,98,it)}
            }finally{image.recycle()}
            repository.importImage(document.document.id,0,Uri.fromFile(fixture))
            rule.waitUntil(10000){rule.onAllNodesWithText("Skip introduction").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithText("Your library").fetchSemanticsNodes().isNotEmpty()}
            if(rule.onAllNodesWithText("Skip introduction").fetchSemanticsNodes().isNotEmpty())rule.onNodeWithText("Skip introduction").performClick()
            ready("Folio backend acceptance");click("Folio backend acceptance");click("Edit document");click("Crop")
            ready("Apply boundaries");click("Apply boundaries");ready("Apply enhancement")
            click("grayscale");click("Apply enhancement");ready("Read all pages");click("Read all pages")
            ready("Continue")
            rule.waitUntil(30000){runBlocking{repository.snapshot(document.document.id).regions.single().recognizedText?.contains("FOLIO")==true}}
            click("Continue");click("Arrange pages");click("Export options");click("Generate PDF")
            ready("Save PDF to files")
            var snapshot=repository.snapshot(document.document.id)
            val searchable=snapshot.exports.maxBy{it.createdAt};assertEquals("SEARCHABLE",searchable.mode);assertTrue(File(searchable.path).exists())
            publish("searchable.pdf","application/pdf"){out->File(searchable.path).inputStream().use{it.copyTo(out)}}
            rule.waitUntil(15000){rule.onAllNodesWithContentDescription("PDF page 1").fetchSemanticsNodes().isNotEmpty()}
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let{bitmap->try{publish("native-preview.png","image/png"){bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}}
            click("Done");click("Edit document");click("Text")
            rule.onNode(hasSetTextAction()).performTextReplacement("Folio reviewed text.\nReady for export.");Espresso.closeSoftKeyboard()
            click("Save text review");ready("Continue");click("Continue");click("Arrange pages");click("Export options")
            click("Regenerated text");click("Generate PDF");ready("Save PDF to files")
            snapshot=repository.snapshot(document.document.id)
            val regenerated=snapshot.exports.maxBy{it.createdAt};assertEquals("TEXT",regenerated.mode);assertEquals(2,snapshot.exports.size)
            publish("regenerated.pdf","application/pdf"){out->File(regenerated.path).inputStream().use{it.copyTo(out)}}
        }finally{
            fixture.delete();val latest=repository.snapshot(document.document.id);repository.delete(latest.document.id,latest.document.revision)
        }
    }
    private fun publish(name:String,mime:String,write:(java.io.OutputStream)->Unit){
        if(Build.VERSION.SDK_INT<29)return
        val resolver=rule.activity.contentResolver
        val values=ContentValues().apply{put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.MIME_TYPE,mime);put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/FolioVerification");put(MediaStore.Downloads.IS_PENDING,1)}
        val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:error("Could not create test evidence")
        resolver.openOutputStream(uri)!!.use(write)
        resolver.update(uri,ContentValues().apply{put(MediaStore.Downloads.IS_PENDING,0)},null,null)
    }
}
