# AcousticGuard

AcousticGuard is an Android foreground-service engine for on-device acoustic
distress detection. It is deliberately fail-closed: it never uploads audio,
does not send an SOS from a single noisy frame, and does not claim that Android
can guarantee uninterrupted microphone access or network delivery.

## Architecture

```text
Visible MainActivity / UI process
  ├─ best-effort WatchdogService in the default process
  │   └─ Messenger heartbeat + restart of the isolated :remote service
  └─ sequential runtime permissions: RECORD_AUDIO → notifications → coarse/fine location
      └─ AcousticCaptureService in :remote (microphone FGS; connectedDevice when mesh is enabled)
           ├─ AudioRecord reader thread (optional AAudio capability boundary)
            │   ├─ device-rate PCM16 → 16 kHz mono resampler
            │   ├─ 500 ms Tier 1 RMS + ZCR + high-frequency spectral gate
            │   ├─ derived envelope history for respiratory periodicity
            │   ├─ impact attack/duration/crest-factor detector
          │   └─ bounded 3.5 s in-memory CircularPcmBuffer
          └─ serialized Tier 2 TFLite inference (only after Tier 1 anomaly)
              ├─ calibrated multilingual distress/event scores
              ├─ ≥ 0.82 confidence
              └─ two consecutive 1.5 s windows
                  └─ 10 s GracePeriodActivity + vibration + cancel/PIN
                      └─ optional quiet alert: no app screen, 5 s exact-alarm window, notification cancel
                      └─ setExactAndAllowWhileIdle()
                           └─ SosDispatchReceiver: AES-GCM envelope + optional BLE/HTTPS/SMS
                               └─ bounded encrypted store-and-forward outbox
```

### Why the model is a contract, not a guessed YAMNet label map

YAMNet is useful for acoustic events such as screams and crying, but it is not a
reliable multilingual speech keyword recognizer. AcousticGuard therefore expects
`app/src/main/assets/distress_multilingual.tflite` to be a calibrated model with
the labels in `distress_multilingual_labels.txt`. The adapter recognizes English,
Hindi Devanagari, and transliterated Hindi labels such as `help`, `madad`, `मदद`,
`bachao`, `बचाओ`, `sahayata`, and `सहायता`, in addition to non-verbal distress
events. Add Marathi, Bengali, Tamil, Telugu, Kannada, Malayalam, Gujarati, or
other language labels only after collecting consented, representative keyword
data and measuring false accepts/false rejects.

The model may use FLOAT32, INT8, or UINT8 input/output tensors. Quantized tensors
are dequantized using the runtime tensor scale and zero-point; scores must still
be finite calibrated probabilities. The binary is intentionally excluded from
Git because a safety model must be trained, evaluated, signed, and
provenance-reviewed. Put the approved binary at the asset path before building a
release APK.

Debug builds include `DevDspDistressModel`, a deterministic end-to-end fallback
used only when the model asset is absent. It treats a Tier 1 candidate as a
development distress event so the foreground service, grace period, exact alarm,
encrypted payload, and optional mesh can be exercised immediately. Release
builds do not enable this fallback and remain fail-closed until the approved
model is present. The debug fallback intentionally returns a synthetic strong
distress result for the rapid one-window path; it is not a speech recognizer and
must not be used to evaluate false-positive performance.

## Tier 1 physiological DSP

`TierOneAnalyzer.kt` adds three pure-Kotlin detectors without persisting raw
audio:

- `SpectralAnalyzer`: 512-point Hann-window FFT, 3.5–8 kHz energy ratio, and
  silence/DC rejection for high-frequency vocal friction.
- `RespiratoryPeriodicityDetector`: retains only 10 Hz scalar energy-envelope
  samples for up to 16 seconds, then performs normalized autocorrelation over
  2–8 second periods.
- `ImpactTransientDetector`: 10 ms/5 ms short-window RMS, attack time, duration,
  crest factor, clipping, and a frame-boundary tail buffer.

These detectors provide candidate evidence only. Tier 2 still requires calibrated
model confidence of at least `0.82` in two consecutive windows. Co-occurring
spectral and respiratory evidence uses a 750 ms inference cadence so two windows
can complete in approximately 1.5 seconds; ordinary candidates retain the
1.5-second cadence.

