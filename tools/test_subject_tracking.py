#!/usr/bin/env python3
"""Run the pure Kotlin tracking checks with the compiler already cached by Gradle."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"

def jar(group, artifact, version):
    matches = list((cache / group / artifact / version).glob(f"*/{artifact}-{version}.jar"))
    if not matches:
        raise SystemExit(f"Missing {artifact}:{version}. Run the Android Kotlin build first.")
    return str(matches[0])

compiler = [jar("org.jetbrains.kotlin", artifact, "2.2.10") for artifact in (
    "kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-script-runtime", "kotlin-reflect")]
compiler += [jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
             jar("org.jetbrains", "annotations", "13.0")]
stdlib = compiler[1]
with tempfile.TemporaryDirectory(prefix="hooru-tracking-") as output:
    subprocess.run(["java", "-cp", os.pathsep.join(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib", "-no-reflect", "-classpath", stdlib, "-d", output,
                    str(root / "android/app/src/main/java/com/purepixel/camera/tracking/SubjectTracker.kt"),
                    str(root / "android/app/src/test/java/com/purepixel/camera/tracking/SubjectTrackerCheck.kt")], check=True)
    subprocess.run(["java", "-cp", output + os.pathsep + stdlib,
                    "com.purepixel.camera.tracking.SubjectTrackerCheckKt"], check=True)
