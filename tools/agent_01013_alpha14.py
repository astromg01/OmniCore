from pathlib import Path
import subprocess

# Alpha 14 migration wrapper.
# The original implementation is immutable in the commit below. We patch one
# generator escape before execution so the emitted Kotlin contains '\u0000'
# (one Kotlin escape), not an illegal double-backslash character literal.
IMPLEMENTATION_COMMIT = "8211837ccef1de99ee4b9bf671630d5d0b7c0c97"

source = subprocess.check_output(
    ["git", "show", f"{IMPLEMENTATION_COMMIT}:tools/agent_01013_alpha14.py"],
    text=True,
)
bad = ".replace('\\\\u0000', ' ')"
good = ".replace('\\u0000', ' ')"
patched = source.replace(bad, good, 1)
if patched == source:
    raise SystemExit("Alpha 14 migration wrapper could not locate Kotlin NUL escape")

namespace = {
    "__name__": "__main__",
    "__file__": str(Path(__file__).resolve()),
}
exec(compile(patched, "agent_01013_alpha14_impl.py", "exec"), namespace)
