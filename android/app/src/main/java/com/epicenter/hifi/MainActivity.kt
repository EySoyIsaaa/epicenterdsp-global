package com.epicenter.hifi

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import com.epicenter.hifi.ui.EpicenterGlobalApp

class MainActivity : ComponentActivity() {
    private var globalService by mutableStateOf<EpicenterGlobalAudioService?>(null)
    private val projectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val projectionRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, EpicenterGlobalAudioService::class.java).apply {
                action = EpicenterGlobalAudioService.ACTION_START
                putExtra(EpicenterGlobalAudioService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(EpicenterGlobalAudioService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(this, intent)
            else startService(intent)
        }
    }

    private val audioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) requestProjection() }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { requestAudioPermission() }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            globalService = (binder as? EpicenterGlobalAudioService.LocalBinder)?.service
        }
        override fun onServiceDisconnected(name: ComponentName?) { globalService = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindService(Intent(this, EpicenterGlobalAudioService::class.java), connection, BIND_AUTO_CREATE)
        setContent {
            EpicenterGlobalApp(
                service = globalService,
                onStartCapture = ::beginCapture,
                onStopCapture = { globalService?.stopCapture() }
            )
        }
    }

    private fun beginCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            globalService?.setUnavailable("La captura de audio externo requiere Android 10 o posterior.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestAudioPermission()
    }

    private fun requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else requestProjection()
    }

    private fun requestProjection() {
        // Android muestra consentimiento cada vez. El token de proyección solo se usa una vez.
        projectionRequest.launch(projectionManager.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        try { unbindService(connection) } catch (_: IllegalArgumentException) { }
        super.onDestroy()
    }
}
