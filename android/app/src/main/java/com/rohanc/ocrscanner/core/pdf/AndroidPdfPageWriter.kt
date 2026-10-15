package com.rohanc.ocrscanner.core.pdf

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.rohanc.ocrscanner.core.Sheet
import com.rohanc.ocrscanner.data.DocumentSnapshot
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** Platform PdfDocument owns all visible page content. OCR remains a separate engine. */
class AndroidPdfPageWriter(private val context:Context) {
    suspend fun write(snapshot:DocumentSnapshot,mode:String,sheets:List<Sheet>,file:File):Int {
        require(sheets.isNotEmpty()) { "Add a page first." }
        if(mode=="SEARCHABLE")return writeScanPages(snapshot,sheets,file)
        require(mode=="TEXT")
        val document=PdfDocument()
        try {
            drawText(document,snapshot,sheets.first())
            coroutineContext.ensureActive()
            file.outputStream().use{out->document.writeTo(out);out.fd.sync()}
            return document.pages.size
        } finally {document.close()}
    }
    private suspend fun writeScanPages(snapshot:DocumentSnapshot,sheets:List<Sheet>,file:File):Int {
        val staged=mutableListOf<File>()
        try {
            for((index,sheet) in sheets.withIndex()){
                coroutineContext.ensureActive()
                val part=File(file.parentFile,file.name+".page-$index.partial");staged.add(part)
                val document=PdfDocument()
                try{
                    drawScans(document,snapshot,listOf(sheet))
                    part.outputStream().use{document.writeTo(it);it.fd.sync()}
                }finally{document.close()}
            }
            // Release each native page and its bitmap memory before rendering
            // the next sheet. Merge flat pages using disk-backed PDFBox buffers.
            val merger=PDFMergerUtility()
            merger.documentMergeMode=PDFMergerUtility.DocumentMergeMode.OPTIMIZE_RESOURCES_MODE
            merger.destinationFileName=file.absolutePath
            staged.forEach{merger.addSource(it)}
            merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir))
            coroutineContext.ensureActive()
            return sheets.size
        }finally{staged.forEach{it.delete()}}
    }
    private suspend fun drawScans(document:PdfDocument,snapshot:DocumentSnapshot,sheets:List<Sheet>) {
        val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        for((index,sheet) in sheets.withIndex()) {
            coroutineContext.ensureActive()
            val page=document.startPage(PdfDocument.PageInfo.Builder(sheet.width.roundToInt(),sheet.height.roundToInt(),index+1).create())
            try {
                page.canvas.drawColor(Color.WHITE)
                for(placement in sheet.placements) {
                    coroutineContext.ensureActive()
                    val region=snapshot.regions.single{it.id==placement.regionId}
                    val capture=snapshot.captures.single{it.id==region.captureId}
                    val bitmap=BitmapFactory.decodeFile(region.correctedPath?:capture.uprightPath)?:error("A page image cannot be opened. Retake or import it again.")
                    try {page.canvas.drawBitmap(bitmap,null,RectF(placement.x,placement.y,placement.x+placement.width,placement.y+placement.height),paint)}
                    finally {bitmap.recycle()}
                }
            } finally {document.finishPage(page)}
        }
    }
    private suspend fun drawText(document:PdfDocument,snapshot:DocumentSnapshot,sheet:Sheet) {
        val text=snapshot.regions.sortedBy{it.position}.joinToString("\n\n"){it.reviewedText?:it.recognizedText.orEmpty()}
            .replace("\r\n","\n").replace('\r','\n')
        require(text.isNotBlank()) { "No text to typeset. Read and review a page first." }
        require(text.codePoints().toArray().all{it>=32 || it==9 || it==10}) { "Remove control characters before exporting." }
        val margin=snapshot.document.margin
        val width=(sheet.width-2*margin).toInt()
        val height=sheet.height-2*margin
        require(width>0 && height>0) { "Page margins leave no room for text." }
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color=Color.BLACK;textSize=11f
            typeface=Typeface.createFromAsset(context.assets,"fonts/NotoSans-Regular.ttf")
        }
        fun layout(value:String)=StaticLayout.Builder.obtain(value,0,value.length,paint,width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
            .setLineSpacing(2f,1f)
            .apply { if(Build.VERSION.SDK_INT>=29)setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE) }
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build()
        val full=layout(text)
        var first=0
        var pageNumber=1
        while(first<full.lineCount) {
            coroutineContext.ensureActive()
            val top=full.getLineTop(first)
            var end=first
            while(end<full.lineCount && full.getLineBottom(end)-top<=height)end++
            require(end>first) { "The page is too small for a line of text." }
            // Draw only this page's characters. Clipping one giant layout would
            // leave off-page text in the PDF and duplicate extraction/search hits.
            val content=layout(text.substring(full.getLineStart(first),full.getLineEnd(end-1)))
            val page=document.startPage(PdfDocument.PageInfo.Builder(sheet.width.roundToInt(),sheet.height.roundToInt(),pageNumber++).create())
            try {
                page.canvas.drawColor(Color.WHITE)
                page.canvas.save()
                try {page.canvas.translate(margin,margin);content.draw(page.canvas)}finally{page.canvas.restore()}
            } finally {document.finishPage(page)}
            first=end
        }
    }
}
