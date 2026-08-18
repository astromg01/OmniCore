from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch(path: str, old: str, new: str, count: int = 1) -> None:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    found = text.count(old)
    if found < count:
        raise SystemExit(f"{path}: expected {count} occurrence(s), found {found}: {old!r}")
    target.write_text(text.replace(old, new, count), encoding="utf-8")


# Modifier.weight is a RowScope member extension in this Compose version; the
# explicit foundation.layout.weight import resolves an internal parent-data symbol.
patch(
    "app/src/main/java/com/omnicore/emulator/ui/achievements/AchievementsScreen.kt",
    "import androidx.compose.foundation.layout.weight\n",
    "",
)

# FileChannel protects main <-> :n64. Synchronization also prevents two threads
# inside the same process from taking overlapping JVM file locks simultaneously.
patch(
    "app/src/main/java/com/omnicore/emulator/achievements/OmniAchievements.kt",
    "    private fun <T> withLockedState(context: Context, writeBack: Boolean, block: (State) -> T): T {\n",
    "    @Synchronized\n    private fun <T> withLockedState(context: Context, writeBack: Boolean, block: (State) -> T): T {\n",
)

print("Alpha 17 compile/locking postfix applied")
