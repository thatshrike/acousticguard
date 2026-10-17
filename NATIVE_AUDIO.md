# Optional native AAudio boundary

AcousticGuard includes a small, platform-only JNI boundary for checking whether
the device accepts an AAudio input stream configured for low latency. It is a
capability scaffold, not a claim that every device provides exclusive or
uninterrupted AAudio capture.

## Default behavior

The native target is **disabled by default**. A normal Gradle build does not
configure `externalNativeBuild`, does not require an installed NDK/CMake pair,
and packages no native audio library. The existing
`AcousticCaptureService` continues to use its `AudioRecord` reader and is not
changed by this scaffold.

`NativeAudioBoundary.status()` reports the opt-in state and
`NativeAudioBoundary.recommendedBackend()` returns `Backend.AUDIO_RECORD` for
the safe fallback. This includes all of these cases:

- native audio is not enabled;
- Android is older than API 26;
- the JNI library was not packaged or could not be loaded;
- microphone permission/privacy controls reject the probe; or
- the device rejects the requested AAudio format, route, sharing mode, or
  performance mode.

The boundary also exposes an opt-in callback stream. When the service is built
with native audio enabled and AAudio accepts the requested configuration,
`AcousticCaptureService` receives PCM16 callbacks and feeds the same 500 ms DSP
pipeline. If opening or starting that stream fails, the service falls back to
AudioRecord. Native callbacks do not bypass Android microphone disclosure and
must still tolerate route changes, callback errors, and process death.

## Opt-in native build

Install an Android NDK and CMake 3.22.1 through the Android SDK, then build with
the property below:

```bash
gradle -Pacousticguard.nativeAudio=true :app:assembleDebug
```

The property turns on the module's `externalNativeBuild` block and packages
`acousticguard_audio`. The C++ target includes the platform
`<aaudio/AAudio.h>` header and links only `aaudio` and Android's `log` library;
it adds no Oboe or other external audio dependency.

The native implementation requests 16 kHz mono PCM16 input, a callback,
exclusive sharing, and `AAUDIO_PERFORMANCE_MODE_LOW_LATENCY` as a best-effort
probe. A successful result additionally verifies those negotiated properties at
that moment. AAudio may negotiate or reject those settings depending on the
device, route, audio server, permission state, and OEM behavior. The result is
therefore not a guarantee of exclusive access, fixed latency, uninterrupted
service lifetime, or successful future capture.

## Integration boundary

The service calls the native start boundary only after `RECORD_AUDIO` has been
granted and after it is foreground. If `nativeStart` returns false, it retains
the existing AudioRecord path. AAudio is therefore an optional low-latency
backend, not a replacement that can be assumed on every device.
