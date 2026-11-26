"""An owned non-game LL load must create a local, private hash-only report."""
from pathlib import Path
import ctypes, hashlib, json, os, re, sys

root=Path(__file__).resolve().parents[1]
path=Path(sys.argv[1]).resolve()
with os.add_dll_directory(str(root/'build/windows/x64/release/ll-fixture')):
    allocator=ctypes.CDLL(str(root/'build/windows/x64/release/ll-fixture/LeviLamina.dll'))
    dll=ctypes.CDLL(str(path));dll.ll_mod_load.argtypes=[ctypes.c_void_p];dll.ll_mod_load.restype=ctypes.c_bool
    dll.MPB_GetStatus.restype=ctypes.c_int
    owner=ctypes.create_string_buffer(512)
    assert not dll.ll_mod_load(ctypes.addressof(owner)) and dll.MPB_GetStatus()==0
reports=list((path.parent/'logs').glob(f'Muonium-host-evidence-*-{os.getpid()}.json'))
assert len(reports)==1,'Rejected production DLL must save one local evidence report, without starting Java'
raw=reports[0].read_text('utf-8');r=json.loads(raw)
assert r['schema']==1 and r['diagnosticOnly'] and not r['nativeAuthorized']
assert r['file']['sha256']==hashlib.sha256(Path(sys.executable).read_bytes()).hexdigest().upper()
assert not r['target2660'] and not r['nativeEquivalenceConfirmed']
assert r['sections'] and all(re.fullmatch('[A-F0-9]{64}',s['sha256']) for s in r['sections'])
assert str(Path(sys.executable)) not in raw and str(Path.home()) not in raw
assert all(k not in raw for k in ['headerBytes','rawBytes','cookies','accessToken'])
log=list((path.parent/'logs').glob(f'MuoniumPlayerBedrock-*-{os.getpid()}.log'))
assert len(log)==1
text=log[0].read_text('utf-8')
assert 'HOST_EVIDENCE revision=1 status=saved' in text and 'nativeAuthorized=no' in text
assert 'REFUSED native load' in text and 'LOAD ADMITTED' not in text and 'START version=' not in text
assert raw and len(raw.encode('utf-8'))<=128*1024
print('Real LL refusal saved bounded section hashes, no paths/raw bytes/authorization or player worker passed')