### Rapid escalation for a remote single shout

Tier 1 telemetry is not itself an alert. The service now also runs an early model
probe on a candidate after the first 500 ms frame, even though the 3.5-second
pre-roll buffer is not full. Short input is zero-padded by the model adapter.
The early probe is allowed to open the normal protection flow after one window
only when one of these calibrated conditions is met:

- an exact multilingual help label (`help`, `madad`, `मदद`, `bachao`, `बचाओ`,
  `sahayata`, `सहायता`, etc.) reaches at least `0.90` keyword confidence and
  the adjusted score remains at least `0.82`;
- a voice-like candidate has an exact strong distress label at least `0.95`;
- a valid impact is concurrent with both a strong accelerometer and gyroscope
  event, plus an exact strong distress label at least `0.90`.

The regular path remains conservative: full 3.5-second context, calibrated
confidence, and two consecutive qualifying windows. A single Tier 1 flag,
accelerometer event, substring label such as `helpful`, or weak model result
cannot trigger SOS. The live UI now reports whether the service is running a
rapid probe, full-context inference, or validated stage, which makes field tests
diagnosable instead of showing only `Acoustic Anomaly Flagged!`.

## Optional native low-latency boundary

The repository also contains an opt-in, platform-only AAudio backend. It is
disabled in the default Gradle build, does not add Oboe, and falls back to the
existing `AudioRecord` service path whenever the native stream is unavailable.
See
[`NATIVE_AUDIO.md`](NATIVE_AUDIO.md) for the gated NDK/CMake build command,
`NativeAudioBoundary` status/fallback API, and the device-specific limitations
of AAudio. A successful probe is not a guarantee of exclusive AAudio access or
uninterrupted low latency.

## Quiet alerts, encryption, and Android disclosure

The UI exposes **Quiet alert mode**, which avoids launching the bright countdown
activity and schedules a five-second exact-alarm dispatch window. It is not a
hidden or zero-permission mode: Android's microphone privacy indicator,
foreground-service/task-manager disclosure, and notification requirements remain
in force. The alert notification provides a direct cancel action. Android does
not allow an application to suppress those system privacy signals.

The optional community mesh requests Bluetooth runtime permissions only when
enabled. Continuous BLE scanning/advertising is device- and OEM-dependent;
Bluetooth-off, unsupported-advertiser, permission, and scan failures are treated
as non-operational rather than falling back to plaintext packets.

`SosPayloadCrypto.kt` produces a compact AES-GCM envelope with a fresh 96-bit
nonce, authenticated protocol metadata, a monotonic counter for replay
rejection, event UUID, timestamp, coarse coordinates, and a distress signature
bitmask. Android Keystore keys are device-local. A real community deployment
must provision a shared receiver-domain key through an authenticated enrollment
protocol; this repository deliberately does not embed a production key in the
APK. HTTPS receives the encrypted envelope, not raw labels or coordinates.

## Reliability and defensive controls

- `WatchdogService` remains in the default process while the microphone service
  is declared in `:remote`. It binds with an IPC `Messenger`, sends heartbeats,
  and attempts a restart after disconnection or heartbeat timeout. This is
  best-effort only: Android can kill both processes, deny a background restart,
  or stop the app after force-stop.
- PINs are stored as salted PBKDF2-HMAC-SHA256 verifiers, never as plaintext.
  The reverse of the configured PIN is the default duress PIN. Entering it in
  the countdown produces the same apparent abort outcome but schedules an
  immediate encrypted dispatch without a visible second confirmation. A
  platform biometric cancellation button is available on API 28+.
- `LocationBurstManager` starts a best-effort 30-second Google Fused Location
  high-accuracy burst, with platform GPS/network updates as a fallback, when an
  alert is raised. It persists only the latest fix metadata at most every five
  minutes. The SOS envelope includes the capture timestamp, allowing the
  receiver to calculate how old an indoor fallback position is.
- `AdaptiveNoiseProfile` learns a slow RMS/high-frequency environment floor only
  from non-candidate frames. Loud steady environments therefore move the floor
  gradually instead of producing a candidate for every frame.
