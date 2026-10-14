package com.rohanc.ocrscanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rohanc.ocrscanner.data.RegionEntity
import com.rohanc.ocrscanner.feature.ScannerViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF6267B4), background = Color(0xFFE8EAF0), surface = Color(0xFFE8EAF0)
            )) { Scanner() }
        }
    }
}

/** A small native integration surface. The permanent website owns the full design reference. */
@Composable
private fun Scanner(vm: ScannerViewModel = viewModel()) {
    val library by vm.library.collectAsStateWithLifecycle()
    val document by vm.document.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importImage(uri)
    }
    BackHandler(document != null) { if (!busy) vm.close() }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(12.dp))
            Text(if (document == null) "Your library" else "Review scan", style = MaterialTheme.typography.headlineLarge)
            Text("Native foundation · local images and Latin OCR", style = MaterialTheme.typography.bodySmall)
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Processing on this device…") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (document != null) OutlinedButton(onClick = vm::close, enabled = !busy) { Text("Library") }
                Button(onClick = { picker.launch(arrayOf("image/*")) }, enabled = !busy && document?.document?.deleting != true) {
                    Text(if (document == null) "Import image" else "Add page")
                }
            }
            val current = document
            if (current == null) {
                if (library.isEmpty()) Text("Import a document to create your first draft.")
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(library, key = { it.id }) { item ->
                        OutlinedButton(onClick = { vm.open(item.id) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(item.title + if (item.deleting) " · deletion pending" else "", modifier = Modifier.padding(12.dp))
                        }
                    }
                }
            } else {
                if (current.document.deleting) Text("Deletion is incomplete. Retry Delete to finish removing this document.")
                var title by rememberSaveable(current.document.id, current.document.revision) { mutableStateOf(current.document.title) }
                OutlinedTextField(title, { title = it }, label = { Text("Document title") }, enabled = !busy && !current.document.deleting, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.rename(title) }, enabled = !busy && !current.document.deleting && title.trim() != current.document.title) { Text("Save title") }
                    TextButton(onClick = { confirmDelete = true }, enabled = !busy) { Text("Delete") }
                }
                if (current.regions.isEmpty()) Text("No pages yet. Add an image to continue.")
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(current.regions.sortedBy { it.position }, key = { it.id }) { region ->
                        RegionEditor(region, busy || current.document.deleting, onRecognize = { vm.recognize(region.id) },
                            onReview = { vm.review(region.id, it) }, onMoveUp = { vm.moveUp(region.id) })
                    }
                }
            }
        }
    }
    if (message != null) AlertDialog(onDismissRequest = vm::clearMessage,
        title = { Text("Could not finish") }, text = { Text(message!!) },
        confirmButton = { TextButton(onClick = vm::clearMessage) { Text("OK") } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this document?") }, text = { Text("Its draft, original images and recognized text will be removed from this device.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } })
}

@Composable
private fun RegionEditor(region: RegionEntity, busy: Boolean, onRecognize: () -> Unit, onReview: (String) -> Unit, onMoveUp: () -> Unit) {
    var text by rememberSaveable(region.id, region.textRevision) { mutableStateOf(region.reviewedText ?: region.recognizedText.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Page ${region.position + 1}", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRecognize, enabled = !busy) { Text(if (region.ocrRevision == null) "Read text" else "Re-read image") }
            if (region.position > 0) TextButton(onClick = onMoveUp, enabled = !busy) { Text("Move up") }
        }
        if (region.ocrRevision != null) {
            if (region.recognizedText.isNullOrBlank()) Text("No text found. Try a clearer image.")
            OutlinedTextField(text, { text = it }, enabled = !busy, label = { Text("Reviewed text") }, minLines = 4, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { onReview(text) }, enabled = !busy && text != (region.reviewedText ?: region.recognizedText)) { Text("Save text") }
            if (region.reviewedText != null) Text("Review saved. Text placement must be checked before searchable export.", style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
    }
}
