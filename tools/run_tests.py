"""Run the pure Java geometry and voice-command tests on Windows, Linux or macOS."""

from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
CLASSES = ROOT / "build/geometry-tests"
CLASSES.mkdir(parents=True, exist_ok=True)
sources = [
    "src/org/example/sparkfloor/FloorDistance.java",
    "src/org/example/sparkfloor/GroundProjection.java",
    "src/org/example/sparkfloor/FloorPath.java",
    "src/org/example/sparkfloor/Trapezoid.java",
    "src/org/example/sparkfloor/VoiceCommands.java",
    "DistanceTest.java",
    "TrapezoidTest.java",
    "FloorPathTest.java",
    "VoiceCommandsTest.java",
]
subprocess.run(
    ["javac", "--release", "8", "-encoding", "UTF-8", "-d", str(CLASSES)]
    + [str(ROOT / source) for source in sources],
    cwd=ROOT,
    check=True,
)
for test in ("DistanceTest", "TrapezoidTest", "FloorPathTest", "VoiceCommandsTest"):
    subprocess.run(["java", "-cp", str(CLASSES), test], cwd=ROOT, check=True)
