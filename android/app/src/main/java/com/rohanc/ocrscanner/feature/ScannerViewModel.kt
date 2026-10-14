package com.rohanc.ocrscanner.feature

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rohanc.ocrscanner.ScannerApplication
import com.rohanc.ocrscanner.data.DocumentSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ScannerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as ScannerApplication).repository
    val library = repository.library.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _document = MutableStateFlow<DocumentSnapshot?>(null)
    val document = _document.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun clearMessage() { _message.value = null }
    fun close() { if (!_busy.value) _document.value = null }
    fun open(id: String) = work { _document.value = repository.snapshot(id) }
    fun importImage(uri: Uri) = work {
        val current = _document.value ?: repository.create().also { _document.value = it }
        repository.importImage(current.document.id, current.document.revision, uri)
        refresh(current.document.id)
    }
    fun rename(title: String) = edit { repository.rename(it.document.id, it.document.revision, title) }
    fun recognize(regionId: String) = edit { repository.recognize(it.document.id, regionId) }
    fun review(regionId: String, text: String) = edit { repository.saveReview(it.document.id, it.document.revision, regionId, text) }
    fun moveUp(regionId: String) = edit { snapshot ->
        val ids = snapshot.regions.sortedBy { it.position }.map { it.id }.toMutableList()
        val index = ids.indexOf(regionId)
        if (index > 0) {
            val previous = ids[index - 1]; ids[index - 1] = regionId; ids[index] = previous
            repository.reorder(snapshot.document.id, snapshot.document.revision, ids)
        }
    }
    fun delete() = work {
        val current = _document.value ?: return@work
        repository.delete(current.document.id, current.document.revision)
        _document.value = null
    }
    private suspend fun refresh(id: String) { _document.value = repository.snapshot(id) }
    private fun edit(action: suspend (DocumentSnapshot) -> Unit) = work {
        val current = _document.value ?: return@work
        action(current); refresh(current.document.id)
    }
    private fun work(action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                _document.value?.document?.id?.let { id ->
                    try { refresh(id) } catch (_: Exception) { /* Preserve the retry surface. */ }
                }
                _message.value = failure.message ?: "This action could not finish. Please retry."
            }
            finally { _busy.value = false }
        }
    }
}
