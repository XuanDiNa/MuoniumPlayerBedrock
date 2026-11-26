"""Exclusive DLL-only 0.14.8 LL coexistence candidate; preserve frozen releases."""
from pathlib import Path
import argparse, difflib, json, sys, zipfile
from package_host_diagnostics import ROOT, PREFIX, archive, sha


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ['baseline', 'previous-source', 'evidence', 'audit-tools', 'output']:
        p.add_argument('--' + name, type=Path, required=True)
    a = p.parse_args()
    frozen = {}
    for line in (a.baseline / 'SHA256SUMS.txt').read_text().splitlines():
        digest, name = line.split(None, 1)
        name = name.strip()
        assert sha((a.baseline / name).read_bytes()) == digest, name
        frozen[name] = digest
    previous = json.loads((a.baseline / 'VERIFICATION.json').read_text('utf-8'))
    assert previous['version'] == '0.14.8'
    jar = (ROOT / 'jvm/build/libs/MuoniumBedrockHost.jar').read_bytes()
    assert sha(jar) == previous['hostJarSha256']
    with zipfile.ZipFile(a.previous_source) as z:
        originals = {n: z.read(n) for n in z.namelist() if not n.endswith('/')}
    source_overlay = a.baseline / 'MuoniumPlayerBedrock-0.14.8-source-overlay.zip'
    with zipfile.ZipFile(source_overlay) as z:
        base = json.loads(z.read('BASELINE.json'))
        assert base['sha256'] == sha(a.previous_source.read_bytes())
        originals.update({n: z.read(n) for n in z.namelist()
                          if n not in {'BASELINE.json', 'UPDATE-DIFF.patch', 'VERIFICATION-0.14.8.json'}})
    changed = {'include/CinematicLaminaHooks.h', 'src/CinematicBridge.cpp', 'src/CinematicLaminaHooks.cpp',
               'tests/CinematicBridgeTests.cpp', 'tests/CinematicLaminaApiTests.cpp'}
    for name, data in originals.items():
        if name not in changed:
            assert (ROOT / name).read_bytes().replace(b'\r\n', b'\n') == data.replace(b'\r\n', b'\n'), name
    assert all((ROOT / n).read_bytes() != originals[n] for n in changed)
    evidence = {}
    for name in ['bridge-release', 'api-release', 'CinematicBridgeTests-debug', 'CinematicLaminaApiTests-debug', 'dll-green']:
        text = (a.evidence / (name + '.log')).read_text('utf-8-sig')
        assert 'passed' in text and 'error:' not in text
        evidence[name] = text.strip()
    native = json.loads((a.evidence / 'native-release/results.json').read_text())
    assert len(native['suites']) == 34 and all(v['exit'] == 0 for v in native['suites'].values())
    proof = json.loads((a.evidence / 'lamium-static-proof.json').read_text('utf-8'))
    assert {v['detourRva'] for v in proof['registrations']} == {'0x2d850', '0x1ed710'}
    dll = (ROOT / 'build/windows/x64/release/MuoniumPlayerBedrockLL.dll').read_bytes()
    sys.path.insert(0, str(a.audit_tools.resolve()))
    from pe_audit import audit_runtime_dependencies
    from package_local import pe_exports
    imports, exports = audit_runtime_dependencies(dll), pe_exports(dll)
    assert {'MPB_GetVersion', 'MPB_GetStatus', 'll_mod_load', 'll_mod_enable', 'll_mod_disable'} <= set(exports)
    assert 'levilamina.dll' in imports and not {'modmenube.dll', 'lamium.dll', 'arke.dll'} & set(imports)
    report = dict(version='0.14.8', candidate='ll-camera2', logRevision='ll-coexist2',
        minecraft='1.26.60.05', loader='LeviLamina 26.60.7', dll=dict(bytes=len(dll), sha256=sha(dll), exports=exports, imports=imports),
        unchangedHostJarSha256=sha(jar), unchangedNativeMachineGuardsAndLayouts=True,
        prior0148FilesPreserved=frozen, changedSources=sorted(changed),
        checks=evidence, nativeRelease={n: dict(exit=v['exit']) for n, v in native['suites'].items()},
        lamiumTargetStaticProof=proof,
        manualAcceptance=dict(lamiumDisabled='user confirmed recovery in PID 32504', lamiumEnabled='pending new DLL test'),
        limits='Only the exact reviewed Lamium/LL/PreLoader combination; unknown chains still refuse. Original failed process ended before its chain could be captured.',
        gameModified=False, otherPluginsIncludedOrModified=False, configIncluded=False, published=False)
    encoded = (json.dumps(report, indent=2) + '\n').encode()
    guide = (ROOT / 'docs/LL-CAMERA-COEXIST2.md').read_bytes()
    a.output.mkdir(parents=True, exist_ok=False)
    patch = []
    for name in sorted(changed):
        patch += difflib.unified_diff(originals[name].decode('utf-8-sig').splitlines(True),
            (ROOT / name).read_text('utf-8-sig').splitlines(True), fromfile='a/' + name, tofile='b/' + name)
    source = {n: (ROOT / n).read_bytes() for n in sorted(changed | {'docs/LL-CAMERA-COEXIST2.md', 'tools/package_cinematic_coexist2.py'})}
    source.update({'LICENSE': (ROOT / 'LICENSE').read_bytes(), 'CAMERA-DIFF.patch': ''.join(patch).encode(),
        'BASELINE.json': (json.dumps(dict(source=a.previous_source.name, sourceSha256=sha(a.previous_source.read_bytes()),
             overlay=source_overlay.name, overlaySha256=sha(source_overlay.read_bytes())), indent=2) + '\n').encode()})
    archive(a.output / 'MuoniumPlayerBedrock-0.14.8-levilamina-camera2-source-overlay.zip', source)
    archive(a.output / 'MuoniumPlayerBedrock-0.14.8-levilamina-camera2.zip',
        {PREFIX + 'MuoniumPlayerBedrock.dll': dll, 'TESTING.md': guide, 'VERIFICATION.json': encoded})
    for name, data in {'MuoniumPlayerBedrock.dll': dll, 'TESTING.md': guide, 'VERIFICATION.json': encoded}.items():
        (a.output / name).write_bytes(data)
    sums = {f.name: sha(f.read_bytes()) for f in a.output.iterdir() if f.is_file()}
    (a.output / 'SHA256SUMS.txt').write_text(''.join(f'{v}  {n}\n' for n, v in sorted(sums.items())), encoding='ascii')
    print(json.dumps(dict(output=str(a.output), dllBytes=len(dll), dllSha256=sha(dll), nativeSuites=34, checksums=sums), indent=2))


if __name__ == '__main__':
    main()