- The UI can collect three consented safe profile samples. Only scalar RMS, ZCR,
  peak, and spectral features are kept. After three samples, a bounded local
  confidence bias reduces matches close to the user's safe profile; it never
  edits TFLite weights and never stores calibration audio.
- `MotionFusion` combines accelerometer and gyroscope spikes with impact evidence.
  A strong concurrent IMU event adds a bounded confidence modifier; IMU data can
  never create a candidate without acoustic evidence.
- `SosPayloadCrypto` rotates versioned AES-GCM keys and attempts Android StrongBox
  hardware isolation where available, falling back to the Android Keystore when
  StrongBox is absent. Existing key versions remain available for local queued
  envelopes until an explicit key-retention policy removes them.

## Store-and-forward network delivery

When an HTTPS endpoint is configured, `SosDispatchReceiver` puts the AES-GCM
envelope into a bounded local outbox before attempting delivery. The outbox
stores only the event ID, encrypted envelope bytes, creation/expiry timestamps,
and delivery-attempt state; it never stores PCM, raw audio, or plaintext labels.
It is limited to 64 events, 128 bytes per envelope, and 8 KiB of total envelope
bytes. Duplicate event IDs are ignored, expired events are removed, and a full
queue refuses a new entry instead of silently evicting an older unexpired SOS.
Failed attempts return to a retryable state with bounded exponential backoff;
an abandoned in-flight attempt is reclaimable after its lease.

`StoreAndForwardQueueTest` is a pure JVM test suite covering the entry and byte
bounds, event-ID deduplication, expiry, retry/attempt persistence, abandoned
claims, and the connectivity flush result boundary.

`SosQueueFlushBoundary.onConnectivityAvailable()` is the explicit transport
boundary for a future WorkManager or foreground-service integration. The
current receiver uses the same boundary for one bounded immediate attempt, and
the stored envelope remains available for a later connectivity callback. The
boundary does not create a scheduler or claim that Android will run it while the
app is stopped. BLE mesh remains an immediate optional transport; queued HTTPS
delivery does not add another radio protocol or transmit audio.

### Audible and near-ultrasonic SOS channel

AcousticGuard does not enable an audible or near-ultrasonic SOS transmission
channel by default. An emitted tone could be heard or felt by nearby people,
could disturb children, animals, or hearing-sensitive users, could be mistaken
for an emergency signal, and would add a privacy-sensitive outbound channel
without guaranteeing authentication, reception, or emergency-service delivery.
The detector also must not turn a user's microphone permission into permission
to broadcast. Any future tone channel therefore requires a separate, visible
opt-in with informed consent, a documented frequency/power/duration policy,
local compatibility and hearing-safety review, and an explicit cancellation
path. This slice intentionally implements no ultrasonic audio transmission.

## Android compliance and limitations

- The app declares `FOREGROUND_SERVICE_MICROPHONE` and starts the service only
  from a visible user action after `RECORD_AUDIO` is granted.
- `POST_NOTIFICATIONS` is requested on Android 13+; a foreground service still
  needs a notification even if the user denies that permission, although Android
  may place it only in the task manager/system UI.
- Coarse and fine location are requested explicitly for the optional burst. Only
  the last cached coordinates, accuracy, and capture timestamp are included in a
  dispatch payload. No audio is included.
- Optional BLE mesh additionally requires Android 12+ `BLUETOOTH_SCAN`,
  `BLUETOOTH_CONNECT`, and `BLUETOOTH_ADVERTISE` permissions (or fine location
  for legacy scanning). Legacy advertisers reject envelopes that cannot fit
  without truncation; there is no plaintext or partial-payload fallback.
- Battery optimization exemption is user-controlled through
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. It does not defeat force-stop,
  OEM task killers, thermal shutdown, microphone privacy controls, audio-server
  failures, or Android's while-in-use rules.
- On Android 12+, exact alarm access is special access. If granted, the grace
  activity uses `AlarmManager.setExactAndAllowWhileIdle()` to wake the dispatch
  receiver during Doze. This reduces delay but cannot guarantee SMS/network
  delivery; radio availability, carrier state, captive portals, and OEM policy
  still apply.
