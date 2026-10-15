package com.rohanc.ocrscanner.core.pdf

import android.content.Context
import com.rohanc.ocrscanner.core.*
import com.rohanc.ocrscanner.data.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.*

class ScannerPdfExporter(private val context:Context) {
    suspend fun export(snapshot:DocumentSnapshot,mode:String,sheets:List<Sheet>,directory:File):ExportEntity = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        require(mode in listOf("SEARCHABLE","TEXT"))
        directory.mkdirs()
        val id=UUID.randomUUID().toString();val temporary=File(directory,"$id.partial");val native=File(directory,"$id.native.partial");val complete=File(directory,"$id.pdf")
        try {
            val expectedPages=AndroidPdfPageWriter(context).write(snapshot,mode,sheets,native)
            val pages=PDDocument.load(native,MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { document ->
                document.documentInformation.title=snapshot.document.title
                document.documentInformation.creator="Folio"
                check(document.numberOfPages==expectedPages) { "Native PDF page count changed." }
                if(mode=="SEARCHABLE") {
                    val font=context.assets.open("fonts/NotoSans-Regular.ttf").use{PDType0Font.load(document,it)}
                    searchable(document,font,snapshot,sheets)
                }
                coroutineContext.ensureActive()
                document.save(temporary)
                // PDFBox owns/closes its save stream. Sync using a fresh writable descriptor.
                RandomAccessFile(temporary,"rw").use { it.fd.sync() }
                document.numberOfPages
            }
            // Verify the saved text layer before publishing; reader compatibility is tested separately.
            PDDocument.load(temporary).use { reopened ->
                check(reopened.numberOfPages==pages && PDFTextStripper().getText(reopened).isNotBlank()) { "The PDF has no usable text layer." }
            }
            coroutineContext.ensureActive()
            check(temporary.renameTo(complete)) { "Could not publish completed PDF." }
            ExportEntity(id,snapshot.document.id,snapshot.document.revision,mode,complete.absolutePath,pages,System.currentTimeMillis())
        } catch(failure:Throwable) { temporary.delete();complete.delete();throw failure }
        finally {native.delete()}
    }
    private suspend fun searchable(document:PDDocument,font:PDType0Font,snapshot:DocumentSnapshot,sheets:List<Sheet>) {
        require(snapshot.regions.all{it.reviewedText==null || it.reviewedText==it.recognizedText}) {
            "Edited paragraphs cannot be aligned automatically. Choose Regenerated text, or re-read the original image for a searchable scan."
        }
        require(snapshot.regions.any{JSONArray(it.wordsJson).length()>0}) { "No recognized words are available for a searchable PDF." }
        for((pageIndex,sheet) in sheets.withIndex()) {
            coroutineContext.ensureActive()
            val page=document.getPage(pageIndex)
            PDPageContentStream(document,page,PDPageContentStream.AppendMode.APPEND,true,true).use { stream ->
                for(placement in sheet.placements) {
                    coroutineContext.ensureActive()
                    val region=snapshot.regions.single{it.id==placement.regionId};val capture=snapshot.captures.single{it.id==region.captureId}
                    val sourceWidth=region.correctedWidth?:capture.width;val sourceHeight=region.correctedHeight?:capture.height
                    val words=JSONArray(region.wordsJson)
                    for(i in 0 until words.length()) {
                        val word=words.getJSONObject(i);val value=word.getString("text");val polygon=word.getJSONArray("polygon")
                        if(value.isBlank() || polygon.length()!=4) continue
                        val points=List(4){j ->polygon.getJSONArray(j).let{Point(it.getDouble(0).toFloat(),it.getDouble(1).toFloat())}}
                        val minX=points.minOf{it.x}.coerceIn(0f,sourceWidth.toFloat());val maxX=points.maxOf{it.x}.coerceIn(0f,sourceWidth.toFloat())
                        val minY=points.minOf{it.y}.coerceIn(0f,sourceHeight.toFloat());val maxY=points.maxOf{it.y}.coerceIn(0f,sourceHeight.toFloat())
                        val height=(maxY-minY)*placement.height/sourceHeight;val width=(maxX-minX)*placement.width/sourceWidth
                        if(width<=0f || height<=0f) continue
                        val size=max(1f,height/.95f)
                        val glyphWidth=font.getStringWidth(value)/1000f*size
                        require(glyphWidth>0) { "A recognized word cannot be encoded by the export font." }
                        stream.beginText();stream.setFont(font,size);stream.setRenderingMode(RenderingMode.NEITHER)
                        stream.setHorizontalScaling(width/glyphWidth*100f)
                        stream.newLineAtOffset(placement.x+minX*placement.width/sourceWidth,
                            sheet.height-placement.y-minY*placement.height/sourceHeight-height*.8f)
                        // Preserve logical word separators for readers that do not infer gaps from positions.
                        stream.showText(value+" ");stream.endText()
                    }
                }
            }
        }
    }
}
