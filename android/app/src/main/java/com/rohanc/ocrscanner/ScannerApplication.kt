package com.rohanc.ocrscanner

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.*
import java.io.File
import com.rohanc.ocrscanner.core.pdf.ScannerPdfExporter
import com.rohanc.ocrscanner.data.*

class ScannerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // A new process cannot have an active foreground export from its predecessor.
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            File(filesDir,"scans").walkTopDown().filter{it.isFile && it.extension=="partial"}.forEach{it.delete()}
        }
    }
    val preferences by lazy { ScannerPreferences(this) }
    val repository: DocumentRepository by lazy {
        val database = Room.databaseBuilder(this, ScannerDatabase::class.java, "scanner.db").build()
        DocumentRepository(database, PrivateImageStore(this), MlKitOcrEngine(),pdf=ScannerPdfExporter(this))
    }
}
