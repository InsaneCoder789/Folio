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
import com.rohanc.ocrscanner.feature.ui.*
import androidx.compose.ui.unit.dp

@Composable
fun CameraCapture(onCaptured:(File)->Unit,onError:(String)->Unit,onImport:()->Unit,count:Int=0,thumbnails:List<String> = emptyList(),cards:Boolean=false,paired:Boolean=false,onDone:()->Unit={}) {
    val alive=remember{java.util.concurrent.atomic.AtomicBoolean(true)}
    DisposableEffect(Unit){onDispose{alive.set(false)}}
    val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){allowed=it}
    var capture by remember{mutableStateOf<ImageCapture?>(null)};var taking by remember{mutableStateOf(false)}
    if(!allowed) {
        Column(verticalArrangement=Arrangement.spacedBy(androidx.compose.ui.unit.Dp(16f))) {
            Text("Allow camera access to photograph documents. You can also import existing images.")
            SilkButton("Allow camera","camera"){permission.launch(Manifest.permission.CAMERA)}
            SilkButton("Import images","image",false,onClick=onImport)
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
    CaptureUi(count,taking,capture!=null,onCapture={
            val camera=capture?:return@CaptureUi;taking=true
            camera.targetRotation=view.display?.rotation?:android.view.Surface.ROTATION_0
            val file=File.createTempFile("folio-camera-",".jpg",context.cacheDir)
            camera.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),ContextCompat.getMainExecutor(context),object:ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result:ImageCapture.OutputFileResults){taking=false;if(alive.get())onCaptured(file) else file.delete()}
                override fun onError(exception:ImageCaptureException){taking=false;file.delete();if(alive.get())onError(exception.message?:"Capture failed.")}
            })
        },onImport=onImport,onDone=onDone,thumbnails=thumbnails,cards=cards,paired=paired){
            AndroidView(factory={view},modifier=Modifier.fillMaxSize())
        }
}
