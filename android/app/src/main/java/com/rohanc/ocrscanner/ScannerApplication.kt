package com.rohanc.ocrscanner

import android.app.Application
import androidx.room.Room
import com.rohanc.ocrscanner.data.*

class ScannerApplication : Application() {
    val repository: DocumentRepository by lazy {
        val database = Room.databaseBuilder(this, ScannerDatabase::class.java, "scanner.db").build()
        DocumentRepository(database, PrivateImageStore(this), MlKitOcrEngine())
    }
}
