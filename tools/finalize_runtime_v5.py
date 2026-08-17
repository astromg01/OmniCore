from pathlib import Path

host = Path('app/src/main/cpp/libretro_host_v5.cpp')
text = host.read_text()
if '#include <cmath>\n' not in text:
    text = text.replace('#include <chrono>\n', '#include <chrono>\n#include <cmath>\n')
host.write_text(text)

bios = Path('app/src/main/java/com/omnicore/emulator/storage/Ps1BiosHealth.kt')
s = bios.read_text()
s = s.replace('digest.digest().joinToString("") { "%02x".format(it) }', 'digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }')
bios.write_text(s)

print('runtime v5 finalizer applied')
