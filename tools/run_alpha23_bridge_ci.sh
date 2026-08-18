#!/usr/bin/env bash
set -euo pipefail

# Alpha 23 reuses the already-proven Alpha 22 release validator, but rewrites
# only the version/migration/feature assertions for SmoothAudioResampler + SyncSlew.
python3 - <<'PY'
from pathlib import Path
import re

source = Path('tools/run_alpha22_bridge_ci.sh').read_text(encoding='utf-8')
text = source
text = text.replace('0.10.21', '0.10.22')
text = text.replace('Alpha 22', 'Alpha 23')
text = text.replace('alpha22', 'alpha23')
text = text.replace('ALPHA22', 'ALPHA23')
text = text.replace('agent_01021_alpha23.py', 'agent_01022_alpha23.py')
text = text.replace('versionCode = 37', 'versionCode = 38')
text = text.replace('AAudio host v18', 'AAudio host v19')
text = text.replace(
    "if grep -q 'versionName = \"0.10.20\"' app/build.gradle.kts; then",
    "if grep -q 'versionName = \"0.10.21\"' app/build.gradle.kts; then",
    1,
)
text = text.replace(
    "grep -q 'RacingComfort' \"$HOST\"",
    "grep -q 'RacingComfort' \"$HOST\"\n"
    "grep -q 'SmoothAudioResampler' \"$HOST\"\n"
    "grep -q 'SyncSlew' \"$HOST\"\n"
    "grep -q 'resampleNextOutputPos' \"$HOST\"\n"
    "grep -q 'audioSyncScaleSmoothed' \"$HOST\"\n"
    "! grep -q 'resampleAccumulator' \"$HOST\"",
    1,
)
text = text.replace(
    'Apply OmniCore 0.10.22 N64 Alpha 23 StartupAudioGate [skip ci]',
    'Apply OmniCore 0.10.22 N64 Alpha 23 SmoothAudioResampler + SyncSlew [skip ci]',
)
notes = (
    'Twenty-third Nintendo 64 device-test alpha. Alpha 22 nearly eliminated the startup-side audio problem, '
    'leaving short artifacts that are easiest to hear when dense mixes or several effects are active together. '
    'The frontend receives one already-mixed stereo PCM stream from Mupen64Plus-Next; the remaining weakness was '
    'OmniCore\'s tiny pacing correction path, which previously repeated or skipped whole PCM frames whenever sync '
    'moved away from 1.0x. Alpha 23 replaces that nearest-neighbor behavior with a continuous streaming linear '
    'interpolator across libretro audio batches and adds SyncSlew so reserve corrections ramp gradually instead of '
    'jumping directly as high as +1.8 percent. StartupAudioGate, ElasticAudioBridge and TransitionAudioShield remain '
    'in place. Protected 1.5x resolution, framebuffer compatibility, PrecisionGovernor v2.1, RacingComfort, Smart '
    'Analog/Kirby, DirectPresenter, WarmCache and PS1 isolation remain unchanged. ROMs, BIOS, firmware, keys and '
    'games are not included.'
)
text = re.sub(r'NOTES=".*?"\nOUT=', 'NOTES="' + notes.replace('"', '\\"') + '"\nOUT=', text, count=1, flags=re.S)
Path('/tmp/run_alpha23_full.sh').write_text(text, encoding='utf-8')
PY
chmod +x /tmp/run_alpha23_full.sh
exec /tmp/run_alpha23_full.sh
