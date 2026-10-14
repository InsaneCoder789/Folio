package com.rohanc.ocrscanner.data

import android.net.Uri
import androidx.room.withTransaction
import com.rohanc.ocrscanner.core.*
import com.rohanc.ocrscanner.core.vision.OpenCvProcessor
import com.rohanc.ocrscanner.core.pdf.ScannerPdfExporter
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** All edits and processing commits pass through this transactional boundary. */
class DocumentRepository(
    private val database: ScannerDatabase,
    private val images: PrivateImageStore,
    private val ocr: OcrEngine,
    private val vision: OpenCvProcessor = OpenCvProcessor(),
    private val pdf: ScannerPdfExporter? = null,
) {
    private val dao = database.scannerDao()
    val library: Flow<List<DocumentEntity>> = dao.observeLibrary()
    fun observe(id: String) = dao.observeSnapshot(id)
    fun search(query: String) = dao.search(query)
    suspend fun snapshot(id: String) = dao.snapshot(id) ?: error("Document no longer exists.")
    suspend fun create(title: String = "Untitled scan", mode: String = "DOCUMENT", paper: String = "A4"): DocumentSnapshot {
        require(mode in listOf("DOCUMENT","CARDS") && paper in listOf("A4","Letter"))
        val document = DocumentEntity(UUID.randomUUID().toString(), cleanTitle(title), updatedAt = System.currentTimeMillis(),mode = mode,paper = paper)
        dao.insertDocument(document)
        return snapshot(document.id)
    }
    suspend fun rename(id: String, expectedRevision: Long, title: String) = database.withTransaction {
        val current = activeSnapshot(id)
        RevisionRules.requireCurrent(expectedRevision, current.document.revision)
        dao.updateDocument(current.document.copy(title = cleanTitle(title), revision = expectedRevision + 1, updatedAt = System.currentTimeMillis()))
    }
    suspend fun importImage(id: String, expectedRevision: Long, uri: Uri, maxSide: Int = 3000) =
        importImages(id, expectedRevision, listOf(uri), maxSide)

    /** Stage files first; publish the whole selection in one revision-checked transaction. */
    suspend fun importImages(id: String, expectedRevision: Long, uris: List<Uri>, maxSide: Int = 3000) {
        require(uris.isNotEmpty() && uris.size <= 12) { "Choose between one and twelve photos." }
        require(maxSide in listOf(2000, 3000))
        val before = activeSnapshot(id)
        RevisionRules.requireCurrent(expectedRevision, before.document.revision)
        require(before.regions.size + uris.size <= 48) { "Use at most 48 pages per document." }
        val staged = mutableListOf<CaptureEntity>()
        var committed = false
        try {
            for (uri in uris) {
                coroutineContext.ensureActive()
                staged.add(images.import(id, uri, maxSide))
            }
            database.withTransaction {
                val current = activeSnapshot(id)
                RevisionRules.requireCurrent(expectedRevision, current.document.revision)
                staged.forEachIndexed { index, capture ->
                    dao.insertCapture(capture)
                    dao.insertRegion(RegionEntity(UUID.randomUUID().toString(), capture.id, id, current.regions.size + index))
                }
                touch(current.document)
            }
            committed = true
        } finally {
            if (!committed) withContext(NonCancellable + Dispatchers.IO) {
                // Cancellation can arrive immediately after SQLite commits. Never remove committed files.
                for (capture in staged) if (!dao.hasCapture(capture.id)) images.removeCapture(capture)
            }
        }
    }
    suspend fun retake(id:String,expectedRevision:Long,regionId:String,uri:Uri) {
        val before=activeSnapshot(id);RevisionRules.requireCurrent(expectedRevision,before.document.revision)
        val old=before.regions.single{it.id==regionId}
        val capture=images.import(id,uri);var committed=false
        try {
            coroutineContext.ensureActive()
            database.withTransaction {
                val now=activeSnapshot(id);RevisionRules.requireCurrent(expectedRevision,now.document.revision)
                dao.insertCapture(capture)
                dao.updateRegion(old.copy(captureId=capture.id,correctedPath=null,correctedWidth=null,correctedHeight=null,
                    cornersJson="",rotation=0,enhancement="COLOR",brightness=0f,contrast=1f,imageRevision=old.imageRevision+1,
                    textRevision=old.textRevision+1,ocrRevision=null,recognizedText=null,reviewedText=null,wordsJson="[]"))
                touch(now.document)
            };committed=true
        } finally { if(!committed)withContext(NonCancellable + Dispatchers.IO){if(!dao.hasCapture(capture.id))images.removeCapture(capture)} }
    }
    suspend fun recognize(id: String, regionId: String) {
        val source = activeSnapshot(id)
        val region = source.regions.single { it.id == regionId }
        val capture = source.captures.single { it.id == region.captureId }
        val input = ImageInput(region.correctedPath ?: capture.uprightPath, region.correctedWidth ?: capture.width, region.correctedHeight ?: capture.height, region.imageRevision)
        val result = ocr.recognize(input)
        coroutineContext.ensureActive()
        database.withTransaction {
            val current = activeSnapshot(id)
            val now = current.regions.singleOrNull { it.id == regionId } ?: throw StaleRevision()
            RevisionRules.requireCurrent(region.imageRevision, now.imageRevision)
            // A concurrent user text review must also survive an old OCR job.
            RevisionRules.requireCurrent(region.textRevision, now.textRevision)
            require(result.width == input.width && result.height == input.height) { "OCR geometry does not match the input image." }
            dao.updateRegion(now.copy(textRevision = now.textRevision + 1, ocrRevision = now.imageRevision, recognizedText = result.text,
                reviewedText = null, wordsJson = encodeWords(result.words)))
            touch(current.document)
        }
    }
    suspend fun saveReview(id: String, expectedRevision: Long, regionId: String, text: String) = database.withTransaction {
        val current = activeSnapshot(id)
        RevisionRules.requireCurrent(expectedRevision, current.document.revision)
        val region = current.regions.single { it.id == regionId }
        require(region.ocrRevision == region.imageRevision && region.recognizedText != null) { "Read this image before reviewing text." }
        dao.updateRegion(region.copy(reviewedText = text, textRevision = region.textRevision + 1))
        touch(current.document)
    }
    suspend fun reorder(id: String, expectedRevision: Long, order: List<String>) = database.withTransaction {
        val current = activeSnapshot(id)
        RevisionRules.requireCurrent(expectedRevision, current.document.revision)
        RevisionRules.reordered(order, current.regions.map { it.id })
        order.forEachIndexed { index, regionId ->
            dao.updateRegion(current.regions.single { it.id == regionId }.copy(position = index))
        }
        touch(current.document)
    }
    fun recipe(region: RegionEntity, capture: CaptureEntity): EditRecipe {
        val quad = if(region.cornersJson.isBlank()) Quad.full(capture.width,capture.height) else
            Quad(JSONArray(region.cornersJson).let { a -> List(4) { i -> a.getJSONArray(i).let { Point(it.getDouble(0).toFloat(),it.getDouble(1).toFloat()) } } })
        return EditRecipe(quad,region.rotation,Enhancement.valueOf(region.enhancement),region.brightness,region.contrast)
    }
    suspend fun correct(id: String, expectedRevision: Long, regionId: String, recipe: EditRecipe) {
        val current = activeSnapshot(id)
        RevisionRules.requireCurrent(expectedRevision,current.document.revision)
        val region=current.regions.single { it.id==regionId }; val capture=current.captures.single { it.id==region.captureId }
        val output=vision.process(capture.uprightPath,recipe,images.documentDirectory(id))
        var committed=false
        try {
            coroutineContext.ensureActive()
            database.withTransaction {
                val now=activeSnapshot(id);RevisionRules.requireCurrent(expectedRevision,now.document.revision)
                dao.updateRegion(region.copy(correctedPath=output.path,correctedWidth=output.width,correctedHeight=output.height,
                    cornersJson=JSONArray().apply{recipe.quad.points.forEach{put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble()))}}.toString(),
                    rotation=recipe.rotation,enhancement=recipe.enhancement.name,brightness=recipe.brightness,contrast=recipe.contrast,
                    imageRevision=region.imageRevision+1,textRevision=region.textRevision+1,ocrRevision=null,recognizedText=null,reviewedText=null,wordsJson="[]"))
                touch(now.document)
            };committed=true
        } finally { if(!committed) removeUnpublishedFiles(listOf(output.path)) }
    }
    suspend fun candidates(id:String, regionId:String): List<Quad> {
        val current=activeSnapshot(id);val region=current.regions.single{it.id==regionId};val capture=current.captures.single{it.id==region.captureId}
        return vision.detect(capture.uprightPath,current.document.mode=="CARDS")
    }
    suspend fun splitCapture(id:String, expectedRevision:Long, regionId:String, quads:List<Quad>) {
        require(quads.isNotEmpty() && quads.size<=12) { "Choose between one and twelve regions." }
        val current=activeSnapshot(id);RevisionRules.requireCurrent(expectedRevision,current.document.revision)
        val region=current.regions.single{it.id==regionId};val capture=current.captures.single{it.id==region.captureId}
        val outputs=mutableListOf<ProcessedImage>();var committed=false
        try {
            for(quad in quads) outputs.add(vision.process(capture.uprightPath,EditRecipe(quad),images.documentDirectory(id)))
            database.withTransaction {
                val now=activeSnapshot(id);RevisionRules.requireCurrent(expectedRevision,now.document.revision)
                val retained=now.regions.sortedBy{it.position}.filter{it.captureId!=capture.id}
                require(retained.size+quads.size<=48)
                now.regions.filter{it.captureId==capture.id}.forEach{dao.deleteRegion(it.id)}
                retained.forEachIndexed { i,r -> dao.updateRegion(r.copy(position=i)) }
                outputs.forEachIndexed { i,out -> dao.insertRegion(RegionEntity(UUID.randomUUID().toString(),capture.id,id,retained.size+i,
                    correctedPath=out.path,correctedWidth=out.width,correctedHeight=out.height,
                    cornersJson=JSONArray().apply{quads[i].points.forEach{put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble()))}}.toString())) }
                touch(now.document)
            };committed=true
        } finally { if(!committed) removeUnpublishedFiles(outputs.map{it.path}) }
    }
    suspend fun labelCard(id:String, expectedRevision:Long, regionId:String, side:String, label:String) = database.withTransaction {
        require(side in CardSide.entries.map{it.name});require(label.length<=80)
        val now=activeSnapshot(id);RevisionRules.requireCurrent(expectedRevision,now.document.revision)
        require(now.document.mode=="CARDS")
        dao.updateRegion(now.regions.single{it.id==regionId}.copy(side=side,pairLabel=label.trim()));touch(now.document)
    }
    suspend fun layout(id:String, expectedRevision:Long, template:String, paper:String, margin:Float) = database.withTransaction {
        require(template in listOf("Stacked","Side by side","Grid") && paper in listOf("A4","Letter") && margin in 18f..60f)
        val now=activeSnapshot(id);RevisionRules.requireCurrent(expectedRevision,now.document.revision)
        dao.updateDocument(now.document.copy(template=template,paper=paper,margin=margin,revision=expectedRevision+1,updatedAt=System.currentTimeMillis()))
    }
    fun layoutSheets(snapshot:DocumentSnapshot):List<Sheet> = PageLayout.sheets(snapshot.regions.sortedBy{it.position}.map{r ->
        val c=snapshot.captures.single{it.id==r.captureId}
        LayoutRegion(r.id,r.correctedWidth?:c.width,r.correctedHeight?:c.height,CardSide.valueOf(r.side),r.pairLabel)
    },snapshot.document.mode=="CARDS",snapshot.document.template,snapshot.document.paper,snapshot.document.margin)
    suspend fun addRegion(id:String,expectedRevision:Long,captureId:String) = database.withTransaction {
        val now=activeSnapshot(id);RevisionRules.requireCurrent(expectedRevision,now.document.revision)
        require(now.document.mode=="CARDS" && now.regions.size<48 && now.captures.any{it.id==captureId})
        dao.insertRegion(RegionEntity(UUID.randomUUID().toString(),captureId,id,now.regions.size));touch(now.document)
    }
    suspend fun removeRegion(id:String, expectedRevision:Long, regionId:String) = database.withTransaction {
        val now=activeSnapshot(id);RevisionRules.requireCurrent(expectedRevision,now.document.revision)
        require(now.regions.any{it.id==regionId});dao.deleteRegion(regionId)
        now.regions.sortedBy{it.position}.filter{it.id!=regionId}.forEachIndexed{i,r->dao.updateRegion(r.copy(position=i))};touch(now.document)
    }
    suspend fun export(id:String, expectedRevision:Long, mode:String):ExportEntity {
        val now=activeSnapshot(id);RevisionRules.requireCurrent(expectedRevision,now.document.revision)
        require(mode in listOf("SEARCHABLE","TEXT"))
        require(now.regions.isNotEmpty() && now.regions.all{it.ocrRevision==it.imageRevision && it.recognizedText!=null}) { "Read every page before creating a PDF." }
        val exporter=pdf?:error("PDF engine is unavailable.")
        val result=exporter.export(now,mode,layoutSheets(if(mode=="TEXT") now.copy(document=now.document.copy(mode="DOCUMENT")) else now),images.documentDirectory(id))
        var committed=false
        try {
            coroutineContext.ensureActive()
            database.withTransaction {
                RevisionRules.requireCurrent(expectedRevision,activeSnapshot(id).document.revision)
                dao.insertExport(result)
            };committed=true;return result
        } finally { if(!committed) removeUnpublishedFiles(listOf(result.path)) }
    }
    suspend fun lab(id:String,regionId:String,stage:String):ProcessedImage {
        require(stage in listOf("Grayscale","Edges","Threshold"))
        val now=activeSnapshot(id);val r=now.regions.single{it.id==regionId};val c=now.captures.single{it.id==r.captureId}
        return vision.process(c.uprightPath,recipe(r,c).copy(enhancement=if(stage=="Threshold") Enhancement.BLACK_WHITE else Enhancement.GRAYSCALE),images.documentDirectory(id),if(stage=="Threshold") null else stage)
    }
    suspend fun delete(id: String, expectedRevision: Long) {
        database.withTransaction {
            val current = snapshot(id)
            RevisionRules.requireCurrent(expectedRevision, current.document.revision)
            dao.updateDocument(current.document.copy(deleting = true, revision = expectedRevision + 1))
        }
        // Tombstone first: an interrupted/failed cleanup remains visible and retryable.
        // No new edits or OCR commits are accepted for a deleting document.
        withContext(NonCancellable) {
            images.removeDocument(id)
            dao.delete(id)
        }
    }
    private suspend fun removeUnpublishedFiles(paths: List<String>) = withContext(NonCancellable + Dispatchers.IO) {
        for (path in paths) if (!dao.hasPublishedFile(path)) File(path).delete()
    }
    private suspend fun activeSnapshot(id: String): DocumentSnapshot {
        val current = snapshot(id)
        check(!current.document.deleting) { "Deletion is incomplete. Retry Delete to remove remaining files." }
        return current
    }
    private suspend fun touch(document: DocumentEntity) = dao.updateDocument(document.copy(
        revision = document.revision + 1, updatedAt = System.currentTimeMillis()))
    private fun cleanTitle(title: String) = title.trim().ifBlank { "Untitled scan" }.take(160)
    private fun encodeWords(words: List<Word>) = JSONArray().apply {
        words.forEach { word -> put(JSONObject().put("text", word.text).put("polygon", JSONArray().apply {
            word.polygon.forEach { put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) }
        })) }
    }.toString()
}
