# AcousticGuard

AcousticGuard is an Android foreground-service engine for on-device acoustic
distress detection. It is deliberately fail-closed: it never uploads audio,
does not send an SOS from a single noisy frame, and does not claim that Android
can guarantee uninterrupted microphone access or network delivery.

## Architecture

```text
Visible MainActivity
  └─ sequential runtime permissions: RECORD_AUDIO → POST_NOTIFICATIONS → coarse location
      └─ user starts microphone foreground service (type=microphone; connectedDevice when mesh is enabled)
          ├─ AudioRecord reader thread
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

## Android compliance and limitations

- The app declares `FOREGROUND_SERVICE_MICROPHONE` and starts the service only
  from a visible user action after `RECORD_AUDIO` is granted.
- `POST_NOTIFICATIONS` is requested on Android 13+; a foreground service still
  needs a notification even if the user denies that permission, although Android
  may place it only in the task manager/system UI.
- Coarse location is requested up front but only the last known coarse location
  is included in a dispatch payload. No audio is included.
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
ten-second grace period, and multilingual label contract.

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

The Dockerfile installs only the pinned JDK, Android command-line tools, SDK
platform/build-tools, and Gradle into the image, then builds the debug APK:

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
   and coarse location in order. Keep the foreground notification visible.
6. Test with staged, consented recordings: ordinary conversation, music,
   laughter, dropped objects, screams, hyperventilation, impacts, and each
   supported language keyword. Confirm that one-off sounds never dispatch, that
   two qualifying windows show the visible cancel surface, and that quiet mode
   uses only the disclosed notification cancellation path.
7. Test airplane mode, Doze, revoked exact-alarm access, denied notifications,
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
