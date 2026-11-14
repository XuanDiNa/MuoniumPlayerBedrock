"""Verify the real LL plugin stays dormant and refuses a non-game host."""
import ctypes,os,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]
fixture=root/'build/windows/x64/release/ll-fixture'
with os.add_dll_directory(str(fixture)):
    allocator=ctypes.CDLL(str(fixture/'LeviLamina.dll'))
    plugin=ctypes.CDLL(str(root/'build/windows/x64/release/MuoniumPlayerBedrockLL.dll'))
    plugin.MPB_GetStatus.restype=ctypes.c_int
    plugin.ll_mod_load.restype=plugin.ll_mod_disable.restype=ctypes.c_bool
    plugin.ll_mod_load.argtypes=plugin.ll_mod_disable.argtypes=[ctypes.c_void_p]
    assert plugin.MPB_GetStatus()==0,'LL DLL must not start from DllMain'
    owned=ctypes.create_string_buffer(512)
    assert not plugin.ll_mod_load(ctypes.addressof(owned)),'unsupported process must refuse'
    assert plugin.ll_mod_disable(ctypes.addressof(owned))
    assert plugin.MPB_GetStatus()==0,'failed LL admission must not start a worker'
print('Real LL DLL imports, dormant DllMain, unsupported-load refusal and idle disable passed; allocator fixture only')
