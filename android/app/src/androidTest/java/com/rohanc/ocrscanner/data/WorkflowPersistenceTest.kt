package com.rohanc.ocrscanner.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rohanc.ocrscanner.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Storage/workflow checks deliberately use no OCR or learned inference. */
class WorkflowPersistenceTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val noOcr=object:OcrEngine{override suspend fun recognize(input:ImageInput):OcrResult=error("OCR is outside this test")}
    private fun withRepository(action:suspend(DocumentRepository,File,String)->Unit)=runBlocking{
        val name="workflow-${UUID.randomUUID()}.db"
        val db=Room.databaseBuilder(context,ScannerDatabase::class.java,name).build()
        val store=PrivateImageStore(context)
        val repo=DocumentRepository(db,store,noOcr)
        val file=File.createTempFile("workflow-",".jpg",context.cacheDir)
        val bitmap=Bitmap.createBitmap(160,240,Bitmap.Config.ARGB_8888)
        try{bitmap.eraseColor(android.graphics.Color.WHITE);file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)}}finally{bitmap.recycle()}
        try{action(repo,file,name)}finally{
            db.close();context.deleteDatabase(name);file.delete()
        }
    }
    @Test fun pairedCaptureHintsAndWorkflowSurviveDiskReopening()=withRepository{repo,file,name->
        val doc=repo.create("Paired workflow",mode="CARDS",workflow="paired")
        try{
            repo.importImages(doc.document.id,0,listOf(Uri.fromFile(file)))
            var s=repo.snapshot(doc.document.id)
            repo.importImages(s.document.id,s.document.revision,List(3){Uri.fromFile(file)})
            val reopened=Room.databaseBuilder(context,ScannerDatabase::class.java,name).build()
            try{
                s=reopened.scannerDao().snapshot(doc.document.id)!!
                assertEquals("paired",s.document.workflow)
                assertEquals(listOf("FRONT","BACK","FRONT","BACK"),s.regions.sortedBy{it.position}.map{it.side})
                assertEquals(listOf("Card 1","Card 1","Card 2","Card 2"),s.regions.sortedBy{it.position}.map{it.pairLabel})
            }finally{reopened.close()}
        }finally{val s=repo.snapshot(doc.document.id);repo.delete(s.document.id,s.document.revision)}
    }
    @Test fun confirmingCardLabelsIsAtomicAndRejectsStaleEdits()=withRepository{repo,file,_->
        val doc=repo.create("Card labels",mode="CARDS",workflow="multiple")
        try{
            repo.importImages(doc.document.id,0,List(2){Uri.fromFile(file)})
            var s=repo.snapshot(doc.document.id);val regions=s.regions.sortedBy{it.position}
            val invalid=regions.map{CardLabelEdit(it.id,"FRONT","Travel")}
            try{repo.confirmCardLabels(s.document.id,s.document.revision,invalid);fail("Invalid pair accepted")}catch(_:IllegalArgumentException){}
            assertEquals(s,repo.snapshot(doc.document.id))
            val edits=listOf(CardLabelEdit(regions[0].id,"FRONT"," Travel "),CardLabelEdit(regions[1].id,"BACK","Travel"))
            repo.confirmCardLabels(s.document.id,s.document.revision,edits)
            try{repo.confirmCardLabels(s.document.id,s.document.revision,edits);fail("Stale pair accepted")}catch(_:StaleRevision){}
            s=repo.snapshot(s.document.id)
            assertTrue(s.regions.all{it.pairLabel=="Travel"})
            assertEquals(2,repo.layoutSheets(s).single().placements.size)
        }finally{val s=repo.snapshot(doc.document.id);repo.delete(s.document.id,s.document.revision)}
    }
    @Test fun labStagesAreDerivedFilesAndNeverChangeDraftRevisions()=withRepository{repo,file,_->
        val doc=repo.create("Lab source")
        try{
            repo.importImage(doc.document.id,0,Uri.fromFile(file))
            val before=repo.snapshot(doc.document.id)
            for(stage in listOf("Grayscale","Blur","Edges","Warp")){
                val result=repo.lab(doc.document.id,before.regions.single().id,stage)
                try{assertTrue(File(result.path).isFile);assertEquals(159,result.width);assertEquals(239,result.height)}finally{File(result.path).delete()}
            }
            assertEquals(before,repo.snapshot(doc.document.id))
        }finally{val s=repo.snapshot(doc.document.id);repo.delete(s.document.id,s.document.revision)}
    }
    @Test fun maximumDocumentRejectsOverflowWithoutLeakingFilesAndDeletesItsStorage()=withRepository{repo,file,_->
        val doc=repo.create("Maximum page boundary")
        try{
            repeat(4){val s=repo.snapshot(doc.document.id);repo.importImages(s.document.id,s.document.revision,List(12){Uri.fromFile(file)})}
            val full=repo.snapshot(doc.document.id)
            assertEquals(48,full.regions.size);assertEquals(48,full.captures.size)
            val directory=File(full.captures.first().originalPath).parentFile!!
            val names=directory.listFiles().orEmpty().map{it.name}.toSet()
            try{repo.importImage(full.document.id,full.document.revision,Uri.fromFile(file));fail("Document overflow accepted")}catch(_:IllegalArgumentException){}
            assertEquals(full,repo.snapshot(full.document.id))
            assertEquals(names,directory.listFiles().orEmpty().map{it.name}.toSet())
            repo.reorder(full.document.id,full.document.revision,full.regions.sortedByDescending{it.position}.map{it.id})
            val reordered=repo.snapshot(full.document.id)
            assertEquals((0 until 48).toList(),reordered.regions.map{it.position}.sorted())
            repo.delete(reordered.document.id,reordered.document.revision)
            assertFalse("Private capture files remain after deletion",directory.exists())
        }finally{runCatching{repo.snapshot(doc.document.id)}.getOrNull()?.let{repo.delete(it.document.id,it.document.revision)}}
    }
}
