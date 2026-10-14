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
    @ColumnInfo(defaultValue = "'DOCUMENT'") val mode: String = "DOCUMENT",
    @ColumnInfo(defaultValue = "'Stacked'") val template: String = "Stacked",
    @ColumnInfo(defaultValue = "'A4'") val paper: String = "A4",
    @ColumnInfo(defaultValue = "36") val margin: Float = 36f,
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
    val correctedPath: String? = null, val correctedWidth: Int? = null, val correctedHeight: Int? = null,
    @ColumnInfo(defaultValue = "''") val cornersJson: String = "",
    @ColumnInfo(defaultValue = "0") val rotation: Int = 0,
    @ColumnInfo(defaultValue = "'COLOR'") val enhancement: String = "COLOR",
    @ColumnInfo(defaultValue = "0") val brightness: Float = 0f,
    @ColumnInfo(defaultValue = "1") val contrast: Float = 1f,
    @ColumnInfo(defaultValue = "'SINGLE'") val side: String = "SINGLE",
    @ColumnInfo(defaultValue = "''") val pairLabel: String = "",
)
@Entity(tableName = "exports", foreignKeys = [ForeignKey(
    entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE
)], indices = [Index("documentId")])
data class ExportEntity(@PrimaryKey val id: String, val documentId: String, val sourceRevision: Long,
    val mode: String, val path: String, val pageCount: Int, val createdAt: Long)

data class DocumentSnapshot(
    @Embedded val document: DocumentEntity,
    @Relation(parentColumn = "id", entityColumn = "documentId") val captures: List<CaptureEntity>,
    @Relation(parentColumn = "id", entityColumn = "documentId") val regions: List<RegionEntity>,
    @Relation(parentColumn = "id", entityColumn = "documentId") val exports: List<ExportEntity> = emptyList(),
)
@Dao
interface ScannerDao {
    @Query("SELECT * FROM documents ORDER BY updatedAt DESC") fun observeLibrary(): Flow<List<DocumentEntity>>
    @Transaction @Query("SELECT * FROM documents WHERE id = :id") suspend fun snapshot(id: String): DocumentSnapshot?
    @Transaction @Query("SELECT * FROM documents WHERE id = :id") fun observeSnapshot(id: String): Flow<DocumentSnapshot?>
    @Query("SELECT * FROM documents WHERE instr(lower(title),lower(:query)) > 0 OR EXISTS (SELECT 1 FROM regions WHERE documentId = documents.id AND instr(lower(COALESCE(reviewedText,recognizedText,'')),lower(:query)) > 0) ORDER BY updatedAt DESC")
    fun search(query: String): Flow<List<DocumentEntity>>
    @Query("SELECT EXISTS(SELECT 1 FROM captures WHERE id = :id)") suspend fun hasCapture(id: String): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM regions WHERE correctedPath = :path) OR EXISTS(SELECT 1 FROM exports WHERE path = :path)")
    suspend fun hasPublishedFile(path: String): Boolean
    @Insert suspend fun insertExport(export: ExportEntity)
    @Query("DELETE FROM regions WHERE id = :id") suspend fun deleteRegion(id: String)
    @Insert suspend fun insertDocument(document: DocumentEntity)
    @Insert suspend fun insertCapture(capture: CaptureEntity)
    @Insert suspend fun insertRegion(region: RegionEntity)
    @Update suspend fun updateDocument(document: DocumentEntity)
    @Update suspend fun updateRegion(region: RegionEntity)
    @Query("DELETE FROM documents WHERE id = :id") suspend fun delete(id: String)
}
@Database(entities = [DocumentEntity::class, CaptureEntity::class, RegionEntity::class, ExportEntity::class], version = 2, exportSchema = true, autoMigrations = [AutoMigration(from = 1, to = 2)])
abstract class ScannerDatabase : RoomDatabase() { abstract fun scannerDao(): ScannerDao }
