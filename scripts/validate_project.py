#!/usr/bin/env python3
"""Offline structural checks for the safety-critical Android core."""

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def require(text: str, pattern: str, label: str) -> None:
    if not re.search(pattern, text, flags=re.MULTILINE):
        raise AssertionError(f"missing {label}: {pattern}")


def main() -> int:
    manifest = read("app/src/main/AndroidManifest.xml")
    service = read("app/src/main/java/com/acousticguard/AcousticCaptureService.kt")
    pipeline = read("app/src/main/java/com/acousticguard/DistressDetectionPipeline.kt")
    scheduler = read("app/src/main/java/com/acousticguard/ExactAlarmScheduler.kt")
    grace = read("app/src/main/java/com/acousticguard/GracePeriodActivity.kt")
    dsp = read("app/src/main/java/com/acousticguard/TierOneAnalyzer.kt")
    crypto = read("app/src/main/java/com/acousticguard/SosPayloadCrypto.kt")
    mesh = read("app/src/main/java/com/acousticguard/CommunityMesh.kt")
    fallback = read("app/src/main/java/com/acousticguard/DevDspDistressModel.kt")
    activity = read("app/src/main/java/com/acousticguard/MainActivity.kt")
    app_gradle = read("app/build.gradle.kts")
    proguard = read("app/proguard-rules.pro")
    native_boundary = read("app/src/main/java/com/acousticguard/NativeAudioBoundary.kt")
    native_cmake = read("app/src/main/cpp/CMakeLists.txt")
    native_cpp = read("app/src/main/cpp/acousticguard_audio.cpp")
    native_doc = read("NATIVE_AUDIO.md")
    queue = read("app/src/main/java/com/acousticguard/StoreAndForwardQueue.kt")
    flush_boundary = read("app/src/main/java/com/acousticguard/SosQueueFlushBoundary.kt")
    network_boundary = read("app/src/main/java/com/acousticguard/SosNetworkFlushBoundary.kt")
    receiver = read("app/src/main/java/com/acousticguard/SosDispatchReceiver.kt")
    watchdog = read("app/src/main/java/com/acousticguard/WatchdogService.kt")
    location = read("app/src/main/java/com/acousticguard/LocationBurstManager.kt")
    noise = read("app/src/main/java/com/acousticguard/AdaptiveNoiseProfile.kt")
    personalization = read("app/src/main/java/com/acousticguard/PersonalizationStore.kt")
    motion = read("app/src/main/java/com/acousticguard/MotionFusion.kt")
    rapid = read("app/src/main/java/com/acousticguard/RapidEscalationPolicy.kt")
    taxonomy = read("app/src/main/java/com/acousticguard/DistressEventTaxonomy.kt")
    reasoning = read("app/src/main/java/com/acousticguard/DistressReasoningEngine.kt")
    owner_voice = read("app/src/main/java/com/acousticguard/OwnerVoiceProfileStore.kt")
    voice_model = read("app/src/main/java/com/acousticguard/VoiceEmbeddingModel.kt")
    encrypted_owner_voice = read("app/src/main/java/com/acousticguard/EncryptedOwnerVoiceProfileStore.kt")
    web = read("app/src/main/assets/web/index.html")
    pin = read("app/src/main/java/com/acousticguard/DispatchPreferences.kt")
    crypto = read("app/src/main/java/com/acousticguard/SosPayloadCrypto.kt")
    readme = read("README.md")
    labels = [line for line in read("app/src/main/assets/distress_multilingual_labels.txt").splitlines() if line]

    for permission in (
        "android.permission.RECORD_AUDIO",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_MICROPHONE",
        "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        "android.permission.SCHEDULE_EXACT_ALARM",
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_ADVERTISE",
        "android.permission.USE_BIOMETRIC",
        "android.permission.ACCESS_FINE_LOCATION",
    ):
        require(manifest, re.escape(permission), permission)
    require(manifest, r'android:foregroundServiceType="microphone(?:\|connectedDevice)?"', "microphone FGS type")
    require(service, r"AudioRecord\.READ_BLOCKING", "blocking AudioRecord loop")
    require(service, r"CircularPcmBuffer\(BUFFER_BYTES\)", "bounded PCM buffer")
    require(service, r"CAPTURE_RATE = 16_000", "16 kHz normalized capture")
    require(pipeline, r"0\.82f", "confidence threshold")
    require(pipeline, r"1_500L", "1.5 second validation window")
    require(pipeline, r"requiredWindows: Int = 2", "two-window confirmation")
    require(scheduler, r"setExactAndAllowWhileIdle", "Doze-aware exact alarm")
    require(grace, r"COUNTDOWN_MS = 10_000L", "ten-second grace period")
    require(grace, r"recordFalsePositive", "false-positive calibration")
    require(dsp, r"class SpectralAnalyzer", "spectral Tier 1 detector")
    require(dsp, r"class RespiratoryPeriodicityDetector", "respiratory Tier 1 detector")
    require(dsp, r"class ImpactTransientDetector", "impact Tier 1 detector")
    require(crypto, r"AES/GCM/NoPadding", "authenticated SOS encryption")
    require(mesh, r"BluetoothLeAdvertiser", "BLE advertiser")
    require(mesh, r"BluetoothLeScanner", "BLE scanner")
    require(service, r"quietAlertMode", "quiet alert preference")
    require(fallback, r"combinedConfidence = 0\.96f", "development DSP fallback")
    require(service, r"BuildConfig\.DEBUG", "debug-only fallback guard")
    require(service, r"ACTION_AUDIO_TELEMETRY", "audio telemetry broadcast")
    require(service, r"ACTION_SIMULATE_ALERT", "simulation action")
    require(service, r"ACTION_START_OWNER_VOICE_ENROLLMENT", "owner voice enrollment action")
    require(service, r"EncryptedOwnerVoiceProfileStore", "encrypted owner voice profile")
    require(service, r"matchOwnerVoice", "owner voice inference hook")
    require(activity, r"setContentView\(scroll\)", "attached main activity layout")
    require(activity, r"TEST SOS PIPELINE \(SIMULATE DISTRESS\)", "simulation button")
    require(app_gradle, r"acousticguard\.nativeAudio", "native audio opt-in property")
    require(app_gradle, r"externalNativeBuild", "gated native build configuration")
    require(app_gradle, r'buildConfigField\("boolean", "NATIVE_AUDIO_ENABLED"', "native build flag")
    require(native_boundary, r"object NativeAudioBoundary", "Kotlin native audio boundary")
    require(native_boundary, r"System\.loadLibrary\(LIBRARY_NAME\)", "JNI library loading")
    require(native_boundary, r"BuildConfig\.NATIVE_AUDIO_ENABLED", "native opt-in guard")
    require(native_boundary, r"Backend\.AUDIO_RECORD", "AudioRecord fallback backend")
    require(proguard, r"com\.acousticguard\.NativeAudioBoundary", "JNI boundary shrinker keep rule")
    require(native_cmake, r"add_library\(acousticguard_audio SHARED", "native audio library")
    require(native_cmake, r"target_link_libraries\(acousticguard_audio PRIVATE aaudio log\)", "platform AAudio link")
    require(native_cpp, r"#include <aaudio/AAudio\.h>", "platform AAudio header")
    require(native_cpp, r"AAudioStreamBuilder_setSampleRate", "16 kHz AAudio request")
    require(native_cpp, r"AAUDIO_SHARING_MODE_EXCLUSIVE", "best-effort exclusive request")
    require(native_cpp, r"AAUDIO_PERFORMANCE_MODE_LOW_LATENCY", "AAudio low-latency request")
    require(native_cpp, r"Java_com_acousticguard_NativeAudioBoundary_nativeProbe", "JNI probe entry point")
    require(native_cpp, r"Java_com_acousticguard_NativeAudioBoundary_nativeStart", "JNI native audio start entry point")
    require(native_cpp, r"Java_com_acousticguard_NativeAudioBoundary_nativeStop", "JNI native audio stop entry point")
    require(native_doc, r"disabled by default", "native default-off documentation")
    require(native_doc, r"AudioRecord", "native AudioRecord fallback documentation")
    require(queue, r"SharedPreferencesStoreAndForwardStorage", "durable outbox storage")
    require(queue, r"DEFAULT_MAX_ENTRIES", "bounded outbox entry limit")
    require(queue, r"expiresAtEpochMs", "outbox expiry metadata")
    require(queue, r"attemptCount", "outbox attempt metadata")
    require(queue, r"DUPLICATE", "outbox event-ID deduplication")
    require(flush_boundary, r"onConnectivityAvailable", "connectivity flush boundary")
    require(flush_boundary, r"markRetry", "retry state transition")
    require(network_boundary, r"enqueueEncryptedPayload", "encrypted payload enqueue integration")
    require(receiver, r"SosNetworkFlushBoundary", "receiver outbox integration")
    require(watchdog, r"onServiceDisconnected", "remote service watchdog")
    require(manifest, r'android:process=":remote"', "remote capture process")
    require(location, r"BURST_DURATION_MS", "location burst")
    require(location, r"CACHE_INTERVAL_MS", "location cache interval")
    require(noise, r"class AdaptiveNoiseProfile", "dynamic noise floor")
    require(personalization, r"MAX_SAMPLES = 3", "three-sample personalization")
    require(motion, r"TYPE_ACCELEROMETER", "accelerometer fusion")
    require(motion, r"TYPE_GYROSCOPE", "gyroscope fusion")
    require(rapid, r"RAPID_KEYWORD_THRESHOLD = 0\.90f", "rapid keyword threshold")
    require(rapid, r"SINGLE_WINDOW_DISTRESS_THRESHOLD = 0\.95f", "single-window vocal threshold")
    require(rapid, r"STRONG_IMPACT_WITH_MOTION", "rapid multi-sensor escalation")
    require(taxonomy, r"IndianLanguage", "Indian language taxonomy")
    require(taxonomy, r"সাহায্য", "Bengali distress label")
    require(taxonomy, r"உதவி", "Tamil distress label")
    require(taxonomy, r"ಸಹಾಯ", "Kannada distress label")
    require(reasoning, r"class DistressReasoningEngine", "distress reasoning engine")
    require(reasoning, r"Protected distress paths always win", "owner voice safety precedence")
    require(owner_voice, r"interface OwnerVoiceProfileStore", "owner voice profile boundary")
    require(owner_voice, r"never accepts PCM", "owner voice no-audio storage")
    require(voice_model, r"interface VoiceEmbeddingModel", "voice embedding boundary")
    require(voice_model, r"DEFAULT_ASSET_NAME", "voice embedding asset contract")
    require(encrypted_owner_voice, r"AES/GCM/NoPadding", "encrypted owner voice profile")
    require(web, r"Owner Voice Profile", "owner voice UI")
    require(web, r"prefers-reduced-motion", "reduced motion UI support")
    require(service, r"evaluateRapid", "early rapid model probe")
    require(service, r"audioBuffer\.sizeBytes\(\) >= BUFFER_BYTES", "full-context standard gate")
    require(pin, r"PBKDF2WithHmacSHA256", "hashed PIN storage")
    require(pin, r"matchesDuressPin", "duress PIN verification")
    require(crypto, r"setIsStrongBoxBacked", "StrongBox best-effort key creation")
    require(crypto, r"fun rotateKey", "key rotation")
    require(
        readme,
        r"does not enable an audible or near-ultrasonic SOS transmission\s+channel by default",
        "audible/near-ultrasonic default-off policy",
    )
    require(readme, r"visible\s+opt-in with informed consent", "audible/near-ultrasonic consent policy")
    if re.search(
        r"find_package\(\s*oboe|oboe::|implementation\([^)]*oboe",
        app_gradle + native_cmake + native_cpp,
        flags=re.IGNORECASE,
    ):
        raise AssertionError("native scaffold must not add an Oboe dependency")
    if len(labels) != 16 or not {
        "screaming_vocal_friction", "hyperventilation_gasp", "physical_struggle_thud",
        "physical_restraint_groan", "suffocation_stridor", "help", "madad", "मदद",
        "bachao", "बचाओ", "sahayata", "सहायता"
    }.issubset(labels):
        raise AssertionError("multilingual distress label contract is incomplete")

    print("AcousticGuard offline validation: PASS")
    print("Checked manifest permissions, microphone FGS, bounded PCM path, TFLite thresholds, exact alarm, grace period, multilingual labels, gated AAudio/JNI scaffold, encrypted outbox, connectivity boundary, and SOS channel disclosure.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, FileNotFoundError) as error:
        print(f"AcousticGuard offline validation: FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
