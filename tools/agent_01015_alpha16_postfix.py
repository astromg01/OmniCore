from pathlib import Path

path = Path('app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt')
text = path.read_text(encoding='utf-8')
old = 'import kotlin.math.abs\n'
new = 'import kotlin.math.abs\nimport kotlin.math.roundToInt\n'
if new not in text:
    if old not in text:
        raise SystemExit('Alpha 16 postfix: kotlin.math.abs import not found')
    text = text.replace(old, new, 1)
path.write_text(text, encoding='utf-8')
print('Alpha 16 Kotlin import postfix applied')
