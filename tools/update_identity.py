from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OLD = "astromg01"
NEW = "mauricio-gamedev"

TEXT_EXTS = {".md", ".kt", ".kts", ".cpp", ".h", ".hpp", ".xml", ".yml", ".yaml", ".sh", ".txt", ".properties", ".toml", ".json"}
SKIP_PARTS = {".git", "build", ".gradle", "third_party/pcsx_rearmed"}
SKIP_FILES = {"tools/update_identity.py", ".github/workflows/identity-migrate.yml"}

def eligible(path: Path) -> tuple[bool, str]:
    if not path.is_file() or path.suffix.lower() not in TEXT_EXTS:
        return False, ""
    rel = path.relative_to(ROOT).as_posix()
    if rel in SKIP_FILES or any(part in rel for part in SKIP_PARTS):
        return False, rel
    return True, rel

changed = []
for path in ROOT.rglob("*"):
    ok, rel = eligible(path)
    if not ok:
        continue
    text = path.read_text(encoding="utf-8")
    new = text.replace(OLD, NEW)
    if new != text:
        path.write_text(new, encoding="utf-8")
        changed.append(rel)

readme = ROOT / "README.md"
text = readme.read_text(encoding="utf-8")
text = text.replace("current%20DEV-0.8.0", "current%20DEV-0.9.0")
text = text.replace("v0.8.0-dev", "v0.9.0-dev")
text = text.replace("## Current status — v0.8.0 DEV", "## Current status — v0.9.0 DEV")
text = text.replace(
    "The v0.8.0 line keeps the working v0.7 video/audio foundation intact and focuses on compatibility, startup latency and user-facing refinement.",
    "The v0.9.0 line preserves the validated v0.7/v0.8 runtime foundation and focuses on input compatibility and frontend polish."
)
text = text.replace(
    "- Touch controls with D-pad, left analog stick, face buttons, shoulders, Start and Select.\n- Android/Bluetooth controller input path.",
    "- Touch controls with D-pad, left analog stick, face buttons, shoulders, Start and Select.\n- **Intelligent left-stick mode**: native DualShock axes plus D-pad projection for early PS1 games that only understand digital movement.\n- Selectable left-stick modes: Intelligent, Native and D-pad.\n- Configurable touch size, opacity and optional haptics.\n- Android USB/Bluetooth controller axes through the native Android joystick path."
)
text = text.replace(
    "- Persistent validated **CUE/BIN disc cache** so unchanged games do not need to copy large BIN tracks on every launch.\n- Presentation modes:",
    "- Persistent validated **CUE/BIN disc cache** so unchanged games do not need to copy large BIN tracks on every launch.\n- Library search, recent/A–Z/size sorting and confirmation before removing entries.\n- Presentation modes:"
)
text = text.replace("**[OmniCore v0.8.0 DEV — Compatibility & UX]", "**[OmniCore v0.9.0 DEV — Input & Frontend Polish]")
text = text.replace("OmniCore-v0.8.0-debug.apk", "OmniCore-v0.9.0-debug.apk")
text = text.replace(
    "**OmniCore** is developed by [@mauricio-gamedev](https://github.com/mauricio-gamedev).",
    "**OmniCore** is developed by [Mauricio.gamedev (@mauricio-gamedev)](https://github.com/mauricio-gamedev)."
)
readme.write_text(text, encoding="utf-8")

changelog = ROOT / "CHANGELOG.md"
text = changelog.read_text(encoding="utf-8")
if "## 0.9.0 — Input & Frontend Polish" not in text:
    insert = '''# Changelog\n\n## 0.9.0 — Input & Frontend Polish\n\n### Input\n- Added Intelligent left-stick compatibility mode: native DualShock axes plus D-pad projection for early PS1 games.\n- Added selectable Intelligent, Native and D-pad stick modes.\n- Added Android USB/Bluetooth joystick-axis handling with deadzone normalization.\n- Added configurable touch-control scale, opacity and optional haptics.\n\n### Frontend\n- Added library search and Recent / A–Z / Size ordering.\n- Added confirmation before removing library entries.\n- Preserved Runtime v7, EGL/GLES, AAudio, BIOS boot, CUE/BIN cache, saves and the stable DEV update path.\n\n## 0.8.0 — Compatibility & UX\n\n- Added persistent validated CUE/BIN staging cache for faster repeated launches.\n- Added optional classic PlayStation BIOS boot/logo.\n- Added 4:3, 16:9 presentation and fullscreen modes.\n- Preserved the validated Runtime v7 / EGL-GLES video foundation.\n\n## 0.7.0 — Video Composition Fix\n\n- Fixed Android SurfaceView composition that could hide correctly rendered PS1 frames behind an opaque View background.\n- Switched the PS1 compatibility path to XRGB8888 output and explicit GLES presentation.\n- Marked the first real-device PS1 gameplay milestone with working video and audio.\n\n'''
    text = text.replace("# Changelog\n\n", insert, 1)
changelog.write_text(text, encoding="utf-8")

ui = ROOT / "app/src/main/java/com/omnicore/emulator/ui/OmniCoreV3App.kt"
text = ui.read_text(encoding="utf-8")
needle = '                Text("BIOS: $biosCount arquivo(s) .bin", color = HubSoft, style = MaterialTheme.typography.bodySmall)\n'
credit = '                Text("Desenvolvido por Mauricio.gamedev • @mauricio-gamedev", color = HubCyan, style = MaterialTheme.typography.bodySmall)\n'
if credit not in text:
    if needle not in text:
        raise SystemExit("System credit insertion point not found")
    text = text.replace(needle, needle + credit, 1)
ui.write_text(text, encoding="utf-8")

# Safety: package identity must remain stable for Android in-place updates.
gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
if 'applicationId = "com.omnicore.emulator"' not in gradle:
    raise SystemExit("Unexpected applicationId; refusing identity migration")

remaining = []
for path in ROOT.rglob("*"):
    ok, rel = eligible(path)
    if not ok:
        continue
    try:
        data = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        continue
    if OLD in data:
        remaining.append(rel)
if remaining:
    raise SystemExit("Old identity remains in: " + ", ".join(remaining))

print("Identity migration complete")
print("Updated old references in:")
for item in changed:
    print(" -", item)
