package com.rohanc.ocrscanner.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val revision: Long = 0,
    val updatedAt: Long,
    val deleting: Boolean = false,
)
@Entity(tableName = "captures", foreignKeys = [ForeignKey(
    entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE
)], indices = [Index("documentId")])
data class CaptureEntity(
    @PrimaryKey val id: String, val documentId: String,
    val originalPath: String, val uprightPath: String, val width: Int, val height: Int,
)
@Entity(tableName = "regions", foreignKeys = [ForeignKey(
    entity = CaptureEntity::class, parentColumns = ["id"], childColumns = ["captureId"], onDelete = ForeignKey.CASCADE
)], indices = [Index("captureId"), Index("documentId")])
data class RegionEntity(
    @PrimaryKey val id: String, val captureId: String, val documentId: String,
    val position: Int, val imageRevision: Long = 0,
    val textRevision: Long = 0,
    val ocrRevision: Long? = null, val recognizedText: String? = null,
    val reviewedText: String? = null, val wordsJson: String = "[]",
)
data class DocumentSnapshot(
    @Embedded val document: DocumentEntity,
    @Relation(parentColumn = "id", entityColumn = "documentId") val captures: List<CaptureEntity>,
    @Relation(parentColumn = "id", entityColumn = "documentId") val regions: List<RegionEntity>,
)
@Dao
interface ScannerDao {
    @Query("SELECT * FROM documents ORDER BY updatedAt DESC") fun observeLibrary(): Flow<List<DocumentEntity>>
    @Transaction @Query("SELECT * FROM documents WHERE id = :id") suspend fun snapshot(id: String): DocumentSnapshot?
    @Insert suspend fun insertDocument(document: DocumentEntity)
    @Insert suspend fun insertCapture(capture: CaptureEntity)
    @Insert suspend fun insertRegion(region: RegionEntity)
    @Update suspend fun updateDocument(document: DocumentEntity)
    @Update suspend fun updateRegion(region: RegionEntity)
    @Query("DELETE FROM documents WHERE id = :id") suspend fun delete(id: String)
}
@Database(entities = [DocumentEntity::class, CaptureEntity::class, RegionEntity::class], version = 1, exportSchema = true)
abstract class ScannerDatabase : RoomDatabase() { abstract fun scannerDao(): ScannerDao }
