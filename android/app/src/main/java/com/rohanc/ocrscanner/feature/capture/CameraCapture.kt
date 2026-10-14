package com.rohanc.ocrscanner.feature.capture

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

@Composable
fun CameraCapture(onCaptured:(File)->Unit,onError:(String)->Unit,onImport:()->Unit) {
    val alive=remember{java.util.concurrent.atomic.AtomicBoolean(true)}
    DisposableEffect(Unit){onDispose{alive.set(false)}}
    val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){allowed=it}
    var capture by remember{mutableStateOf<ImageCapture?>(null)};var taking by remember{mutableStateOf(false)}
    if(!allowed) {
        Column(verticalArrangement=Arrangement.spacedBy(androidx.compose.ui.unit.Dp(16f))) {
            Text("Allow camera access to photograph documents. You can also import existing images.")
            Button(onClick={permission.launch(Manifest.permission.CAMERA)}){Text("Allow camera")}
            OutlinedButton(onClick=onImport){Text("Import photos")}
            TextButton(onClick={context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}){Text("Open app settings")}
        }
        return
    }
    val view=remember{PreviewView(context)}
    DisposableEffect(lifecycle,allowed) {
        var provider:ProcessCameraProvider?=null;var preview:Preview?=null;var shot:ImageCapture?=null;var disposed=false
        val future=ProcessCameraProvider.getInstance(context)
        future.addListener({
            if(!disposed) try {
                provider=future.get();preview=Preview.Builder().build().also{it.setSurfaceProvider(view.surfaceProvider)}
                shot=ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                provider!!.bindToLifecycle(lifecycle,CameraSelector.DEFAULT_BACK_CAMERA,preview!!,shot!!);capture=shot
            }catch(failure:Exception){onError(failure.message?:"Camera unavailable. Import a photo instead.")}
        },ContextCompat.getMainExecutor(context))
        onDispose{disposed=true;capture=null;preview?.let{provider?.unbind(it)};shot?.let{provider?.unbind(it)}}
    }
    Column(verticalArrangement=Arrangement.spacedBy(androidx.compose.ui.unit.Dp(16f))) {
        AndroidView(factory={view},modifier=Modifier.fillMaxWidth().weight(1f))
        Text("Keep the entire page visible. Avoid glare and overlapping cards.")
        Button(enabled=capture!=null && !taking,onClick={
            val camera=capture?:return@Button;taking=true
            camera.targetRotation=view.display?.rotation?:android.view.Surface.ROTATION_0
            val file=File.createTempFile("folio-camera-",".jpg",context.cacheDir)
            camera.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),ContextCompat.getMainExecutor(context),object:ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result:ImageCapture.OutputFileResults){taking=false;if(alive.get())onCaptured(file) else file.delete()}
                override fun onError(exception:ImageCaptureException){taking=false;file.delete();if(alive.get())onError(exception.message?:"Capture failed.")}
            })
        },modifier=Modifier.fillMaxWidth()){Text(if(taking)"Capturing…" else "Capture page")}
    }
}
