"""Read-only exact-image review; never executes or rewrites a game file."""
from pathlib import Path
import argparse, hashlib, json, re, struct

ROOT = Path(__file__).resolve().parents[1]
HASHES = {
    (0x134D2DEF, 2): '988F6A61403B4381AA41E6C66D64CCE441B14E561A9B10F647C2758EF3F2672A',
    (0x134D2DEF, 3): 'E34D9DBB574790CAB5293E8B2C9C31A696CD58287281C8424255C6CF5045C40B',
    (0x134D3CBA, 2): '019F4EE7C82DB0E0C187653BC76C61CF75502FF6866A5CC6603FA0C909A386A2',
    (0x134D3CBA, 3): 'DA6C84C32681A7CD7F82CF3573B08E23D629BB2E6E6EC73AD44DE098248FCE63',
    (0x134D3CBB, 2): '537A7BD5AB1BEB109BD9D2E5206B6DF35BF273D01717EF9C5B20EC4B68CC3017',
    (0x134D3CBB, 3): '68B38A0B3FD2A57C4538DBFAC5BEACE14AFD774513E2CBF1BE4443E9DE010768',
}
GAME = {'.text', '.rdata', '.data', '.pdata', '.tls', '.xbld', '.rsrc', '.reloc'}

def sha(data): return hashlib.sha256(data).hexdigest().upper()

class Image:
    def __init__(self, path):
        self.data = Path(path).read_bytes()
        pe = struct.unpack_from('<I', self.data, 60)[0]
        assert self.data[pe:pe+4] == b'PE\0\0'
        self.opt = pe + 24
        assert struct.unpack_from('<HH', self.data, pe+4)[0] == 0x8664
        assert struct.unpack_from('<I', self.data, pe+8)[0] == 0x6AD79EE4
        table = self.opt + struct.unpack_from('<H', self.data, pe+20)[0]
        self.sections = {}
        for i in range(struct.unpack_from('<H', self.data, pe+6)[0]):
            row = self.data[table+40*i:table+40*(i+1)]
            size, rva, raw_size, offset = struct.unpack_from('<4I', row, 8)
            name = row[:8].rstrip(b'\0').decode('ascii')
            self.sections[name] = (row, self.data[offset:offset+raw_size], rva, size)

    def read(self, rva, size):
        for _, data, va, _ in self.sections.values():
            if va <= rva and rva + size <= va + len(data): return data[rva-va:rva-va+size]
        raise AssertionError(('unmapped RVA', hex(rva), size))

def review(original, launcher, menu_source, bodies_path):
    old, patched = Image(original), Image(launcher)
    assert sha(old.data) in (HASHES[0x134D2DEF, 2], HASHES[0x134D2DEF, 3])
    assert len(old.data) == 323819336 and len(patched.data) == 323821384
    assert set(old.sections) == GAME and set(patched.sections) == GAME | {'.levildr'}
    assert old.data[old.opt+16:old.opt+20] == patched.data[patched.opt+16:patched.opt+20]
    sections = {}
    for name in sorted(GAME):
        assert old.sections[name] == patched.sections[name], name
        sections[name] = {'sha256': sha(old.sections[name][1]), 'headerIdentical': True}
    assert b'LeviLauncherEntry' in patched.sections['.levildr'][1]
    assert b'LeviLauncher.dll' in patched.sections['.levildr'][1]
    forms = []
    for im, sums, image_size in [(old, [0x134D2DEF], 0x1383B000), (patched, [0x134D3CBA, 0x134D3CBB], 0x1383C000)]:
        assert struct.unpack_from('<I', im.data, im.opt+56)[0] == image_size
        data = bytearray(im.data)
        for checksum in sums:
            struct.pack_into('<I', data, im.opt+64, checksum)
            for subsystem in [2, 3]:
                struct.pack_into('<H', data, im.opt+68, subsystem)
                actual = sha(data)
                assert actual == HASHES[checksum, subsystem], (checksum, subsystem, actual)
                forms.append({'sha256': actual, 'bytes': len(data), 'subsystem': subsystem, 'checksum': hex(checksum)})
    guards = 0
    for group in ['World', 'Environment', 'Entity', 'Network', 'Cinematic']:
        for address, raw in re.findall(r'\{(0x[0-9A-Fa-f]+),\{([^}]+)\}\}', (ROOT/f'src/{group}Guards2660.inl').read_text()):
            guard = bytes(int(v, 16) for v in raw.split(','))
            assert len(guard) == 32 and patched.read(int(address, 16), 32) == guard
            guards += 1
    records = json.loads(Path(bodies_path).read_text())['records']
    for start, row in records.items():
        at, end = int(start, 16), int(row['end'], 16)
        assert sha(patched.read(at, end-at)).lower() == row['sha256']
    assert guards == 61 and len(records) == 48
    workspace = {}
    for rel in ['src/Workspace.cpp', 'src/WorkspaceApi.cpp', 'include/ModMenu/Workspace.h']:
        current = (Path(menu_source)/rel).read_bytes()
        assert current == (ROOT/'tests/vendor/modmenu-ll-test1'/rel).read_bytes(), rel
        workspace[rel] = sha(current)
    return {'gameSections': sections, 'originalEntrypointIdentical': True, 'forms': forms,
            'nativeGuards': guards, 'fullNativeBodies': len(records), 'test11WorkspaceFixtureIdentical': workspace,
            'gameModified': False, 'gameLaunched': False, 'manualMinecraftAcceptance': False}

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    for n in ['original', 'launcher', 'menu-source', 'native-bodies', 'output']: p.add_argument('--'+n, required=True)
    a = p.parse_args()
    result = review(a.original, a.launcher, a.menu_source, a.native_bodies)
    Path(a.output).write_text(json.dumps(result, indent=2)+'\n')
    print('Eight original sections/headers, entrypoint, six exact forms, 61 guards, 48 native bodies and test11 Workspace API verified; gameplay pending')
