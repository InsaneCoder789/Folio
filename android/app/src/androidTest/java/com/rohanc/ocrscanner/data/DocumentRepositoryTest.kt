package com.rohanc.ocrscanner.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rohanc.ocrscanner.core.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DocumentRepositoryTest {
    private lateinit var database: ScannerDatabase
    private lateinit var repository: DocumentRepository
    private val started = CompletableDeferred<Unit>()
    private val finish = CompletableDeferred<Unit>()
    private val engine = object : OcrEngine {
        override suspend fun recognize(input: ImageInput): OcrResult {
            started.complete(Unit); finish.await()
            return OcrResult("recognized", listOf(Word("recognized", listOf(Point(0f, 0f)))), input.width, input.height)
        }
    }
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ScannerDatabase::class.java).build()
        repository = DocumentRepository(database, PrivateImageStore(context), engine)
    }
    @After fun close() { database.close() }
    private suspend fun seed(): DocumentSnapshot {
        val snapshot = repository.create(" Test ")
        val id = snapshot.document.id
        database.scannerDao().insertCapture(CaptureEntity("capture", id, "original", "working", 100, 200))
        database.scannerDao().insertRegion(RegionEntity("first", "capture", id, 0))
        database.scannerDao().insertRegion(RegionEntity("second", "capture", id, 1))
        return repository.snapshot(id)
    }
    @Test fun titleAndOcrReviewPersistInRoom() = runBlocking {
        val source = seed(); val id = source.document.id
        assertEquals("Test", source.document.title)
        finish.complete(Unit)
        repository.recognize(id, "first")
        val recognized = repository.snapshot(id)
        assertEquals("recognized", recognized.regions.single { it.id == "first" }.recognizedText)
        repository.saveReview(id, recognized.document.revision, "first", "Reviewed text")
        assertEquals("Reviewed text", repository.snapshot(id).regions.single { it.id == "first" }.reviewedText)
    }
    @Test fun staleReviewCannotOverwriteNewerEdits() = runBlocking {
        val source = seed(); val id = source.document.id
        repository.rename(id, 0, "Changed")
        try { repository.rename(id, 0, "Old edit"); fail("Stale edit accepted") } catch (_: StaleRevision) { }
        assertEquals("Changed", repository.snapshot(id).document.title)
    }
    @Test fun oldOcrCannotOverwriteImageEdit() = runBlocking {
        val source = seed(); val id = source.document.id
        val pending = async { runCatching { repository.recognize(id, "first") } }
        started.await()
        val region = repository.snapshot(id).regions.single { it.id == "first" }
        database.scannerDao().updateRegion(region.copy(imageRevision = 1))
        finish.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is StaleRevision)
        assertNull(repository.snapshot(id).regions.single { it.id == "first" }.recognizedText)
    }
    @Test fun reorderRetainsOcrAndIsAtomicOnBadOrder() = runBlocking {
        val source = seed(); val id = source.document.id
        finish.complete(Unit); repository.recognize(id, "first")
        val revision = repository.snapshot(id).document.revision
        repository.reorder(id, revision, listOf("second", "first"))
        val moved = repository.snapshot(id)
        assertEquals(listOf("second", "first"), moved.regions.sortedBy { it.position }.map { it.id })
        assertEquals("recognized", moved.regions.single { it.id == "first" }.recognizedText)
        try { repository.reorder(id, moved.document.revision, listOf("first", "first")); fail("Bad order accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(moved.document.revision, repository.snapshot(id).document.revision)
    }
    @Test fun deletionCascadesToCapturesAndRegions() = runBlocking {
        val source = seed(); repository.delete(source.document.id, source.document.revision)
        assertNull(database.scannerDao().snapshot(source.document.id))
    }
    @Test fun cancelledOcrDoesNotCommit() = runBlocking {
        val source = seed(); val id = source.document.id
        val pending = launch { repository.recognize(id, "first") }
        started.await(); pending.cancelAndJoin()
        assertNull(repository.snapshot(id).regions.single { it.id == "first" }.recognizedText)
    }
}
