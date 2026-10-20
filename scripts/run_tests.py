#!/usr/bin/env python3
"""AcousticGuard test runner.

Runs offline architectural and invariant validation, and optionally JVM tests
if Gradle is available on PATH.
"""

import os
import shutil
import subprocess
import sys


def run_command(cmd, desc):
    print(f"==> {desc}: {' '.join(cmd)}")
    result = subprocess.run(cmd)
    if result.returncode != 0:
        print(f"FAILED: {desc} (exit code {result.returncode})", file=sys.stderr)
        return False
    return True


def main():
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    os.chdir(repo_root)

    print("--- Running AcousticGuard Validation Suite ---")
    
    # 1. Structural and invariant validation
    val_script = os.path.join(repo_root, "scripts", "validate_project.py")
    if not run_command([sys.executable, val_script], "Offline Architectural Validation"):
        sys.exit(1)

    # 2. JVM Unit Tests (if gradlew or gradle is available)
    gradle_bin = shutil.which("gradle") or shutil.which("gradlew")
    if gradle_bin:
        print("Gradle detected. Running JVM contract and unit tests...")
        if not run_command([gradle_bin, ":app:testDebugUnitTest"], "JVM Unit Tests"):
            sys.exit(1)
    else:
        print("Note: gradle/gradlew not found on PATH. Skipping JVM tests.")

    print("\nAll AcousticGuard test phases PASSED successfully.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
