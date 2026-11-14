"""Load the plugin into this disposable non-game Python process; expect refusal."""
import ctypes
import sys
import time
from pathlib import Path

path = Path(sys.argv[1]).resolve()
dll = ctypes.CDLL(str(path))
dll.MPB_GetStatus.restype = ctypes.c_int
dll.MPB_GetVersion.restype = ctypes.c_char_p
from verify_direct_artifact import VERSION
expected = sys.argv[2] if len(sys.argv) > 2 else VERSION
assert dll.MPB_GetVersion() == expected.encode('ascii')
deadline = time.monotonic()+5
while dll.MPB_GetStatus() == 0 and time.monotonic() < deadline:
    time.sleep(.02)
assert dll.MPB_GetStatus() == -1, 'Production DLL failed to refuse unsupported host'
print('Production DLL load/export/unsupported-host refusal passed; no game process used')
