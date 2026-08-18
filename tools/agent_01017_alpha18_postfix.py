from pathlib import Path

path = Path('app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt')
text = path.read_text(encoding='utf-8')
replacements = {
    'achievementAsync { OmniAchievements.recordN64Launch(this, currentGameKey) }':
        'achievementAsync { OmniAchievements.recordN64Launch(this@N64EmulationActivity, currentGameKey) }',
    'achievementAsync { OmniAchievements.recordLayoutEdit(this) }':
        'achievementAsync { OmniAchievements.recordLayoutEdit(this@N64EmulationActivity) }',
}
for old, new in replacements.items():
    if text.count(old) != 1:
        raise SystemExit(f'expected exactly one Alpha 18 Kotlin context pattern: {old}')
    text = text.replace(old, new, 1)
path.write_text(text, encoding='utf-8')
print('Alpha 18 Kotlin context postfix applied')
