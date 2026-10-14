package com.rohanc.ocrscanner.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rohanc.ocrscanner.core.pdf.ScannerPdfExporter
import com.rohanc.ocrscanner.core.vision.OpenCvProcessor
import com.rohanc.ocrscanner.data.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VisionAndPdfTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private fun image(dir:File,draw:(Canvas)->Unit={}):File {
        val file=File(dir,"source.jpg");val bitmap=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888)
        try{val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE);draw(canvas);file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,100,it)}}finally{bitmap.recycle()};return file
    }
    private suspend fun inDirectory(action:suspend(File)->Unit){val dir=File(context.cacheDir,"folio-test-${UUID.randomUUID()}").apply{mkdirs()};try{action(dir)}finally{dir.deleteRecursively()}}
    private fun snapshot(file:File,text:String="Folio café"):DocumentSnapshot {
        val id=UUID.randomUUID().toString()
        val words=JSONArray().put(JSONObject().put("text",text).put("polygon",JSONArray("[[40,50],[300,50],[300,90],[40,90]]"))).toString()
        return DocumentSnapshot(DocumentEntity(id,"Test",updatedAt=0),listOf(CaptureEntity("c",id,file.path,file.path,800,600)),
            listOf(RegionEntity("r","c",id,0,ocrRevision=0,recognizedText=text,wordsJson=words)))
    }
    @Test fun perspectiveAndRotationReturnExpectedDimensions()=runBlocking{inDirectory{dir->
        val file=image(dir);val quad=Quad(listOf(Point(100f,100f),Point(699f,100f),Point(699f,499f),Point(100f,499f)))
        val result=OpenCvProcessor().process(file.path,EditRecipe(quad,rotation=1),dir)
        assertEquals(399,result.width);assertEquals(599,result.height);assertTrue(File(result.path).exists())
    }}
    @Test fun contourDetectionFindsSeparatedCards()=runBlocking{inDirectory{dir->
        val file=image(dir){c->c.drawColor(Color.DKGRAY);val p=Paint().apply{color=Color.WHITE};c.drawRect(50f,70f,350f,250f,p);c.drawRect(440f,330f,750f,520f,p)}
        val candidates=OpenCvProcessor().detect(file.path,true)
        assertEquals(2,candidates.size);candidates.forEach{it.validate(800,600)}
    }}
    @Test fun searchablePdfHasExtractableUnicodeAndInvisibleText()=runBlocking{inDirectory{dir->
        val file=image(dir);val s=snapshot(file);val result=ScannerPdfExporter(context).export(s,"SEARCHABLE",PageLayout.sheets(listOf(LayoutRegion("r",800,600)),false,"Stacked","A4",36f),dir)
        PDFBoxResourceLoader.init(context)
        PDDocument.load(File(result.path)).use{assertTrue(PDFTextStripper().getText(it).contains("Folio café"))}
        ParcelFileDescriptor.open(File(result.path),ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{pdf->pdf.openPage(0).use{page->
            val bitmap=Bitmap.createBitmap(595,842,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            try{val pixels=IntArray(595*842);bitmap.getPixels(pixels,0,595,0,0,595,842);assertEquals(0,pixels.count{Color.red(it)<240})}finally{bitmap.recycle()}
        }}}
    }}
    @Test fun regeneratedTextPaginatesWithoutLosingWords()=runBlocking{inDirectory{dir->
        val s=snapshot(image(dir),List(200){"Folio paragraph $it café"}.joinToString("\n"));val exporter=ScannerPdfExporter(context)
        val result=exporter.export(s,"TEXT",PageLayout.sheets(listOf(LayoutRegion("r",800,600)),false,"Stacked","A4",36f),dir)
        assertTrue(result.pageCount>1)
        PDDocument.load(File(result.path)).use{val text=PDFTextStripper().getText(it);assertTrue(text.contains("paragraph 199 café"));assertTrue(text.contains("paragraph 0 café"))}
    }}
    @Test fun rewrittenTextCannotBecomeMisalignedSearchablePdf()=runBlocking{inDirectory{dir->
        val s=snapshot(image(dir)).let{it.copy(regions=it.regions.map{r->r.copy(reviewedText="Completely rewritten paragraph")})}
        try{ScannerPdfExporter(context).export(s,"SEARCHABLE",PageLayout.sheets(listOf(LayoutRegion("r",800,600)),false,"Stacked","A4",36f),dir);fail("Misaligned review accepted")}
        catch(_:IllegalArgumentException){}
        assertTrue(dir.listFiles().orEmpty().none{it.extension in listOf("pdf","partial")})
    }}
}
