package com.acousticguard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.provider.Settings
import kotlin.math.log10
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AcousticCaptureService : Service() {
    private val running = AtomicBoolean(false)
    private val preAlertInFlight = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private lateinit var thresholdStore: AdaptiveThresholdStore
    private lateinit var gate: TierOneGate
    private lateinit var tierOneAnalyzer: TierOneAnalyzer
    private lateinit var audioBuffer: CircularPcmBuffer
    private lateinit var locationBurst: LocationBurstManager
    private lateinit var personalization: PersonalizationStore
    private lateinit var motionFusion: MotionFusion
    private lateinit var connectivityMonitor: SosConnectivityMonitor
    private var pipeline: DistressDetectionPipeline? = null
    private var captureThread: Thread? = null
    private var lastInferenceAt = 0L
    private var modelError: String? = null
    private var nativeCaptureActive = false
    private val nativeFrame = ShortArray(FRAME_SAMPLES)
    private var nativeFrameFill = 0
    @Volatile private var calibrationFramesRemaining = 0
    private var calibrationRms = 0f
    private var calibrationZcr = 0f
    private var calibrationHighFrequency = 0f
    private var calibrationPeak = 0f
    private val ipcMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what == WatchdogService.MESSAGE_PING) {
                runCatching { message.replyTo?.send(Message.obtain(null, WatchdogService.MESSAGE_PONG)) }
            } else {
                super.handleMessage(message)
            }
        }
    })

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        thresholdStore = AdaptiveThresholdStore(this)
        gate = TierOneGate(thresholdStore)
        tierOneAnalyzer = TierOneAnalyzer(gate)
        audioBuffer = CircularPcmBuffer(BUFFER_BYTES)
        locationBurst = LocationBurstManager(this)
        personalization = PersonalizationStore(this)
        motionFusion = MotionFusion(this)
        connectivityMonitor = SosConnectivityMonitor(this) {
            inferenceExecutor.execute { SosNetworkFlushBoundary(this).onConnectivityAvailable() }
        }
        try {
            pipeline = createPipeline(TfliteDistressModel(this))
        } catch (error: Exception) {
            if (BuildConfig.DEBUG) {
                // Development / DSP fallback: usable debug APK before the approved
                // model asset is provisioned. Release builds remain fail-closed.
                pipeline = createPipeline(DevDspDistressModel())
                modelError = null
            } else {
                modelError = error.message ?: error.javaClass.simpleName
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                WatchdogService.stop(this)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SIMULATE_ALERT -> {
                startAsForeground()
                preAlertInFlight.set(false)
                launchPreAlert(listOf("screaming_vocal_friction", "help (simulated)"), forceVisible = true)
                return START_NOT_STICKY
            }
            ACTION_DURESS_DISPATCH -> {
                startAsForeground()
                locationBurst.startBurst()
                dispatchDuressImmediately(intent.getStringArrayExtra(EXTRA_DURESS_LABELS).orEmpty().toList())
                return START_NOT_STICKY
            }
            ACTION_RECORD_SAFE_SAMPLE -> {
                startAsForeground()
                calibrationFramesRemaining = CALIBRATION_FRAME_COUNT
                calibrationRms = 0f
                calibrationZcr = 0f
                calibrationHighFrequency = 0f
                calibrationPeak = 0f
                sendCalibrationTelemetry()
                return START_STICKY
            }
            ACTION_CANCEL_QUIET_ALERT -> {
                intent.getStringExtra(EXTRA_QUIET_EVENT_ID)?.let { eventId ->
                    ExactAlarmScheduler.cancel(this, eventId)
                    AdaptiveThresholdStore(this).recordFalsePositive()
                    FalsePositiveLogger(this).recordFalsePositive(listOf("quiet_cancel", eventId))
                }
                preAlertInFlight.set(false)
                getSystemService(NotificationManager::class.java).cancel(ALERT_NOTIFICATION_ID)
                return START_STICKY
            }
            ACTION_PRE_ALERT_RESOLVED -> preAlertInFlight.set(false)
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (pipeline == null) {
            startAsForeground()
            reportCaptureFailure("Verified TFLite model is unavailable; microphone monitoring is disabled")
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (DispatchPreferences(this).communityMeshEnabled) {
            CommunityMeshScanner.start(this) { envelope, payload ->
                val expires = payload.timestampEpochMs + StoreAndForwardQueue.DEFAULT_EVENT_TTL_MS
                SosNetworkFlushBoundary(this).enqueueEncryptedPayload(
                    payload.eventId.toString(),
                    envelope,
                    expires
                )
                mainHandler.post { CommunityMeshNotifier.show(this, payload) }
            }
        }
        connectivityMonitor.start()
        motionFusion.start()
        if (running.compareAndSet(false, true)) startCapture()
        return START_STICKY
    }

    private fun startAsForeground() {
        val notification = monitoringNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                if (DispatchPreferences(this).communityMeshEnabled && meshForegroundTypeAllowed()) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                }
            startForeground(
                MONITORING_NOTIFICATION_ID,
                notification,
                serviceTypes
            )
        } else {
            startForeground(MONITORING_NOTIFICATION_ID, notification)
        }
    }

    private fun meshForegroundTypeAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun startCapture() {
        if (BuildConfig.NATIVE_AUDIO_ENABLED && NativeAudioBoundary.start(nativeSink)) {
            nativeCaptureActive = true
            return
        }
        captureThread = Thread({ captureLoop() }, "acousticguard-audio").also { it.start() }
    }

    private val nativeSink = object : NativeAudioBoundary.Sink {
        override fun onPcm16(samples: ShortArray) {
            if (!running.get()) return
            synchronized(nativeFrame) {
                var offset = 0
                while (offset < samples.size) {
                    val amount = minOf(FRAME_SAMPLES - nativeFrameFill, samples.size - offset)
                    samples.copyInto(nativeFrame, nativeFrameFill, offset, offset + amount)
                    nativeFrameFill += amount
                    offset += amount
                    if (nativeFrameFill == FRAME_SAMPLES) {
                        processFrame(nativeFrame.copyOf())
                        nativeFrameFill = 0
                    }
                }
            }
        }
    }

    private fun createPipeline(model: DistressModel): DistressDetectionPipeline =
        DistressDetectionPipeline(model) { scores, features ->
            if (features == null) {
                scores
            } else {
                val motionBoost = if (features.sensorFusionBoost >= 0.5f) {
                    val boost = features.sensorFusionBoost * SENSOR_CONFIDENCE_BOOST
                    scores.copy(
                        distressConfidence = (scores.distressConfidence + boost).coerceAtMost(1f),
                        combinedConfidence = (scores.combinedConfidence + boost).coerceAtMost(1f)
                    )
                } else {
                    scores
                }
                personalization.adjust(motionBoost, features)
            }
        }

    private fun captureLoop() {
        val recording = createAudioRecord() ?: run {
            reportCaptureFailure("AudioRecord could not be initialized")
            running.set(false)
            return
        }
        val sourceRate = recording.sampleRate
        val deviceChunk = ShortArray(maxOf(1024, recording.bufferSizeInFrames))
        val frame = ShortArray(FRAME_SAMPLES)
        var frameFill = 0

        try {
            recording.startRecording()
            while (running.get()) {
                val read = recording.read(deviceChunk, 0, deviceChunk.size, AudioRecord.READ_BLOCKING)
                if (read == AudioRecord.ERROR_DEAD_OBJECT || read == AudioRecord.ERROR_INVALID_OPERATION) break
                if (read <= 0) continue
                val normalizedRate = PcmResampler.to16Khz(deviceChunk.copyOf(read), sourceRate)
                var offset = 0
                while (offset < normalizedRate.size) {
                    val amount = minOf(FRAME_SAMPLES - frameFill, normalizedRate.size - offset)
                    normalizedRate.copyInto(frame, frameFill, offset, offset + amount)
                    frameFill += amount
                    offset += amount
                    if (frameFill == FRAME_SAMPLES) {
                        processFrame(frame)
                        frameFill = 0
                    }
                }
            }
        } catch (error: Exception) {
            reportCaptureFailure(error.message ?: error.javaClass.simpleName)
        } finally {
            try {
                if (recording.recordingState == AudioRecord.RECORDSTATE_RECORDING) recording.stop()
            } catch (_: IllegalStateException) {
                // The audio server may already have released the native recorder.
            }
            recording.release()
            captureThread = null
        }

        if (running.get()) {
            mainHandler.postDelayed({
                if (running.get() && captureThread == null) startCapture()
            }, RESTART_DELAY_MS)
        }
    }

    private fun processFrame(frame: ShortArray) {
        audioBuffer.writePcm16(frame)
        val features = motionFusion.enrich(tierOneAnalyzer.analyze(frame, CAPTURE_RATE))
        collectCalibrationSample(features)
        sendAudioTelemetry(features)
        val now = android.os.SystemClock.elapsedRealtime()
        val model = pipeline ?: return

        if (!features.candidate) {
            model.reset()
            lastInferenceAt = 0L
            return
        }
        if (audioBuffer.sizeBytes() < BUFFER_BYTES) return
        if (now - lastInferenceAt < EVALUATION_WINDOW_MS) return
        lastInferenceAt = now
        val snapshot = audioBuffer.snapshotPcm16()
        inferenceExecutor.execute {
            val result = model.evaluate(snapshot, features, nowElapsedMs = now)
            if (result.validated && preAlertInFlight.compareAndSet(false, true)) {
                mainHandler.post { launchPreAlert(result.scores?.matchedLabels.orEmpty()) }
            }
        }
    }

    private fun sendAudioTelemetry(features: TierOneFeatures) {
        val rmsDb = (20f * log10(features.audio.rms.coerceAtLeast(1.0e-5f)))
            .toInt()
            .coerceIn(-100, 0)
        sendBroadcast(Intent(ACTION_AUDIO_TELEMETRY).apply {
            setPackage(packageName)
            putExtra(EXTRA_RMS_DB, rmsDb)
            putExtra(EXTRA_CANDIDATE_FLAG, features.candidate)
            putExtra(EXTRA_FAST_TRACK, features.fastTrack)
            putExtra(EXTRA_CALIBRATION_REMAINING, calibrationFramesRemaining)
        })
    }

    private fun collectCalibrationSample(features: TierOneFeatures) {
        if (calibrationFramesRemaining <= 0) return
        calibrationRms += features.audio.rms
        calibrationZcr += features.audio.zcr
        calibrationHighFrequency += features.spectral.highFrequencyRatio ?: 0f
        calibrationPeak += features.audio.peak
        calibrationFramesRemaining--
        if (calibrationFramesRemaining == 0) {
            val count = CALIBRATION_FRAME_COUNT.toFloat()
            val sample = features.copy(
                audio = features.audio.copy(
                    rms = calibrationRms / count,
                    zcr = calibrationZcr / count,
                    peak = calibrationPeak / count
                ),
                spectral = features.spectral.copy(
                    highFrequencyRatio = calibrationHighFrequency / count
                )
            )
            val total = personalization.recordSafeSample(sample)
            sendCalibrationTelemetry(total)
        }
    }

    private fun sendCalibrationTelemetry(sampleCount: Int = personalization.sampleCount()) {
        sendBroadcast(Intent(ACTION_CALIBRATION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_CALIBRATION_COUNT, sampleCount)
            putExtra(EXTRA_CALIBRATION_REMAINING, calibrationFramesRemaining)
        })
    }

    private fun launchPreAlert(labels: List<String>, forceVisible: Boolean = false) {
        locationBurst.startBurst()
        if (DispatchPreferences(this).quietAlertMode) {
            launchQuietAlert(labels)
            return
        }
        val intent = Intent(this, GracePeriodActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(GracePeriodActivity.EXTRA_MATCHED_LABELS, labels.toTypedArray())
        }
        try {
            // Overlay access improves reliability for a user-visible countdown, but Android may
            // still deny background activity starts. The notification remains the fallback.
            if (forceVisible || Settings.canDrawOverlays(this)) startActivity(intent)
        } catch (_: SecurityException) {
            // Fall through to the notification action.
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(ALERT_NOTIFICATION_ID, alertNotification(intent))
    }

    private fun launchQuietAlert(labels: List<String>) {
        val eventId = UUID.randomUUID().toString()
        if (!ExactAlarmScheduler.schedule(this, eventId, labels.toTypedArray(), delayMs = 5_000L)) {
            preAlertInFlight.set(false)
            reportCaptureFailure("Quiet alert requires exact-alarm access; no dispatch was scheduled")
            return
        }
        FalsePositiveLogger(this).recordDispatchScheduled(eventId, labels)
        val cancelIntent = Intent(this, AcousticCaptureService::class.java).apply {
            action = ACTION_CANCEL_QUIET_ALERT
            putExtra(EXTRA_QUIET_EVENT_ID, eventId)
        }
        getSystemService(NotificationManager::class.java).notify(
            ALERT_NOTIFICATION_ID,
            quietAlertNotification(
                PendingIntent.getService(
                    this,
                    21,
                    cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        )
    }

    private fun dispatchDuressImmediately(labels: List<String>) {
        val eventId = UUID.randomUUID().toString()
        if (ExactAlarmScheduler.schedule(this, eventId, labels.toTypedArray(), delayMs = 0L)) {
            FalsePositiveLogger(this).recordDispatchScheduled(eventId, labels + "duress")
        } else {
            FalsePositiveLogger(this).recordDispatchUnavailable(eventId, "duress_exact_alarm_unavailable")
            reportCaptureFailure("Duress dispatch requires exact-alarm access and was not scheduled")
        }
        preAlertInFlight.set(false)
    }

    private fun createAudioRecord(): AudioRecord? {
        for (rate in SUPPORTED_CAPTURE_RATES) {
            val minBytes = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBytes <= 0) continue
            try {
                val record = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBytes, rate / 2))
                    .build()
                if (record.state == AudioRecord.STATE_INITIALIZED) return record
                record.release()
            } catch (_: IllegalArgumentException) {
                // Try the next device-supported sample rate.
            }
        }
        return null
    }

    private fun reportCaptureFailure(reason: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(MONITORING_NOTIFICATION_ID, monitoringNotification("Microphone unavailable: $reason"))
    }

    override fun onDestroy() {
        running.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        captureThread?.interrupt()
        inferenceExecutor.shutdownNow()
        motionFusion.stop()
        connectivityMonitor.stop()
        if (nativeCaptureActive) {
            NativeAudioBoundary.stop()
            nativeCaptureActive = false
        }
        locationBurst.stopBurst()
        CommunityMeshScanner.stop()
        CommunityMeshBroadcaster.stop()
        if (::tierOneAnalyzer.isInitialized) tierOneAnalyzer.reset()
        pipeline?.close()
        pipeline = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = ipcMessenger.binder

    private fun monitoringNotification(message: String = "Listening on-device; no audio is uploaded") =
        Notification.Builder(this, MONITORING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(modelError?.let { "Model unavailable; monitoring is paused" } ?: message)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Stop monitoring",
                    PendingIntent.getService(
                        this,
                        10,
                        Intent(this, AcousticCaptureService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                ).build()
            )
            .build()

    private fun alertNotification(activityIntent: Intent) =
        Notification.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Possible distress detected")
            .setContentText("Tap to review or cancel the 10-second SOS countdown")
            .setPriority(Notification.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    20,
                    activityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun quietAlertNotification(cancelPendingIntent: PendingIntent) =
        Notification.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("AcousticGuard alert pending")
            .setContentText("Quiet mode is active; tap Cancel SOS if this is a false alarm")
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Cancel SOS", cancelPendingIntent).build())
            .build()

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                MONITORING_CHANNEL_ID,
                getString(R.string.monitoring_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                getString(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    companion object {
        const val ACTION_STOP = "com.acousticguard.action.STOP"
        const val ACTION_AUDIO_TELEMETRY = "com.acousticguard.action.AUDIO_TELEMETRY"
        const val EXTRA_RMS_DB = "rms_db"
        const val EXTRA_CANDIDATE_FLAG = "candidate_flag"
        const val EXTRA_FAST_TRACK = "fast_track"
        const val ACTION_SIMULATE_ALERT = "com.acousticguard.action.SIMULATE_ALERT"
        const val ACTION_DURESS_DISPATCH = "com.acousticguard.action.DURESS_DISPATCH"
        const val EXTRA_DURESS_LABELS = "duress_labels"
        const val ACTION_RECORD_SAFE_SAMPLE = "com.acousticguard.action.RECORD_SAFE_SAMPLE"
        const val ACTION_CALIBRATION_STATUS = "com.acousticguard.action.CALIBRATION_STATUS"
        const val EXTRA_CALIBRATION_COUNT = "calibration_count"
        const val EXTRA_CALIBRATION_REMAINING = "calibration_remaining"
        const val ACTION_CANCEL_QUIET_ALERT = "com.acousticguard.action.CANCEL_QUIET_ALERT"
        const val ACTION_PRE_ALERT_RESOLVED = "com.acousticguard.action.PRE_ALERT_RESOLVED"
        const val EXTRA_QUIET_EVENT_ID = "quiet_event_id"
        private const val MONITORING_CHANNEL_ID = "acoustic_monitoring"
        private const val ALERT_CHANNEL_ID = "acoustic_alerts"
        private const val MONITORING_NOTIFICATION_ID = 100
        private const val ALERT_NOTIFICATION_ID = 101
        private const val CAPTURE_RATE = 16_000
        private const val BUFFER_BYTES = 112_000 // 16,000 mono PCM16 samples × 3.5 seconds
        private const val FRAME_SAMPLES = CAPTURE_RATE / 2
        private const val EVALUATION_WINDOW_MS = 1_500L
        private const val RESTART_DELAY_MS = 2_000L
        private const val CALIBRATION_FRAME_COUNT = 6
        private const val SENSOR_CONFIDENCE_BOOST = 0.15f
        private val SUPPORTED_CAPTURE_RATES = intArrayOf(16_000, 48_000, 44_100)
    }
}
