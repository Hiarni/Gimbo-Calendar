#!/usr/bin/env python3

from pathlib import Path
import hashlib
import re
import shutil
import zipfile

root = Path(__file__).resolve().parents[1]
version = re.search(r'versionName = "([^"]+)"', (root / "app/build.gradle.kts").read_text()).group(1)
output = root / "artifacts"
output.mkdir(exist_ok=True)
apk = output / f"Gimbo-Calendar-{version}.apk"
shutil.copyfile(root / "app/build/outputs/apk/debug/app-debug.apk", apk)
archive = output / f"Gimbo-Calendar-{version}-Quellcode.zip"
files = [root / name for name in (
    ".gitattributes", ".gitignore", "README.md", "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
    "gradlew", "gradlew.bat", "app/build.gradle.kts",
)]
for directory in ("app/src", "app/schemas", "docs", "gradle", "scripts", ".github"):
    files.extend(path for path in (root / directory).rglob("*") if path.is_file() and "__pycache__" not in path.parts)
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
    for path in sorted(set(files)):
        if path.exists():
            bundle.write(path, "Gimbo-Calendar/" + str(path.relative_to(root)))
with (output / "SHA256SUMS").open("w") as checksums:
    for path in (apk, archive):
        checksums.write(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n")
print(f"APK: {apk.name} ({apk.stat().st_size / 1024 / 1024:.1f} MB)")
print(f"Quellcode: {archive.name}")
