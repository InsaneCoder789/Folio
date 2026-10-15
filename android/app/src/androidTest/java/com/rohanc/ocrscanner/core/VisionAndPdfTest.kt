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
    @Test fun separateRecognizedWordsKeepLogicalSpaces()=runBlocking{inDirectory{dir->
        var s=snapshot(image(dir),"Folio backend")
        val words=JSONArray()
        for((text,x) in listOf("Folio" to 40,"backend" to 170)) words.put(JSONObject().put("text",text).put("polygon",JSONArray("[[$x,50],[${x+100},50],[${x+100},90],[$x,90]]")))
        s=s.copy(regions=s.regions.map{it.copy(wordsJson=words.toString())})
        val result=ScannerPdfExporter(context).export(s,"SEARCHABLE",PageLayout.sheets(listOf(LayoutRegion("r",800,600)),false,"Stacked","A4",36f),dir)
        PDDocument.load(File(result.path)).use{document->
            val reader=PDFTextStripper().apply{spacingTolerance=Float.MAX_VALUE;averageCharTolerance=Float.MAX_VALUE}
            assertTrue(reader.getText(document).contains("Folio backend"))
        }
    }}
    @Test fun regeneratedTextPaginatesWithoutLosingWords()=runBlocking{inDirectory{dir->
        val s=snapshot(image(dir),List(200){"Folio paragraph $it café"}.joinToString("\n"));val exporter=ScannerPdfExporter(context)
        val result=exporter.export(s,"TEXT",PageLayout.sheets(listOf(LayoutRegion("r",800,600)),false,"Stacked","A4",36f),dir)
        assertTrue(result.pageCount>1)
        PDDocument.load(File(result.path)).use{val text=PDFTextStripper().getText(it);assertTrue(text.contains("paragraph 199 café"));assertTrue(text.contains("paragraph 0 café"))}
    }}
    @Test fun nativeTextPaginationHasNoDuplicateOrRasterizedParagraphs()=runBlocking{inDirectory{dir->
        val s=snapshot(image(dir),List(200){"Folio paragraph $it café"}.joinToString("\n"))
        val result=ScannerPdfExporter(context).export(s,"TEXT",PageLayout.sheets(listOf(LayoutRegion("r",800,600)),false,"Stacked","Letter",36f),dir)
        PDDocument.load(File(result.path)).use{pdf->
            assertTrue(pdf.numberOfPages>1)
            val text=PDFTextStripper().getText(pdf)
            for(i in 0 until 200)assertEquals("Paragraph $i should appear once",1,Regex(Regex.escape("Folio paragraph $i café")).findAll(text).count())
            for(page in pdf.pages){
                assertEquals(612f,page.mediaBox.width,0f);assertEquals(792f,page.mediaBox.height,0f)
                assertFalse("Regenerated text must not be a page bitmap",page.resources.xObjectNames.iterator().hasNext())
            }
        }
        assertTrue(dir.listFiles().orEmpty().none{it.extension=="partial"})
    }}
    @Test fun nativeTextPagesRenderInsideTheirMargins()=runBlocking{inDirectory{dir->
        val s=snapshot(image(dir),List(120){"Visible native paragraph $it"}.joinToString("\n"))
        val result=ScannerPdfExporter(context).export(s,"TEXT",PageLayout.sheets(listOf(LayoutRegion("r",800,600)),false,"Stacked","A4",36f),dir)
        ParcelFileDescriptor.open(File(result.path),ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{pdf->
            assertTrue(pdf.pageCount>1)
            for(index in 0 until pdf.pageCount)pdf.openPage(index).use{page->
                val bitmap=Bitmap.createBitmap(page.width,page.height,Bitmap.Config.ARGB_8888)
                try{
                    bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val pixels=IntArray(page.width*page.height);bitmap.getPixels(pixels,0,page.width,0,0,page.width,page.height)
                    val ink=pixels.indices.filter{Color.red(pixels[it])<180}
                    assertTrue("Page ${index+1} has no visible text",ink.size>50)
                    assertTrue("Text crosses the left margin",ink.all{it%page.width>=35})
                    assertTrue("Text crosses the right margin",ink.all{it%page.width<page.width-35})
                    assertTrue("Text crosses the bottom margin",ink.all{it/page.width<page.height-35})
                }finally{bitmap.recycle()}
            }
        }}
    }}
    @Test fun rewrittenTextCannotBecomeMisalignedSearchablePdf()=runBlocking{inDirectory{dir->
        val s=snapshot(image(dir)).let{it.copy(regions=it.regions.map{r->r.copy(reviewedText="Completely rewritten paragraph")})}
        try{ScannerPdfExporter(context).export(s,"SEARCHABLE",PageLayout.sheets(listOf(LayoutRegion("r",800,600)),false,"Stacked","A4",36f),dir);fail("Misaligned review accepted")}
        catch(_:IllegalArgumentException){}
        assertTrue(dir.listFiles().orEmpty().none{it.extension in listOf("pdf","partial")})
    }}
    @Test fun failedNativeWriteKeepsThePreviousCompletedPdf()=runBlocking{inDirectory{dir->
        val s=snapshot(image(dir));val sheets=PageLayout.sheets(listOf(LayoutRegion("r",800,600)),false,"Stacked","A4",36f)
        val exporter=ScannerPdfExporter(context);val previous=exporter.export(s,"SEARCHABLE",sheets,dir)
        val bytes=File(previous.path).readBytes()
        val blocked=File(dir,"blocked-directory").apply{writeText("Not a directory")}
        try{exporter.export(s,"TEXT",sheets,blocked);fail("Unwritable destination accepted")}catch(_:java.io.IOException){}
        assertArrayEquals(bytes,File(previous.path).readBytes())
        assertTrue(dir.listFiles().orEmpty().none{it.extension=="partial"})
        PDDocument.load(File(previous.path)).use{assertTrue(PDFTextStripper().getText(it).contains("Folio café"))}
    }}
    @Test fun maximumScanExportKeepsAllPagesAndRemovesNativeIntermediates()=runBlocking{inDirectory{dir->
        val s=snapshot(image(dir))
        val many=s.copy(regions=(0 until 48).map{i->s.regions.single().copy(id="r$i",position=i)})
        val sheets=PageLayout.sheets(many.regions.map{LayoutRegion(it.id,800,600)},false,"Stacked","A4",36f)
        val result=ScannerPdfExporter(context).export(many,"SEARCHABLE",sheets,dir)
        assertEquals(48,result.pageCount)
        PDDocument.load(File(result.path)).use{pdf->
            assertEquals(48,pdf.numberOfPages)
            assertEquals(48,Regex(Regex.escape("Folio café")).findAll(PDFTextStripper().getText(pdf)).count())
        }
        assertTrue("Native intermediate pages were not cleaned",dir.listFiles().orEmpty().none{it.extension=="partial"})
    }}
}
