package com.rohanc.ocrscanner.data

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rohanc.ocrscanner.core.pdf.ScannerPdfExporter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CardWorkflowTest {
    @Test fun multipleCardPhotosBecomeConfirmedPairsAndTwoSearchableSheets()=runBlocking{
        val context=ApplicationProvider.getApplicationContext<Context>()
        val database=Room.inMemoryDatabaseBuilder(context,ScannerDatabase::class.java).build()
        val repository=DocumentRepository(database,PrivateImageStore(context),MlKitOcrEngine(),pdf=ScannerPdfExporter(context))
        val document=repository.create("Six-card acceptance",mode="CARDS")
        val source=File.createTempFile("folio-cards-",".jpg",context.cacheDir)
        try{
            for(pair in 1..3){
                val bitmap=Bitmap.createBitmap(1000,1500,Bitmap.Config.ARGB_8888)
                try{
                    val canvas=Canvas(bitmap);canvas.drawColor(Color.DKGRAY)
                    val white=Paint().apply{color=Color.WHITE};val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=70f}
                    canvas.drawRect(100f,100f,900f,600f,white);canvas.drawRect(100f,800f,900f,1300f,white)
                    canvas.drawText("FOLIO FRONT $pair",160f,330f,ink);canvas.drawText("FOLIO BACK $pair",160f,1030f,ink)
                    source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,98,it)}
                }finally{bitmap.recycle()}
                var snapshot=repository.snapshot(document.document.id)
                repository.importImage(snapshot.document.id,snapshot.document.revision,Uri.fromFile(source))
                snapshot=repository.snapshot(snapshot.document.id)
                val photo=snapshot.regions.maxBy{it.position}
                val candidates=repository.candidates(snapshot.document.id,photo.id);assertEquals(2,candidates.size)
                repository.splitCapture(snapshot.document.id,snapshot.document.revision,photo.id,candidates)
                snapshot=repository.snapshot(snapshot.document.id)
                for(region in snapshot.regions.filter{it.captureId==photo.captureId}){
                    repository.recognize(snapshot.document.id,region.id)
                    val current=repository.snapshot(snapshot.document.id)
                    val recognized=current.regions.single{it.id==region.id}.recognizedText.orEmpty()
                    val side=when{recognized.contains("FRONT")->"FRONT";recognized.contains("BACK")->"BACK";else->error("Synthetic card was not read: $recognized")}
                    repository.labelCard(current.document.id,current.document.revision,region.id,side,"Pair $pair")
                }
            }
            var snapshot=repository.snapshot(document.document.id)
            assertEquals(6,snapshot.regions.size)
            repository.layout(snapshot.document.id,snapshot.document.revision,"Grid","A4",36f)
            snapshot=repository.snapshot(snapshot.document.id)
            assertEquals(listOf(4,2),repository.layoutSheets(snapshot).map{it.placements.size})
            val export=repository.export(snapshot.document.id,snapshot.document.revision,"SEARCHABLE")
            assertEquals(2,export.pageCount)
            PDDocument.load(File(export.path)).use{pdf->
                val text=PDFTextStripper().getText(pdf)
                for(pair in 1..3){assertTrue(text.contains("FRONT $pair"));assertTrue(text.contains("BACK $pair"))}
            }
        }finally{source.delete();PrivateImageStore(context).removeDocument(document.document.id);database.close()}
    }
}
