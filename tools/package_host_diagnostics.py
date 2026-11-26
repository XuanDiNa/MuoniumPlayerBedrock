"""Package the DLL-only 0.14.7 LL hostcheck1 candidate, not a public release."""
from pathlib import Path
import argparse
import difflib
import hashlib
import json
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PREFIX = 'mods/MuoniumPlayerBedrock/'

def sha(data):
    return hashlib.sha256(data).hexdigest().upper()

def archive(path, files):
    with zipfile.ZipFile(path, 'x', zipfile.ZIP_DEFLATED) as z:
        for name, data in sorted(files.items()):
            z.writestr(name, data)
    with zipfile.ZipFile(path) as z:
        assert z.testzip() is None and set(z.namelist()) == set(files)
        for name, data in files.items():
            assert z.read(name) == data

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', required=True, type=Path)
    parser.add_argument('--evidence', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--audit-tools', required=True, type=Path)
    a = parser.parse_args()
    for line in (a.baseline / 'SHA256SUMS.txt').read_text().splitlines():
        digest, name = line.split(None, 1)
        assert sha((a.baseline / name.strip()).read_bytes()) == digest.upper()
    previous = json.loads((a.baseline / 'VERIFICATION.json').read_text('utf-8'))
    assert previous['version'] == '0.14.7'
    native = json.loads((a.evidence / 'native-release/results.json').read_text())
    assert len(native['suites']) == 34 and all(v['exit'] == 0 for v in native['suites'].values())
    evidence = {}
    for name in ['host-diagnostics-release.log', 'host-diagnostics-debug.log', 'diagnostic-dll-green.log']:
        text = (a.evidence / name).read_text('utf-8')
        assert 'passed' in text and 'Traceback' not in text and 'error:' not in text
        evidence[name] = text.strip()
    baseline_source = a.baseline / 'MuoniumPlayerBedrock-0.14.7-source.zip'
    changed_native = {'src/Host.cpp', 'src/Plugin.cpp', 'include/Runtime.h'}
    unchanged = {}
    patch = []
    overlays = ['include/Runtime.h', 'src/Host.cpp', 'src/Plugin.cpp', 'xmake.lua',
                'tests/HostDiagnosticsTests.cpp', 'tools/verify_host_diagnostics.py',
                'tools/package_host_diagnostics.py', 'docs/LL-HOST-DIAGNOSTICS.md']
    with zipfile.ZipFile(baseline_source) as z:
        assert z.testzip() is None
        for name in z.namelist():
            if name.startswith(('src/', 'include/')) and name not in changed_native:
                data = (ROOT / name).read_bytes()
                assert data == z.read(name), name
                unchanged[name] = sha(data)
        for name in overlays:
            old = z.read(name).decode('utf-8-sig') if name in z.namelist() else ''
            new = (ROOT / name).read_text('utf-8-sig')
            patch += difflib.unified_diff(old.splitlines(True), new.splitlines(True),
                                         fromfile='a/' + name if old else '/dev/null', tofile='b/' + name)
    jar = (ROOT / 'jvm/build/libs/MuoniumBedrockHost.jar').read_bytes()
    assert sha(jar) == previous['hostJarSha256']
    dll = (ROOT / 'build/windows/x64/release/MuoniumPlayerBedrockLL.dll').read_bytes()
    sys.path.insert(0, str(a.audit_tools.resolve()))
    from pe_audit import audit_runtime_dependencies
    from package_local import pe_exports
    imports, exports = audit_runtime_dependencies(dll), pe_exports(dll)
    assert {'MPB_GetVersion', 'MPB_GetStatus', 'll_mod_load', 'll_mod_enable', 'll_mod_disable'} <= set(exports)
    assert 'levilamina.dll' in imports and not {'modmenube.dll', 'modmenubedrock.dll'} & set(imports)
    report = {'version': '0.14.7', 'candidate': 'll-hostcheck1',
              'scope': 'DLL-only detailed host refusal diagnostics; player failure cause still unknown',
              'minecraft': '1.26.60.05', 'loader': 'LeviLamina 26.60.7',
              'dll': {'bytes': len(dll), 'sha256': sha(dll), 'exports': exports},
              'existingHostJarUnchanged': sha(jar), 'nativeRelease': native,
              'diagnosticChecks': evidence, 'unchangedNativeSources': unchanged,
              'exactImageAllowlistChanged': False, 'manualPlayerHostAcceptance': 'pending',
              'previous0147Packages': 'all five preserved with identical hashes',
              'installedGameModified': False, 'menuIncluded': False, 'published': False}
    a.output.mkdir(parents=True, exist_ok=False)
    encoded = (json.dumps(report, indent=2) + '\n').encode()
    guide = (ROOT / 'docs/LL-HOST-DIAGNOSTICS.md').read_bytes()
    base = {'sourceZip': baseline_source.name, 'sha256': sha(baseline_source.read_bytes())}
    source = {name: (ROOT / name).read_bytes() for name in overlays}
    source.update({'BASELINE.json': (json.dumps(base, indent=2) + '\n').encode(),
                   'HOSTCHECK-DIFF.patch': ''.join(patch).encode(),
                   'LICENSE': (ROOT / 'LICENSE').read_bytes()})
    archive(a.output / 'MuoniumPlayerBedrock-0.14.7-levilamina-hostcheck1-source-overlay.zip', source)
    files = {PREFIX + 'MuoniumPlayerBedrock.dll': dll, 'TESTING.md': guide, 'VERIFICATION.json': encoded}
    archive(a.output / 'MuoniumPlayerBedrock-0.14.7-levilamina-hostcheck1.zip', files)
    (a.output / 'MuoniumPlayerBedrock.dll').write_bytes(dll)
    (a.output / 'TESTING.md').write_bytes(guide)
    (a.output / 'VERIFICATION.json').write_bytes(encoded)
    hashes = {p.name: sha(p.read_bytes()) for p in a.output.iterdir() if p.is_file()}
    (a.output / 'SHA256SUMS.txt').write_text(''.join(f'{v}  {k}\n' for k, v in sorted(hashes.items())), encoding='ascii')
    print(json.dumps({'output': str(a.output), 'dll': report['dll'], 'checksums': hashes}, indent=2))

if __name__ == '__main__':
    main()
