package com.rohanc.ocrscanner.data

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rohanc.ocrscanner.core.*
import com.rohanc.ocrscanner.core.pdf.ScannerPdfExporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RepositoryPipelineTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private suspend fun pipeline(action:suspend(DocumentRepository,String)->Unit){
        val db=Room.inMemoryDatabaseBuilder(context,ScannerDatabase::class.java).build()
        val repo=DocumentRepository(db,PrivateImageStore(context),MlKitOcrEngine(),pdf=ScannerPdfExporter(context))
        val doc=repo.create();val fixture=File.createTempFile("folio-pipeline-",".jpg",context.cacheDir)
        try{
            val bitmap=Bitmap.createBitmap(1000,500,Bitmap.Config.ARGB_8888)
            try{val c=Canvas(bitmap);c.drawColor(Color.WHITE);c.drawText("FOLIO DOCUMENT",60f,180f,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=64f});fixture.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,98,it)}}finally{bitmap.recycle()}
            repo.importImage(doc.document.id,0,Uri.fromFile(fixture))
            action(repo,doc.document.id)
        }finally{fixture.delete();PrivateImageStore(context).removeDocument(doc.document.id);db.close()}
    }
    @Test fun importRecognizeExportAndEditRetainPreviousPdf()=runBlocking{pipeline{repo,id->
        var snapshot=repo.snapshot(id);val region=snapshot.regions.single()
        repo.recognize(id,region.id);snapshot=repo.snapshot(id)
        assertTrue(snapshot.regions.single().recognizedText!!.contains("FOLIO"))
        val export=repo.export(id,snapshot.document.revision,"SEARCHABLE")
        assertTrue(File(export.path).exists())
        repo.rename(id,snapshot.document.revision,"Changed title")
        assertNotEquals(export.sourceRevision,repo.snapshot(id).document.revision)
        assertTrue(File(export.path).exists())
        try{repo.export(id,snapshot.document.revision,"SEARCHABLE");fail("Stale export accepted")}catch(_:StaleRevision){}
    }}
    @Test fun correctionInvalidatesTextButKeepsOriginal()=runBlocking{pipeline{repo,id->
        var s=repo.snapshot(id);val r=s.regions.single();val c=s.captures.single()
        repo.recognize(id,r.id);s=repo.snapshot(id)
        repo.correct(id,s.document.revision,r.id,EditRecipe(Quad.full(c.width,c.height),enhancement=Enhancement.GRAYSCALE))
        s=repo.snapshot(id);assertNull(s.regions.single().ocrRevision);assertNull(s.regions.single().recognizedText)
        assertEquals(1L,s.regions.single().imageRevision);assertTrue(File(c.originalPath).exists());assertTrue(File(s.regions.single().correctedPath!!).exists())
    }}
    @Test fun failedBatchImportLeavesNoPartialPagesOrFiles()=runBlocking{pipeline{repo,id->
        val before=repo.snapshot(id);val source=File(before.captures.single().uprightPath)
        val bad=File.createTempFile("folio-bad-batch-",".jpg",context.cacheDir).apply{writeText("invalid")}
        val directory=File(source.parent!!);val originalFiles=directory.listFiles().orEmpty().map{it.name}.toSet()
        try{
            try{repo.importImages(id,before.document.revision,listOf(Uri.fromFile(source),Uri.fromFile(bad)));fail("Bad batch accepted")}
            catch(_:android.graphics.ImageDecoder.DecodeException){}
            val after=repo.snapshot(id)
            assertEquals(before.document.revision,after.document.revision)
            assertEquals(before.regions,after.regions);assertEquals(before.captures,after.captures)
            assertEquals(originalFiles,directory.listFiles().orEmpty().map{it.name}.toSet())
        }finally{bad.delete()}
    }}
    @Test fun validBatchPublishesEveryPageInOneRevision()=runBlocking{pipeline{repo,id->
        val before=repo.snapshot(id);val uri=Uri.fromFile(File(before.captures.single().uprightPath))
        repo.importImages(id,before.document.revision,listOf(uri,uri))
        val after=repo.snapshot(id)
        assertEquals(before.document.revision+1,after.document.revision)
        assertEquals(3,after.captures.size);assertEquals(listOf(0,1,2),after.regions.map{it.position}.sorted())
    }}
    @Test fun retakeReplacesOneRegionAndInvalidatesOcr()=runBlocking{pipeline{repo,id->
        val before=repo.snapshot(id);val original=before.captures.single();val region=before.regions.single()
        repo.recognize(id,region.id)
        repo.retake(id,repo.snapshot(id).document.revision,region.id,Uri.fromFile(File(original.uprightPath)))
        val after=repo.snapshot(id);val replaced=after.regions.single()
        assertEquals(region.id,replaced.id);assertEquals(1L,replaced.imageRevision);assertNull(replaced.recognizedText)
        assertNotEquals(original.id,replaced.captureId);assertTrue(File(original.originalPath).exists())
    }}
    @Test fun diskDatabaseSurvivesReopening()=runBlocking{
        val name="folio-persistence-${java.util.UUID.randomUUID()}.db"
        var db=Room.databaseBuilder(context,ScannerDatabase::class.java,name).build()
        try{
            val repo=DocumentRepository(db,PrivateImageStore(context),MlKitOcrEngine())
            val created=repo.create("Persistent draft");db.close()
            db=Room.databaseBuilder(context,ScannerDatabase::class.java,name).build()
            assertEquals("Persistent draft",db.scannerDao().snapshot(created.document.id)?.document?.title)
        }finally{db.close();context.deleteDatabase(name)}
    }
}
