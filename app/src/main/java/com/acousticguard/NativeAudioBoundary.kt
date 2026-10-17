package com.acousticguard

import android.os.Build

/**
 * Opt-in status boundary for the native AAudio scaffold.
 *
 * The existing capture service intentionally remains on AudioRecord. A caller
 * may use [status] and [recommendedBackend] before a future native capture
 * integration, but this class does not silently replace the service's audio
 * path. AAudio is best effort: a device can expose API 26+ and still reject
 * the requested low-latency input stream.
 */
object NativeAudioBoundary {
    interface Sink {
        fun onPcm16(samples: ShortArray)
    }
    enum class Backend {
        AAUDIO,
        AUDIO_RECORD,
    }

    enum class State {
        DISABLED,
        API_UNAVAILABLE,
        LIBRARY_UNAVAILABLE,
        AAUDIO_UNAVAILABLE,
        READY,
    }

    data class Status(
        val state: State,
        val recommendedBackend: Backend,
        val nativeLibraryLoaded: Boolean,
        val detail: String,
    ) {
        val canUseAaudio: Boolean
            get() = state == State.READY
    }

    private const val LIBRARY_NAME = "acousticguard_audio"

    private const val PROBE_READY = 0
    private const val PROBE_UNSUPPORTED = 1
    private const val PROBE_UNAVAILABLE = 2

    private val nativeLibraryLoaded = if (
        BuildConfig.NATIVE_AUDIO_ENABLED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    ) {
        runCatching { System.loadLibrary(LIBRARY_NAME) }.isSuccess
    } else {
        false
    }

    /**
     * Returns the current native capability without changing the service's
     * backend. When enabled, the JNI probe briefly attempts the requested
     * AAudio input configuration and closes it without retaining audio.
     */
    @JvmStatic
    fun status(): Status {
        if (!BuildConfig.NATIVE_AUDIO_ENABLED) {
            return fallbackStatus(
                State.DISABLED,
                "Native AAudio is disabled; the existing AudioRecord path remains active"
            )
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return fallbackStatus(
                State.API_UNAVAILABLE,
                "AAudio requires Android API 26 or newer"
            )
        }
        if (!nativeLibraryLoaded) {
            return fallbackStatus(
                State.LIBRARY_UNAVAILABLE,
                "Native audio library is not packaged; use AudioRecord"
            )
        }

        return when (runCatching { nativeProbe() }.getOrDefault(PROBE_UNAVAILABLE)) {
            PROBE_READY -> Status(
                state = State.READY,
                recommendedBackend = Backend.AAUDIO,
                nativeLibraryLoaded = true,
                detail = "AAudio accepted the requested low-latency input configuration at probe time"
            )
            PROBE_UNSUPPORTED -> fallbackStatus(
                State.AAUDIO_UNAVAILABLE,
                "The device rejected the requested AAudio format or performance mode"
            )
            else -> fallbackStatus(
                State.AAUDIO_UNAVAILABLE,
                "AAudio could not open the requested input stream"
            )
        }
    }

    /**
     * Selects the safe backend for an opt-in integration. AudioRecord is the
     * explicit fallback for disabled builds, old Android versions, missing
     * native libraries, permissions, and device-specific AAudio failures.
     */
    @JvmStatic
    fun recommendedBackend(): Backend = status().recommendedBackend

    /** Starts the opt-in native stream. Returns false for every unsupported case. */
    @JvmStatic
    fun start(sink: Sink): Boolean {
        if (!BuildConfig.NATIVE_AUDIO_ENABLED || !nativeLibraryLoaded || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false
        }
        return runCatching { nativeStart(sink) }.getOrDefault(false)
    }

    @JvmStatic
    fun stop() {
        if (nativeLibraryLoaded) runCatching { nativeStop() }
    }

    private fun fallbackStatus(state: State, detail: String) = Status(
        state = state,
        recommendedBackend = Backend.AUDIO_RECORD,
        nativeLibraryLoaded = nativeLibraryLoaded,
        detail = detail
    )

    /** JNI returns one of the PROBE_* constants above. */
    private external fun nativeProbe(): Int

    private external fun nativeStart(sink: Sink): Boolean

    private external fun nativeStop()
}
