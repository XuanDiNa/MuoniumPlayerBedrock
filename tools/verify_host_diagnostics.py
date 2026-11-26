"""Regression probe: real LL DLL refusal must explain its host check.

Runs only an owned non-game Python process with the allocator fixture.
No game launch, installation, account or network work.
"""
from pathlib import Path
import ctypes
import hashlib
import os
import re
import sys

root = Path(__file__).resolve().parents[1]
dll_path = Path(sys.argv[1]).resolve()
fixture = root / 'build/windows/x64/release/ll-fixture'
with os.add_dll_directory(str(fixture)):
    allocator = ctypes.CDLL(str(fixture / 'LeviLamina.dll'))
    dll = ctypes.CDLL(str(dll_path))
    dll.MPB_GetStatus.restype = ctypes.c_int
    dll.ll_mod_load.argtypes = [ctypes.c_void_p]
    dll.ll_mod_load.restype = ctypes.c_bool
    assert dll.MPB_GetStatus() == 0
    owner = ctypes.create_string_buffer(512)
    assert not dll.ll_mod_load(ctypes.addressof(owner))
    assert dll.MPB_GetStatus() == 0, 'Refused load must not start any worker'
logs = list((dll_path.parent / 'logs').glob(f'MuoniumPlayerBedrock-*-{os.getpid()}.log'))
assert len(logs) == 1
text = logs[0].read_text(encoding='utf-8')
host = re.search(r'HOST_DIAGNOSTIC revision=1 reason=(\S+) win32=(\d+) bytes=(\d+) sha256=([A-F0-9]{64})', text)
assert host, 'The failed production LL load must explain its file check and record its digest'
assert host[1] == 'size-unrecognized' and host[2] == '0'
exe = Path(sys.executable)
assert int(host[3]) == exe.stat().st_size
assert host[4] == hashlib.sha256(exe.read_bytes()).hexdigest().upper()
assert str(exe) not in text, 'No user path should enter the report'
print('Real LL refusal diagnostics, SHA/size attribution, no worker and path privacy passed')
