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
    require(fallback, r"combinedConfidence = 0\.88f", "development DSP fallback")
    require(service, r"BuildConfig\.DEBUG", "debug-only fallback guard")
    require(service, r"ACTION_AUDIO_TELEMETRY", "audio telemetry broadcast")
    require(service, r"ACTION_SIMULATE_ALERT", "simulation action")
    require(activity, r"setContentView\(scroll\)", "attached main activity layout")
    require(activity, r"TEST SOS PIPELINE \(SIMULATE DISTRESS\)", "simulation button")
    if len(labels) != 16 or not {
        "screaming_vocal_friction", "hyperventilation_gasp", "physical_struggle_thud",
        "physical_restraint_groan", "suffocation_stridor", "help", "madad", "मदद",
        "bachao", "बचाओ", "sahayata", "सहायता"
    }.issubset(labels):
        raise AssertionError("multilingual distress label contract is incomplete")

    print("AcousticGuard offline validation: PASS")
    print("Checked manifest permissions, microphone FGS, bounded PCM path, TFLite thresholds, exact alarm, grace period, and multilingual labels.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, FileNotFoundError) as error:
        print(f"AcousticGuard offline validation: FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
