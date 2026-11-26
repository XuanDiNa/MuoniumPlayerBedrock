"""Build exclusive local 0.14.8 update ZIPs and a checked 0.14.7 source overlay.

Never writes to an installed instance or republishes/overwrites an old release.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import difflib
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from package_host_diagnostics import ROOT, PREFIX, archive, sha

VERSION = '0.14.8'
TARGETS = ['CinematicBridgeTests', 'CinematicLaminaApiTests',
           'CinematicActorReadTests', 'PlayerSettingsValueTests']
EXPECTED_JVM = {'AllSourceStreamingTest', 'BilibiliCdnFallbackTest',
                'StreamingTransportCoverageTest', 'StreamingTransitionTest',
                'StreamingSeekTest', 'CinematicReaderTest', 'CinematicStateTest'}


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ['baseline', 'evidence', 'audio-evidence', 'audit-tools', 'output']:
        p.add_argument('--' + name, type=Path, required=True)
    a = p.parse_args()
    frozen = {}
    for line in (a.baseline / 'SHA256SUMS.txt').read_text().splitlines():
        digest, name = line.split(None, 1)
        name = name.strip()
        assert sha((a.baseline / name).read_bytes()) == digest.upper(), name
        frozen[name] = digest.upper()
    native = json.loads((a.evidence / 'native-release/results.json').read_text())
    assert native['mode'] == 'release' and len(native['suites']) == 34
    assert all(v['exit'] == 0 for v in native['suites'].values())
    checks = {}
    for mode in ['debug', 'release']:
        for name in TARGETS:
            path = a.evidence / f'{name}-{mode}.log'
            text = path.read_text('utf-8-sig')
            assert 'passed' in text and 'error:' not in text, path
            checks[path.name] = sha(path.read_bytes())
    for name in ['vanilla-dll-green', 'll-dll-green', 'evidence-dll-green', 'diagnostic-dll-green']:
        path = a.evidence / (name + '.log')
        assert 'passed' in path.read_text('utf-8-sig'), path
        checks[path.name] = sha(path.read_bytes())
    assert 'BUILD SUCCESSFUL' in (a.audio_evidence / 'green.log').read_text('utf-8-sig')
    jvm = {}
    for task in ['test', 'cinematicHostTest']:
        suites = []
        for f in sorted((ROOT / 'jvm/build/test-results' / task).glob('TEST-*.xml')):
            t = ET.parse(f).getroot()
            assert all(int(t.attrib[k]) == 0 for k in ['failures', 'errors', 'skipped']), f
            suites.append({'name': t.attrib['name'], 'tests': int(t.attrib['tests'])})
        assert suites, task
        jvm[task] = {'tests': sum(s['tests'] for s in suites), 'suites': suites}
    assert {s['name'].rsplit('.', 1)[-1] for s in jvm['test']['suites']} == EXPECTED_JVM

    names = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT).decode().split('\0')
    source_files = {}
    changes, added, patch = [], [], []
    baseline_source = a.baseline / 'MuoniumPlayerBedrock-0.14.7-source.zip'
    with zipfile.ZipFile(baseline_source) as z:
        assert z.testzip() is None
        original_names = set(z.namelist())
        assert all((ROOT / n).is_file() for n in original_names if not n.endswith('/'))
        for name in names:
            if not name or not (ROOT / name).is_file():
                continue
            assert Path(name).parts[0] not in {'build', 'artifacts', 'releases', '.git', '.xmake', 'logs'}, name
            data = (ROOT / name).read_bytes()
            old = z.read(name) if name in original_names else b''
            if name in original_names and old.replace(b'\r\n', b'\n') == data.replace(b'\r\n', b'\n'):
                continue
            assert Path(name).parts[0] in {'src', 'include', 'tests', 'tools', 'docs', 'jvm'} or name in {'manifest.json', 'mod.json', 'modmenu.settings.json', 'xmake.lua'}, name
            source_files[name] = data
            (changes if name in original_names else added).append(name)
            patch += difflib.unified_diff(old.decode('utf-8-sig').splitlines(True), data.decode('utf-8-sig').splitlines(True),
                                         fromfile='a/' + name if name in original_names else '/dev/null', tofile='b/' + name)
        changed_native = {n for n in changes if n.startswith(('src/', 'include/'))}
        assert changed_native == {'include/LoaderPolicy.h', 'include/Local3Descriptions.h', 'include/NativeContracts.h',
                                  'include/NativeProfile.h', 'include/Runtime.h', 'include/Version.h',
                                  'src/CinematicBridge.cpp', 'src/Host.cpp', 'src/Plugin.cpp'}, changed_native
        guards = {n: sha(z.read(n)) for n in original_names if n.startswith('src/') and 'Guards' in n}
        assert guards and all(sha((ROOT / n).read_bytes()) == digest for n, digest in guards.items())

    descriptor = (ROOT / 'modmenu.settings.json').read_bytes()
    fields = json.loads(descriptor)
    assert fields['plugin']['versions'] == [VERSION]
    scale = next(f for f in fields['fields'] if f['path'] == '/stageScalePercent')
    assert (scale['min'], scale['max'], scale['step']) == (40, 300, 1)
    assert sha(descriptor) in (ROOT / 'include/Local3Descriptions.h').read_text()
    jar = (ROOT / 'jvm/build/libs/MuoniumBedrockHost.jar').read_bytes()
    lib = ROOT / 'jvm/build/install/MuoniumBedrockHost/lib'
    assert jar == (lib / 'MuoniumBedrockHost.jar').read_bytes()
    # The only changed runtime JAR is the player host; external libraries remain pinned.
    old_update = a.baseline / 'MuoniumPlayerBedrock-0.14.7-vanilla-mc26.45-26.51-26.52-26.60-update.zip'
    with zipfile.ZipFile(old_update) as z:
        for f in lib.glob('*.jar'):
            if f.name != 'MuoniumBedrockHost.jar':
                assert z.read(PREFIX + 'jvm/lib/' + f.name) == f.read_bytes(), f.name
    sys.path.insert(0, str(a.audit_tools.resolve()))
    from pe_audit import audit_runtime_dependencies
    from package_local import pe_exports
    binaries, binary_reports = {}, {}
    for edition, target in [('vanilla', 'MuoniumPlayerBedrock'), ('levilamina', 'MuoniumPlayerBedrockLL')]:
        dll = (ROOT / f'build/windows/x64/release/{target}.dll').read_bytes()
        imports, exports = audit_runtime_dependencies(dll), pe_exports(dll)
        assert {'MPB_GetVersion', 'MPB_GetStatus'} <= set(exports)
        assert ('ll_mod_load' in exports) == (edition == 'levilamina')
        assert ('levilamina.dll' in imports) == (edition == 'levilamina')
        assert not {'modmenube.dll', 'modmenubedrock.dll'} & set(imports)
        assert not any(n.startswith(('Fixture', 'Owner', 'WorkspaceBegin', 'WorkspaceMode', 'Frozen_')) for n in exports)
        binaries[edition] = dll
        binary_reports[edition] = dict(bytes=len(dll), sha256=sha(dll), imports=imports, exports=exports)
    verification = dict(version=VERSION, generatedUtc=datetime.now(timezone.utc).isoformat(),
        scope=['Scale117 settings parser', '26.60 actor slots', 'Reviewed LL shared-hook coexistence',
               'Same-representation Bilibili initial CDN fallback', 'Prior 409C hostfix2 retained'],
        binaries=binary_reports, hostJarSha256=sha(jar), descriptorSha256=sha(descriptor),
        nativeRelease={n: dict(exit=v['exit']) for n, v in native['suites'].items()},
        additionalDebugReleaseChecks=checks, jvmTasks=jvm,
        unchangedMachineCodeGuards=guards, sourceChanges=changes, addedSources=added,
        baselineSource=dict(name=baseline_source.name, sha256=sha(baseline_source.read_bytes())),
        previous0147FilesPreserved=frozen, manualMinecraftAcceptance='pending',
        liveBVProbe='BV1d4411N7zD anonymous two AAC-LC representations fully decoded about 317 seconds; user failure not reproduced',
        knownLimits=['Exact reviewed LL/PreLoader/Lamium builds only; unknown owners refuse',
                     'CDN fallback only before accepting an HTTP body; not every Bilibili failure explained',
                     'Update requires an existing complete 0.14.7 runtime; DLL alone is insufficient'],
        userConfigIncluded=False, otherPluginsChangedOrIncluded=False, gameModified=False, published=False)
    a.output.mkdir(parents=True, exist_ok=False)
    report = encoded(verification)
    guide = (ROOT / 'docs/UPDATE-0.14.8.md').read_bytes()
    source_files.update({'BASELINE.json': encoded(verification['baselineSource']), 'UPDATE-DIFF.patch': ''.join(patch).encode(),
                         'LICENSE': (ROOT / 'LICENSE').read_bytes(), 'VERIFICATION-0.14.8.json': report})
    archive(a.output / f'MuoniumPlayerBedrock-{VERSION}-source-overlay.zip', source_files)
    for edition, dll in binaries.items():
        manifest = json.loads((ROOT / 'manifest.json').read_text())
        metadata = json.loads((ROOT / 'mod.json').read_text())
        if edition == 'levilamina':
            manifest['type'] = 'native'
            manifest['optionalDependencies'] = [{'name': 'arke'}]
            metadata['minecraft'] = ['1.26.60.05']
        assert manifest['version'] == metadata['version'] == VERSION
        files = {PREFIX + 'MuoniumPlayerBedrock.dll': dll, PREFIX + 'jvm/lib/MuoniumBedrockHost.jar': jar,
                 PREFIX + 'manifest.json': encoded(manifest), PREFIX + 'mod.json': encoded(metadata),
                 PREFIX + 'modmenu.settings.json': descriptor, PREFIX + 'interface/modmenu.settings.json': descriptor,
                 PREFIX + 'docs/UPDATE-0.14.8.md': guide, 'TESTING.md': guide, 'VERIFICATION.json': report}
        files[PREFIX + 'PACKAGE-MANIFEST.json'] = encoded(dict(version=VERSION, edition=edition, package='update',
            files={n: dict(bytes=len(d), sha256=sha(d)) for n, d in files.items()}))
        assert not any(n.endswith('MuoniumPlayerBedrock.json') for n in files)
        suffix = 'mc26.60' if edition == 'levilamina' else 'mc26.45-26.51-26.52-26.60'
        archive(a.output / f'MuoniumPlayerBedrock-{VERSION}-{edition}-{suffix}-update.zip', files)
    (a.output / 'VERIFICATION.json').write_bytes(report)
    (a.output / 'TESTING.md').write_bytes(guide)
    sums = {f.name: sha(f.read_bytes()) for f in a.output.iterdir() if f.is_file()}
    (a.output / 'SHA256SUMS.txt').write_text(''.join(f'{v}  {n}\n' for n, v in sorted(sums.items())), encoding='ascii')
    print(json.dumps(dict(output=str(a.output), binaries=binary_reports,
                         jvmTests={n: t['tests'] for n, t in jvm.items()}, checksums=sums), indent=2))


if __name__ == '__main__':
    main()
