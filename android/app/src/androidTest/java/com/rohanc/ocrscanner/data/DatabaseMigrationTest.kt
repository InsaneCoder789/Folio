package com.rohanc.ocrscanner.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DatabaseMigrationTest {
    @Test fun versionOneDraftSurvivesUpgrade()=runBlocking{
        val context=ApplicationProvider.getApplicationContext<Context>();val name="folio-migration-test.db"
        context.deleteDatabase(name)
        val schema=InstrumentationRegistry.getInstrumentation().context.assets.open("com.rohanc.ocrscanner.data.ScannerDatabase/1.json").bufferedReader().use{JSONObject(it.readText()).getJSONObject("database")}
        val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path,null).use{old->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()){
                val entity=entities.getJSONObject(i);val table=entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=entity.getJSONArray("indices")
                for(j in 0 until indices.length())old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            old.execSQL("INSERT INTO documents (id,title,revision,updatedAt,deleting) VALUES ('legacy','Old draft',2,0,0)")
            old.version=1
        }
        val database=Room.databaseBuilder(context,ScannerDatabase::class.java,name).build()
        try{val snapshot=database.scannerDao().snapshot("legacy")!!;assertEquals("Old draft",snapshot.document.title);assertEquals("DOCUMENT",snapshot.document.mode);assertEquals(2L,snapshot.document.revision);assertTrue(snapshot.exports.isEmpty())}
        finally{database.close();context.deleteDatabase(name)}
    }
}
