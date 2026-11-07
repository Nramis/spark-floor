"""Publish the tested, locally signed APK as a GitHub prerelease using an authenticated gh CLI."""

import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--gh", default=shutil.which("gh"), help="Path to an authenticated GitHub CLI")
parser.add_argument("--repo", default="Nramis/spark-floor")
args = parser.parse_args()
if not args.gh:
    parser.error("Install GitHub CLI, run gh auth login, or provide --gh PATH")

manifest = ElementTree.parse(ROOT / "AndroidManifest.xml").getroot()
version = manifest.attrib["{http://schemas.android.com/apk/res/android}versionName"]
tag = "v" + (version + ".0" if version.count(".") == 1 else version)
apk = ROOT / f"build/outputs/apk/local/spark-floor-{version}-debug.apk"
notes = ROOT / f"docs/releases/{tag}.md"
if not apk.is_file() or not notes.is_file():
    parser.error(f"Build {apk.name} and prepare {notes.relative_to(ROOT)} first")

subprocess.run(["git", "diff", "--quiet"], cwd=ROOT, check=True)
subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=ROOT, check=True)
head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
remote = subprocess.check_output(
    ["git", "ls-remote", "origin", "refs/heads/main"], cwd=ROOT, text=True
).split()[0]
if head != remote:
    parser.error("Publish and synchronize main before creating its release")

report = ROOT / "verification.json"
import json

expected = json.loads(report.read_text(encoding="utf-8"))[f"apk_sha256_v{version.replace('.', '')}"]
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
if digest != expected:
    parser.error("APK differs from verification.json; verify the build before publishing")
checksums = apk.parent / "SHA256SUMS.txt"
checksums.write_text(f"{digest}  {apk.name}\n", encoding="utf-8")
subprocess.run(
    [
        args.gh,
        "release",
        "create",
        tag,
        str(apk),
        str(checksums),
        "--repo",
        args.repo,
        "--target",
        head,
        "--title",
        f"Spark Floor {version} — Voice Assistant",
        "--notes-file",
        str(notes),
        "--prerelease",
    ],
    cwd=ROOT,
    check=True,
)
subprocess.run(
    [
        args.gh,
        "release",
        "view",
        tag,
        "--repo",
        args.repo,
        "--json",
        "url,tagName,isPrerelease,assets",
    ],
    cwd=ROOT,
    check=True,
)