- Background activity launch is restricted. The service launches the countdown
  only when overlay access is available and always posts a high-priority
  notification with a direct activity `PendingIntent` fallback. The user should
  grant overlay access for the most reliable countdown UX.
- Android may stop or silence a foreground microphone service. The notification
  and source code intentionally expose failure instead of silently claiming
  that monitoring continues.

## Build and test

### Offline structural test

The repository's single offline check does not download dependencies:

```bash
make test
```

It verifies the manifest permissions, microphone FGS declaration, 3.5-second
buffer, 500 ms gate path, 0.82/two-window validation policy, exact alarm API,
ten-second grace period, multilingual label contract, and the default-off
audible/near-ultrasonic channel policy.

### Local Android build

Use JDK 17, Android SDK platform 35, build-tools 35.0.0, and Gradle 8.10.2.
The versions are pinned in `gradle/libs.versions.toml` and
`gradle/wrapper/gradle-wrapper.properties`.

```bash
gradle --offline :app:testDebugUnitTest
gradle :app:assembleDebug
```

The first command requires the Gradle and Android artifacts to already exist in
the local cache. If the cache is empty, run the second command once while online
to populate it; this project never installs dependencies globally.

### Reproducible Docker build

The Dockerfile installs the pinned JDK, Android command-line tools, SDK
platform/build-tools, NDK `27.2.12479018`, CMake `3.22.1`, and Gradle into the
image, then builds the debug APK:

```bash
docker build -t acousticguard:dev .
docker run --rm acousticguard:dev
```

The image build requires network access for the toolchain download; the
application's `make test` check remains offline and dependency-free.

## Step-by-step device setup

1. Train/evaluate the approved multilingual model and copy it to
   `app/src/main/assets/distress_multilingual.tflite`.
2. Build and install the debug APK on a physical test device. An emulator is
   not suitable for microphone, Doze, carrier, or OEM-policy testing.
3. Open the app, save a 4–8 digit quick cancel PIN, and optionally configure an
   SMS number and an HTTPS endpoint. Only use an endpoint you control and serve
   over HTTPS.
4. Save settings. Choose **Quiet alert mode** only if a no-app-screen alert is
   appropriate, and choose the optional community mesh only after reviewing its
   Bluetooth disclosure. Tap **Allow countdown overlay** for the normal visible
   flow, **Allow exact alarm dispatch**, and, if the device's power policy is
   aggressive, **Allow battery optimization exemption**.
5. Tap **Start on-device monitoring**. Approve `RECORD_AUDIO`, notifications,
   coarse location, and fine location in order. Keep the foreground notification
   visible.
6. Test with staged, consented recordings: ordinary conversation, music,
   laughter, dropped objects, screams, hyperventilation, impacts, and each
   supported language keyword. Confirm that one-off sounds never dispatch, that
   two qualifying windows show the visible cancel surface, and that quiet mode
   uses only the disclosed notification cancellation path.
7. Use **TEST SOS PIPELINE (SIMULATE DISTRESS)** to exercise the alert path
   without creating an acoustic event. Quiet mode uses its five-second
   notification cancellation path; normal mode opens the ten-second activity.
8. Use **Record safe profile sample** three times with consented safe audio if
   personal calibration is desired. Wait for each sample to complete before
   starting the next one.
9. Test airplane mode, Doze, revoked exact-alarm access, denied notifications,
   microphone privacy toggle, Bluetooth route changes, call interruption, OEM
   battery controls, reboot, and force-stop. Record every degraded state.

## Release safety checklist

- Calibrate 0.82 against a locked validation set; do not treat it as a universal
  probability threshold without calibration.
- Evaluate false accepts per hour in the user's expected environment. Heavy
  metal, action films, laughter, alarms, and dropped objects must be in the set.
- Add model version and label-map version to dispatch telemetry (not audio).
- Obtain explicit consent for SMS/HTTPS contacts and comply with local telecom,
  Google Play, and privacy requirements. `SEND_SMS` and exact-alarm exemptions
  are policy-sensitive capabilities.
- Provide visible stop controls and do not market the app as a substitute for
  emergency services or a guaranteed safety system.
