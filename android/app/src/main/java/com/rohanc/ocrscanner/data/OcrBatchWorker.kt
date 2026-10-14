package com.rohanc.ocrscanner.data

import android.content.Context
import androidx.work.*
import com.rohanc.ocrscanner.ScannerApplication
import kotlinx.coroutines.CancellationException

class OcrBatchWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val id=inputData.getString("documentId")?:return Result.failure()
        val repository=(applicationContext as ScannerApplication).repository
        return try {
            val ids=repository.snapshot(id).regions.sortedBy{it.position}.map{it.id}
            require(ids.isNotEmpty()) { "Add a page first." }
            for((index,regionId) in ids.withIndex()) {
                if(isStopped) throw CancellationException()
                val region=repository.snapshot(id).regions.singleOrNull{it.id==regionId}?:continue
                if(region.ocrRevision!=region.imageRevision || region.recognizedText==null) repository.recognize(id,regionId)
                setProgress(workDataOf("done" to index+1,"total" to ids.size))
            }
            Result.success()
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(failure:Exception) { Result.failure(workDataOf("error" to (failure.message?:"Text recognition failed."))) }
    }
}
