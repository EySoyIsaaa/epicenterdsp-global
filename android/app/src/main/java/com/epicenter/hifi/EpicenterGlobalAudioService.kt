package com.epicenter.hifi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.epicenter.hifi.dsp.EpicenterDSPNative
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

enum class GlobalAudioStatus { STOPPED, REQUESTING, CAPTURING, SILENCE, LIMITED, ERROR }

data class GlobalAudioState(
    val status: GlobalAudioStatus = GlobalAudioStatus.STOPPED,
    val message: String = "Procesamiento detenido",
    val sampleRate: Int = 0,
    val peak: Float = 0f,
    val captureToOutputMs: Float? = null,
    val latencyJitterMs: Float? = null,
    val outputRoute: String = "",
    val audioFocusGranted: Boolean? = null
)

class EpicenterGlobalAudioService : Service() {
    inner class LocalBinder : Binder() { val service: EpicenterGlobalAudioService get() = this@EpicenterGlobalAudioService }
    private val binder = LocalBinder()
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var recorder: AudioRecord? = null
    private var output: AudioTrack? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    @Volatile private var audioFocusGranted: Boolean? = null
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.i(TAG, "Audio focus changed: $change")
    }
    private var dsp: EpicenterDSPNative? = null
    private var headphonesMode = true
    private var intensity = 65f
    private var sweepFrequency = 45f
    private var width = 50f
    private var balance = 100f
    private var volume = 100f
    @Volatile private var bassOnlyOutputEnabled = false
    @Volatile private var duckingEnabled = true
    private var bassCutoffHz = 80f
    private var bassGainPercent = 70f
    private var bassPolarityInverted = false
    private var bassPhaseDelayMs = 0f
    private val prefs by lazy { getSharedPreferences("epicenter_global", MODE_PRIVATE) }
    private val _state = MutableStateFlow(GlobalAudioState())
    val state: StateFlow<GlobalAudioState> = _state.asStateFlow()
    private val _bassOnlyEnabled = MutableStateFlow(false)
    val bassOnlyEnabled: StateFlow<Boolean> = _bassOnlyEnabled.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        _bassOnlyEnabled.value = prefs.getBoolean(KEY_BASS_ONLY_ENABLED, false)
        // Prepare the channel before startForegroundService's short promotion deadline.
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) return START_NOT_STICKY

        // Promote immediately. Do not stop an old pipeline, parse projection data,
        // allocate AudioRecord/AudioTrack, or initialize native DSP before this call.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to promote audio capture service", t)
            _state.value = GlobalAudioState(
                GlobalAudioStatus.ERROR,
                t.message ?: "Android no permitió iniciar el servicio de audio."
            )
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        startCapture(intent)
        return START_NOT_STICKY
    }

    fun setUnavailable(message: String) { _state.value = GlobalAudioState(GlobalAudioStatus.LIMITED, message) }
    fun isHeadphonesMode(): Boolean = prefs.getBoolean("engine.headphones", true)
    fun currentIntensity(): Float = prefs.getFloat("engine.intensity", 65f)
    fun customPresetName(): String? = prefs.getString("preset.custom", null)
    fun currentSweepFrequency(): Float = prefs.getFloat("engine.sweep", 45f)
    fun currentWidth(): Float = prefs.getFloat("engine.width", 50f)
    fun currentBalance(): Float = prefs.getFloat("engine.balance", 100f)
    fun currentVolume(): Float = prefs.getFloat("engine.volume", 100f)
    fun isDuckingEnabled(): Boolean = prefs.getBoolean(KEY_DUCKING_ENABLED, true)

    fun setDuckingEnabled(enabled: Boolean) {
        duckingEnabled = enabled
        prefs.edit().putBoolean(KEY_DUCKING_ENABLED, enabled).apply()
        if (!running.get()) return
        if (enabled) requestDuckingFocus() else abandonDuckingFocus()
    }

    fun setBassOnlyEnabled(enabled: Boolean) {
        bassOnlyOutputEnabled = enabled
        prefs.edit().putBoolean(KEY_BASS_ONLY_ENABLED, enabled).apply()
        _bassOnlyEnabled.value = enabled
    }

    fun setBassCutoff(value: Float) {
        bassCutoffHz = value.coerceIn(50f, 100f)
        prefs.edit().putFloat(KEY_BASS_CUTOFF, bassCutoffHz).apply()
        publishBassOnlyConfig()
    }

    fun setBassGainPercent(value: Float) {
        bassGainPercent = value.coerceIn(0f, 150f)
        prefs.edit().putFloat(KEY_BASS_GAIN, bassGainPercent).apply()
        publishBassOnlyConfig()
    }

    fun setBassPolarityInverted(value: Boolean) {
        bassPolarityInverted = value
        prefs.edit().putBoolean(KEY_BASS_POLARITY, value).apply()
        publishBassOnlyConfig()
    }

    fun setBassPhaseDelay(value: Float) {
        bassPhaseDelayMs = value.coerceIn(0f, 20f)
        prefs.edit().putFloat(KEY_BASS_PHASE_DELAY, bassPhaseDelayMs).apply()
        publishBassOnlyConfig()
    }

    fun currentBassCutoff() = prefs.getFloat(KEY_BASS_CUTOFF, 80f)
    fun currentBassGainPercent() = prefs.getFloat(KEY_BASS_GAIN, 70f)
    fun isBassPolarityInverted() = prefs.getBoolean(KEY_BASS_POLARITY, false)
    fun currentBassPhaseDelay() = prefs.getFloat(KEY_BASS_PHASE_DELAY, 0f)

    private fun publishBassOnlyConfig() {
        dsp?.setBassOnlyControls(bassCutoffHz, bassGainPercent / 100f, bassPolarityInverted, bassPhaseDelayMs)
    }

    fun setEngineMode(headphones: Boolean) {
        headphonesMode = headphones
        prefs.edit().putBoolean("engine.headphones", headphones).apply()
        dsp?.setHeadphonesMode(headphones)
        publishConfig()
    }

    fun setIntensity(value: Float) {
        intensity = value.coerceIn(0f, 100f)
        prefs.edit().putFloat("engine.intensity", intensity).apply()
        publishConfig()
    }

    fun setSweepFrequency(value: Float) {
        sweepFrequency = value.coerceIn(27f, 63f)
        prefs.edit().putFloat("engine.sweep", sweepFrequency).apply()
        publishConfig()
    }

    fun setWidth(value: Float) {
        width = value.coerceIn(0f, 100f)
        prefs.edit().putFloat("engine.width", width).apply()
        publishConfig()
    }

    fun setBalance(value: Float) {
        balance = value.coerceIn(0f, 100f)
        prefs.edit().putFloat("engine.balance", balance).apply()
        publishConfig()
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 100f)
        prefs.edit().putFloat("engine.volume", volume).apply()
        publishConfig()
    }

    fun saveCustomPreset(name: String) {
        val clean = name.trim().take(32)
        if (clean.isNotEmpty()) prefs.edit()
            .putString("preset.custom", clean)
            .putFloat("preset.intensity", intensity)
            .putFloat("preset.sweep", sweepFrequency)
            .putFloat("preset.width", width)
            .putFloat("preset.balance", balance)
            .putFloat("preset.volume", volume)
            .putBoolean("preset.headphones", headphonesMode)
            .apply()
    }

    fun loadPreset(name: String): Float {
        if (prefs.getString("preset.custom", null) == name) {
            setEngineMode(prefs.getBoolean("preset.headphones", headphonesMode))
            setIntensity(prefs.getFloat("preset.intensity", intensity))
            setSweepFrequency(prefs.getFloat("preset.sweep", sweepFrequency))
            setWidth(prefs.getFloat("preset.width", width))
            setBalance(prefs.getFloat("preset.balance", balance))
            setVolume(prefs.getFloat("preset.volume", volume))
            return intensity
        }
        val value = when (name) { "Suave" -> 35f; "Fuerte" -> 90f; else -> prefs.getFloat("preset.intensity", intensity) }
        setIntensity(value)
        prefs.edit().putFloat("preset.intensity", value).apply()
        return value
    }

    private fun publishConfig() {
        dsp?.setParams(intensity, sweepFrequency, width, balance, volume)
        dsp?.setHeadphonesMode(headphonesMode)
    }

    private fun startCapture(startIntent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            setUnavailable("La captura de audio externo requiere Android 10 o posterior.")
            stopSelf()
            return
        }
        stopPipeline()
        _state.value = GlobalAudioState(GlobalAudioStatus.REQUESTING, "Preparando captura autorizada…")
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val code = startIntent.getIntExtra(EXTRA_RESULT_CODE, 0)
            @Suppress("DEPRECATION")
            val data = startIntent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            if (data == null) throw IllegalStateException("Android no entregó autorización de captura.")
            projection = manager.getMediaProjection(code, data)
            val callback = object : MediaProjection.Callback() {
                override fun onStop() { stopCapture() }
            }
            projectionCallback = callback
            projection?.registerCallback(callback, android.os.Handler(mainLooper))
            startPipeline(projection!!)
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to start playback capture", t)
            _state.value = GlobalAudioState(GlobalAudioStatus.ERROR, t.message ?: "No se pudo iniciar la captura.")
            stopPipeline()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startPipeline(mediaProjection: MediaProjection) {
        val sampleRate = 48_000
        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val minBytes = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) throw IllegalStateException("El dispositivo no admite una ruta de captura PCM estéreo a 48 kHz.")
        // Exclude our output track so the captured stream cannot feed back into
        // itself. Android still enforces each source app's usage/DRM capture policy.
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .excludeUid(android.os.Process.myUid())
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val newRecorder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes((minBytes * 2).coerceAtLeast(sampleRate / 20))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        val outFormat = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val outMin = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (outMin <= 0) { newRecorder.release(); throw IllegalStateException("El dispositivo no admite salida PCM estéreo a 48 kHz.") }
        val outputBuilder = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(outFormat)
            .setBufferSizeInBytes(outMin)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            outputBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val newOutput = outputBuilder.build()
        duckingEnabled = prefs.getBoolean(KEY_DUCKING_ENABLED, true)
        if (duckingEnabled) requestDuckingFocus()
        val newDsp = EpicenterDSPNative(sampleRate, 2)
        headphonesMode = prefs.getBoolean("engine.headphones", true)
        intensity = prefs.getFloat("engine.intensity", 65f)
        sweepFrequency = prefs.getFloat("engine.sweep", 45f).coerceIn(27f, 63f)
        width = prefs.getFloat("engine.width", 50f).coerceIn(0f, 100f)
        balance = prefs.getFloat("engine.balance", 100f).coerceIn(0f, 100f)
        volume = prefs.getFloat("engine.volume", 100f).coerceIn(0f, 100f)
        bassOnlyOutputEnabled = prefs.getBoolean(KEY_BASS_ONLY_ENABLED, false)
        bassCutoffHz = prefs.getFloat(KEY_BASS_CUTOFF, 80f).coerceIn(50f, 100f)
        bassGainPercent = prefs.getFloat(KEY_BASS_GAIN, 70f).coerceIn(0f, 150f)
        bassPolarityInverted = prefs.getBoolean(KEY_BASS_POLARITY, false)
        bassPhaseDelayMs = prefs.getFloat(KEY_BASS_PHASE_DELAY, 0f).coerceIn(0f, 20f)
        dsp = newDsp
        publishConfig()
        publishBassOnlyConfig()
        newDsp.setEnabled(true)
        recorder = newRecorder
        output = newOutput
        newRecorder.startRecording()
        newOutput.play()
        running.set(true)
        _state.value = GlobalAudioState(
            GlobalAudioStatus.SILENCE,
            "Captura autorizada; esperando audio compatible…",
            sampleRate,
            audioFocusGranted = audioFocusGranted
        )
        worker = thread(name = "EpicenterGlobalAudio", priority = Thread.MAX_PRIORITY) {
            // 256 stereo frames (5.33 ms at 48 kHz) reduce fixed block latency.
            // Blocking writes keep the queue bounded to this chunk plus AudioTrack's HAL buffer.
            val pcm = ShortArray(256 * 2)
            val floats = FloatArray(pcm.size)
            var smoothedPeak = 0f
            var bassGate = 0f
            var smoothedLatencyMs: Float? = null
            var smoothedLatencyJitterMs: Float? = null
            var lastTimestampCheckMs = 0L
            val recordTimestamp = AudioTimestamp()
            val trackTimestamp = AudioTimestamp()
            var lastUiUpdateMs = 0L
            var failure: String? = null
            try {
                while (running.get()) {
                    val count = newRecorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (!running.get()) break
                    if (count < 0) throw IllegalStateException("La ruta de captura se interrumpió (código $count).")
                    if (count == 0) continue
                    var peak = 0f
                    for (i in 0 until count) {
                        val sample = pcm[i] / 32768f
                        floats[i] = sample
                        peak = maxOf(peak, kotlin.math.abs(sample))
                    }
                    newDsp.processBassOnlyBuffer(floats, 2, count)
                    val gateTarget = if (bassOnlyOutputEnabled) 1f else 0f
                    val gateStep = 1f / (sampleRate * 0.005f) // 5 ms click-free master ramp
                    for (i in 0 until count) {
                        bassGate += (gateTarget - bassGate).coerceIn(-gateStep, gateStep)
                        floats[i] *= bassGate
                    }
                    for (i in 0 until count) pcm[i] = (floats[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    var written = 0
                    while (written < count && running.get()) {
                        val n = newOutput.write(pcm, written, count - written, AudioTrack.WRITE_BLOCKING)
                        if (n < 0) throw IllegalStateException("La salida de audio se interrumpió (código $n).")
                        if (n == 0) break
                        written += n
                    }
                    smoothedPeak = smoothedPeak * 0.82f + peak * 0.18f
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastUiUpdateMs >= 250L) {
                        if (now - lastTimestampCheckMs >= 10_000L) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                                newRecorder.getTimestamp(recordTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS &&
                                newOutput.getTimestamp(trackTimestamp)) {
                                val measured = ((recordTimestamp.nanoTime - trackTimestamp.nanoTime) / 1_000_000.0).toFloat()
                                if (measured in 0f..1000f) {
                                    val previous = smoothedLatencyMs
                                    smoothedLatencyMs = if (previous == null) measured else previous * 0.8f + measured * 0.2f
                                    val deviation = if (previous == null) 0f else kotlin.math.abs(measured - previous)
                                    smoothedLatencyJitterMs = (smoothedLatencyJitterMs ?: deviation) * 0.8f + deviation * 0.2f
                                }
                            }
                            lastTimestampCheckMs = now
                        }
                        val route = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) newOutput.routedDevice?.productName?.toString().orEmpty() else ""
                        _state.value = GlobalAudioState(
                            if (peak > 0.0005f) GlobalAudioStatus.CAPTURING else GlobalAudioStatus.SILENCE,
                            if (peak > 0.0005f) "Audio capturado; stem de subgraves generado" else "Esperando audio compatible…",
                            sampleRate,
                            smoothedPeak,
                            smoothedLatencyMs,
                            smoothedLatencyJitterMs,
                            route,
                            audioFocusGranted
                        )
                        lastUiUpdateMs = now
                    }
                }
            } catch (t: Throwable) {
                if (running.get()) {
                    val message = t.message ?: "Se interrumpió la ruta de audio."
                    failure = message
                    Log.e(TAG, "Audio processing loop failed", t)
                    _state.value = GlobalAudioState(GlobalAudioStatus.ERROR, message, sampleRate)
                }
            } finally {
                stopPipeline()
                if (failure != null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    fun stopCapture() {
        stopPipeline()
        _state.value = GlobalAudioState(GlobalAudioStatus.STOPPED, "Procesamiento detenido")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Synchronized private fun stopPipeline() {
        running.set(false)
        try { recorder?.stop() } catch (_: Throwable) { }
        try { output?.pause(); output?.flush(); output?.stop() } catch (_: Throwable) { }
        try { recorder?.release() } catch (_: Throwable) { }
        try { output?.release() } catch (_: Throwable) { }
        abandonDuckingFocus()
        try { dsp?.setEnabled(false); dsp?.release() } catch (_: Throwable) { }
        try { projection?.let { p -> projectionCallback?.let(p::unregisterCallback); p.stop() } } catch (_: Throwable) { }
        recorder = null; output = null; dsp = null; projection = null
        projectionCallback = null
    }

    /** Requests Android's cooperative MAY_DUCK behavior while the parallel bass stem runs. */
    private fun requestDuckingFocus() {
        try {
            val manager = getSystemService(AUDIO_SERVICE) as AudioManager
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(audioFocusListener)
                    .build()
                audioFocusRequest = request
                manager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    audioFocusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
            audioFocusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Log.i(TAG, "MAY_DUCK audio focus ${if (audioFocusGranted == true) "granted" else "not granted"}; original player controls its response")
        } catch (t: Throwable) {
            audioFocusGranted = false
            Log.w(TAG, "MAY_DUCK focus request failed; continuing without ducking", t)
        }
    }

    private fun abandonDuckingFocus() {
        try {
            val manager = getSystemService(AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let(manager::abandonAudioFocusRequest)
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(audioFocusListener)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to abandon ducking focus cleanly", t)
        } finally {
            audioFocusGranted = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "EpicenterDSP Global", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("EpicenterDSP Global")
        .setContentText("Captura de audio autorizada en curso")
        .setOngoing(true)
        .build()

    override fun onDestroy() { stopPipeline(); super.onDestroy() }

    companion object {
        const val ACTION_START = "com.epicenter.hifi.global.START"
        const val EXTRA_RESULT_CODE = "projection.resultCode"
        const val EXTRA_RESULT_DATA = "projection.resultData"
        private const val TAG = "EpicenterGlobal"
        private const val CHANNEL_ID = "epicenter_global_capture"
        private const val NOTIFICATION_ID = 77
        private const val KEY_BASS_ONLY_ENABLED = "bass.only.enabled"
        private const val KEY_DUCKING_ENABLED = "audio.ducking.enabled"
        private const val KEY_BASS_CUTOFF = "bass.only.cutoff"
        private const val KEY_BASS_GAIN = "bass.only.gain"
        private const val KEY_BASS_POLARITY = "bass.only.polarity"
        private const val KEY_BASS_PHASE_DELAY = "bass.only.phase_delay"
    }
}
