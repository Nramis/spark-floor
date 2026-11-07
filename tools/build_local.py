"""Fallback debug APK build for this resource-free project using installed Android SDK tools.

No Gradle plugin or Maven classpath resolution is needed. Requires Python, JDK 17+
and Android SDK platform 35 / build-tools 35+. The key is local to build/ and is
not the signing key of the original distributed application.
"""

import hashlib
import os
from pathlib import Path
import runpy
import shutil
import subprocess
import urllib.request
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "build" / "local-sdk"
WORK.mkdir(parents=True, exist_ok=True)
MODEL_SHA = "3e5c18a4be395f16646438d54c42377ddc202edfa33d5eced0c9506de75c44c2"
RUNTIME_SHA = "75b2927c51955aed63f29644a8c3263a90293e612225a1e209d992565f8f4394"


def run(*args):
    subprocess.run([str(a) for a in args], check=True, cwd=ROOT)


def verify(path, expected):
    if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise RuntimeError(f"SHA256 mismatch: {path}")


sdk = Path(
    os.environ.get("ANDROID_SDK_ROOT")
    or os.environ.get("ANDROID_HOME")
    or Path.home() / "AppData/Local/Android/Sdk"
)
java_home = Path(os.environ.get("JAVA_HOME") or "C:/Program Files/Android/Android Studio/jbr")
suffix = ".exe" if os.name == "nt" else ""
java = java_home / "bin" / ("java" + suffix)
javac = java_home / "bin" / ("javac" + suffix)
jar = java_home / "bin" / ("jar" + suffix)
android = sdk / "platforms/android-35/android.jar"
versions = [
    p
    for p in (sdk / "build-tools").iterdir()
    if p.is_dir() and all(s.isdigit() for s in p.name.split("."))
]
tools = max(versions, key=lambda p: tuple(map(int, p.name.split("."))))
if not java.exists() or not android.exists():
    raise RuntimeError(
        "Set JAVA_HOME and ANDROID_SDK_ROOT to a JDK and Android SDK with platform 35"
    )

model = ROOT / "assets/floor.onnx"
if not model.exists():
    runpy.run_path(str(ROOT / "download_model.py"))
verify(model, MODEL_SHA)
runtime = WORK / "onnxruntime.aar"
if not runtime.exists():
    url = (
        "https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/"
        "onnxruntime-android/1.16.3/onnxruntime-android-1.16.3.aar"
    )
    partial = runtime.with_suffix(".partial")
    with urllib.request.urlopen(url, timeout=120) as response, partial.open("wb") as out:
        shutil.copyfileobj(response, out)
    verify(partial, RUNTIME_SHA)
    partial.replace(runtime)
verify(runtime, RUNTIME_SHA)
runtime_jar = WORK / "onnxruntime-classes.jar"
with ZipFile(runtime) as aar:
    runtime_jar.write_bytes(aar.read("classes.jar"))

classes = WORK / "classes"
classes.mkdir(exist_ok=True)
sources = sorted((ROOT / "src").rglob("*.java"))
run(
    javac,
    "-source",
    "8",
    "-target",
    "8",
    "-encoding",
    "UTF-8",
    "-cp",
    os.pathsep.join(map(str, [android, runtime_jar])),
    "-d",
    classes,
    *sources,
)
app_jar = WORK / "app-classes.jar"
run(jar, "cf", app_jar, "-C", classes, ".")
dex = WORK / "dex"
dex.mkdir(exist_ok=True)
run(
    java,
    "-cp",
    tools / "lib/d8.jar",
    "com.android.tools.r8.D8",
    "--min-api",
    "26",
    "--lib",
    android,
    "--output",
    dex,
    app_jar,
    runtime_jar,
)
unsigned = WORK / "unsigned.apk"
run(
    tools / ("aapt2" + suffix),
    "link",
    "-I",
    android,
    "--manifest",
    ROOT / "AndroidManifest.xml",
    "-A",
    ROOT / "assets",
    "-o",
    unsigned,
    "--min-sdk-version",
    "26",
    "--target-sdk-version",
    "35",
)
with ZipFile(unsigned, "a", compression=ZIP_DEFLATED) as apk, ZipFile(runtime) as aar:
    for entry in dex.glob("*.dex"):
        apk.write(entry, entry.name)
    for name in aar.namelist():
        if name.endswith(".so") and name.startswith(("jni/armeabi-v7a/", "jni/arm64-v8a/")):
            apk.writestr("lib/" + name[len("jni/") :], aar.read(name))

aligned = WORK / "aligned.apk"
run(tools / ("zipalign" + suffix), "-f", "4", unsigned, aligned)
key = ROOT / "build/local-debug.keystore"
if not key.exists():
    run(
        java_home / "bin" / ("keytool" + suffix),
        "-genkeypair",
        "-keystore",
        key,
        "-storepass",
        "android",
        "-keypass",
        "android",
        "-alias",
        "androiddebugkey",
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-validity",
        "10000",
        "-dname",
        "CN=Android Debug,O=Android,C=US",
    )
output = ROOT / "build/outputs/apk/local/spark-floor-0.9-debug.apk"
output.parent.mkdir(parents=True, exist_ok=True)
signer = tools / "lib/apksigner.jar"
run(
    java,
    "-jar",
    signer,
    "sign",
    "--ks",
    key,
    "--ks-pass",
    "pass:android",
    "--key-pass",
    "pass:android",
    "--out",
    output,
    aligned,
)
run(java, "-jar", signer, "verify", "--verbose", output)
run(tools / ("zipalign" + suffix), "-c", "4", output)
print(f"APK: {output}")
print(f"SHA256: {hashlib.sha256(output.read_bytes()).hexdigest()}")
