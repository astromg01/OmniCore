from pathlib import Path
import subprocess

# Stable Alpha 14 migration loader. The implementation was authored in the
# immutable commit below; execute it verbatim so validation always receives the
# exact reviewed migration source.
IMPLEMENTATION_COMMIT = "8211837ccef1de99ee4b9bf671630d5d0b7c0c97"
source = subprocess.check_output(
    ["git", "show", f"{IMPLEMENTATION_COMMIT}:tools/agent_01013_alpha14.py"],
    text=True,
)
namespace = {
    "__name__": "__main__",
    "__file__": str(Path(__file__).resolve()),
}
exec(compile(source, "agent_01013_alpha14_impl.py", "exec"), namespace)
