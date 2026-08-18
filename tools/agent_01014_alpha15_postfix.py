from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
path = ROOT / "app/src/main/cpp/n64/n64_libretro_host.cpp"
text = path.read_text(encoding="utf-8")
old = '''    if (smartPrecompileActive_.load(std::memory_order_acquire)) {\n        glFlush();\n        return;\n    }\n'''
if old not in text:
    raise SystemExit("Alpha 15 postfix: leftover SmartPrecompile video guard not found")
text = text.replace(old, "", 1)
if "smartPrecompileActive_" in text:
    raise SystemExit("Alpha 15 postfix: another smartPrecompileActive_ reference remains")
path.write_text(text, encoding="utf-8")
print("Alpha 15 postfix removed leftover SmartPrecompile video guard")
