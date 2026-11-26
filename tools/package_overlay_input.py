"""Audit and package the 0.16.5 focus-input candidate. No install or publish."""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import io
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from package_upstream261 import ROOT, PREFIX, sha, encoded, read_archive, archive, manifest_check

VERSION, BASELINE = '0.16.5', '0.16.4'
INPUT_CLASSES = ('com/muoniumplayer/bedrock/HostInputState',
    'com/muoniumplayer/bedrock/BedrockOverlay', 'com/muoniumplayer/bedrock/OverlayDiagnostics',
    'com/muoniumplayer/compat/lwjgl2/Keyboard', 'com/muoniumplayer/compat/lwjgl2/Mouse')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['baseline', 'evidence', 'audit-tools', 'output']:
        parser.add_argument('--' + name, type=Path, required=True)
    a = parser.parse_args()
    assert not a.output.exists(), 'Preserve frozen releases; choose a new directory'
    frozen = json.loads((a.evidence / 'frozen-0164.json').read_text('utf-8'))
    assert frozen and all(sha((a.baseline / n).read_bytes()) == h for n, h in frozen.items())
    previous = json.loads((a.baseline / 'VERIFICATION.json').read_text('utf-8'))
    assert previous['version'] == BASELINE
    assert 'BUILD SUCCESSFUL' in (a.evidence / 'verification-final.log').read_text('utf-8')
    assert 'build ok' in (a.evidence / 'native-build.log').read_text('utf-8')
    before = json.loads((a.evidence / 'native-before.json').read_text('utf-8'))
    native_now = {p.relative_to(ROOT).as_posix(): sha(p.read_bytes())
        for folder in ['src', 'include'] for p in (ROOT / folder).rglob('*') if p.is_file()}
    assert before.keys() == native_now.keys()
    changed_native = sorted(n for n in before if before[n] != native_now[n])
    assert changed_native == ['include/Local3Descriptions.h', 'include/Version.h'], changed_native
    descriptor = (ROOT / 'modmenu.settings.json').read_bytes()
    assert json.loads(descriptor)['plugin']['versions'] == [VERSION]
    assert sha(descriptor) in (ROOT / 'include/Local3Descriptions.h').read_text()
    for n in ['manifest.json', 'mod.json']:
        assert json.loads((ROOT / n).read_text('utf-8'))['version'] == VERSION
    vendor = ROOT / 'vendor/MuoniumPlayer-2.5.2'
    provenance = json.loads((vendor / 'SOURCE-MANIFEST.json').read_text('utf-8'))
    assert provenance['version'] == '2.6.1'
    for item in provenance['files']:
        assert sha((vendor / item['path']).read_bytes()).lower() == item['sha256'].lower(), item['path']
    suites = []
    for p in sorted((ROOT / 'jvm/build/test-results/overlayInputTest').glob('TEST-*.xml')):
        assert p.stat().st_mtime > (a.evidence / 'native-before.json').stat().st_mtime, p.name
        suite = ET.parse(p).getroot()
        assert all(int(suite.get(k)) == 0 for k in ['failures', 'errors', 'skipped']), p.name
        suites.append(dict(name=suite.get('name'), tests=int(suite.get('tests'))))
    assert len(suites) == 10, suites
    red = ET.parse(a.evidence / 'input-red.xml').getroot()
    assert int(red.get('tests')) == int(red.get('failures')) == 1 and int(red.get('errors')) == 0
    assert 'held modifier after focus return' in red.find('testcase/failure').get('message')
    native = json.loads((a.evidence / 'native-release/results.json').read_text('utf-8'))
    assert native['mode'] == 'release' and len(native['suites']) == 34
    assert all(s['exit'] == 0 for s in native['suites'].values())
    host = (ROOT / 'jvm/build/libs/MuoniumBedrockHost.jar').read_bytes()
    jars = {p.name: p.read_bytes() for p in (ROOT / 'jvm/build/install/MuoniumBedrockHost/lib').glob('*.jar')}
    assert len(jars) == 46 and jars['MuoniumBedrockHost.jar'] == host
    sys.path.insert(0, str(a.audit_tools.resolve()))
    from pe_audit import PeImage, audit_runtime_dependencies
    binaries, dlls = {}, {}
    for edition, name in [('vanilla', 'MuoniumPlayerBedrock'), ('levilamina', 'MuoniumPlayerBedrockLL')]:
        data = (ROOT / ('build/windows/x64/release/' + name + '.dll')).read_bytes()
        imports = audit_runtime_dependencies(data)
        exports = sorted(n for n in PeImage(data).exports().values() if n is not None)
        assert {'MPB_GetVersion', 'MPB_GetStatus'} <= set(exports)
        assert ('ll_mod_load' in exports) == (edition == 'levilamina')
        assert not {'modmenube.dll', 'modmenubedrock.dll', 'lamium.dll'} & set(imports)
        assert not any(n.startswith(('Fixture', 'Owner', 'WorkspaceBegin', 'WorkspaceMode', 'Frozen_')) for n in exports)
        binaries[edition] = dict(bytes=len(data), sha256=sha(data), exports=exports, runtimeImports=imports)
        dlls[edition] = data
    baseline_name = f'MuoniumPlayerBedrock-{BASELINE}-vanilla-mc26.45-26.51-26.52-26.60-full.zip'
    baseline = read_archive(a.baseline / baseline_name)
    manifest_check(baseline)
    old_host = baseline[PREFIX + 'jvm/lib/MuoniumBedrockHost.jar']
    assert sha(old_host) == previous['hostJarSha256']
    with zipfile.ZipFile(io.BytesIO(host)) as z, zipfile.ZipFile(io.BytesIO(old_host)) as old:
        original = {n: old.read(n) for n in old.namelist() if n.endswith('.class')}
        now = {n: z.read(n) for n in z.namelist() if n.endswith('.class')}
        added = sorted(now.keys() - original.keys())
        assert not original.keys() - now.keys()
        assert added == ['com/muoniumplayer/bedrock/HostInputState$Snapshot.class'], added
        delta = sorted(n for n in original if now[n] != original[n])
        behavioral = [n for n in delta if now[n] != original[n].replace(BASELINE.encode(), VERSION.encode())]
        assert behavioral and all(n.removesuffix('.class') in INPUT_CLASSES for n in behavioral), behavioral
        assert {int.from_bytes(d[6:8], 'big') for d in now.values()} == {65}
        assert {n: z.read(n) for n in z.namelist() if not n.endswith('.class')} == {
            n: old.read(n) for n in old.namelist() if not n.endswith('.class')}
        assert not any('BroadcastPcmQueue' in n for n in now)
        preserved = {n: sha(d) for n, d in original.items() if n.startswith((
            'com/muoniumplayer/core/cine/', 'com/muoniumplayer/bedrock/Cinematic',
            'com/muoniumplayer/bedrock/StageLyrics', 'com/muoniumplayer/bedrock/VoxelLyric',
            'com/muoniumplayer/core/ncm/music/YouTube'))}
        assert all(sha(now[n]) == h for n, h in preserved.items())
    for n, data in jars.items():
        if n != 'MuoniumBedrockHost.jar':
            assert baseline[PREFIX + 'jvm/lib/' + n] == data, n
    verification = dict(version=VERSION, generatedUtc=datetime.now(timezone.utc).isoformat(),
        upstream=previous['upstream'], baseline=dict(version=BASELINE, fullArchive=baseline_name,
            sha256=frozen[baseline_name], verificationSha256=frozen['VERIFICATION.json']),
        playerReport='whole player page stops accepting input after browser/window handoff',
        evidenceLimit='live fault was not captured; a matching gate failure is reproduced with an owned HWND',
        jvmFreshTasks=dict(overlayInputTest=dict(tests=sum(s['tests'] for s in suites), suites=suites)),
        nativeRelease=native, binaries=binaries, changedNativeSources=changed_native,
        productionClassDelta=delta, behaviorClassDelta=behavioral, addedProductionClasses=added,
        unchangedRuntimeAndLibraryJars=True, unchangedHostResourceFiles=True,
        unchangedVendorFiles=len(provenance['files']), nativeHookAndLayoutChanges=False,
        preservedCameraLyricsAndYouTubeClasses=preserved, local3ExactDescriptorSha256=sha(descriptor),
        hostJarSha256=sha(host), hostClassMajorVersions=[65], jars=46,
        baselineInputRed=dict(tests=1, failures=1, errors=0, cause='one carried modifier blocks an unrelated mouse click'),
        coverage='owned HWND messages, carried modifier/button isolation, Escape, text, repeat suppression, polling, release/repress, modal input and page lifetime',
        globalInputInjection=False, manualMinecraftAcceptance='pending',
        knownLimitations=['YouTube still stops at 1:04 in the reported live session; no media-fetch changes',
            'no exact live input-state capture', 'BSChat drafts are not an integrated runtime feature'],
        frozen0164Files=frozen, installedGameModified=False, userConfigurationIncluded=False,
        otherPluginsIncluded=False, githubPublication=False)
    (ROOT / 'docs/PUBLIC-VERIFICATION.json').write_bytes(encoded(verification))
    a.output.mkdir(parents=True, exist_ok=False)
    tracked = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT).decode().split('\0')
    source = {n: (ROOT / n).read_bytes() for n in tracked if n and (ROOT / n).is_file()}
    assert not any(n.split('/')[0] in {'build', 'artifacts', 'releases', '.git'} for n in source)
    source_name = f'MuoniumPlayerBedrock-{VERSION}-source.zip'
    archive(a.output / source_name, source)
    for edition in ['vanilla', 'levilamina']:
        suffix = 'mc26.45-26.51-26.52-26.60' if edition == 'vanilla' else 'mc26.60'
        for kind in ['full', 'update']:
            files = read_archive(a.baseline / f'MuoniumPlayerBedrock-{BASELINE}-{edition}-{suffix}-{kind}.zip')
            manifest_check(files)
            del files[PREFIX + 'PACKAGE-MANIFEST.json']
            for n in list(files):
                if n.startswith(PREFIX + 'sources/MuoniumPlayerBedrock-'):
                    del files[n]
            for n in ['README.md', 'TESTING.md', 'modmenu.settings.json']:
                files[PREFIX + n] = (ROOT / n).read_bytes()
            files[PREFIX + 'interface/modmenu.settings.json'] = descriptor
            for n in ['manifest.json', 'mod.json']:
                meta = json.loads(files[PREFIX + n]); meta['version'] = VERSION
                files[PREFIX + n] = encoded(meta)
            files[PREFIX + 'MuoniumPlayerBedrock.dll'] = dlls[edition]
            files[PREFIX + 'jvm/lib/MuoniumBedrockHost.jar'] = host
            files[PREFIX + 'docs/UPDATE-' + VERSION + '.md'] = (ROOT / ('docs/UPDATE-' + VERSION + '.md')).read_bytes()
            if kind == 'full':
                files[PREFIX + 'sources/' + source_name] = (a.output / source_name).read_bytes()
            files['TESTING.md'] = (ROOT / 'TESTING.md').read_bytes()
            files['VERIFICATION.json'] = encoded(verification)
            files['INSTALL.md'] = files['INSTALL.md'].replace(BASELINE.encode(), VERSION.encode())
            candidate = json.loads(files['CANDIDATE.json']); candidate['version'] = VERSION
            files['CANDIDATE.json'] = encoded(candidate)
            assert not any(n.lower().endswith(('modmenube.dll', 'modmenubedrock.dll', '/config.json', '/muoniumplayerbedrock.json')) for n in files)
            files[PREFIX + 'PACKAGE-MANIFEST.json'] = encoded(dict(version=VERSION, edition=edition, package=kind,
                files={n: dict(bytes=len(d), sha256=sha(d)) for n, d in sorted(files.items())}))
            archive(a.output / f'MuoniumPlayerBedrock-{VERSION}-{edition}-{suffix}-{kind}.zip', files, True)
    from verify_public_assets import audit
    audits = {p.name: audit(p) for p in sorted(a.output.glob('*.zip'))}
    assert all(sha((a.baseline / n).read_bytes()) == h for n, h in frozen.items())
    for n, data in [('VERIFICATION.json', encoded(verification)), ('PUBLIC-ASSET-AUDIT.json', encoded(audits)),
        ('UPDATE-' + VERSION + '.md', (ROOT / ('docs/UPDATE-' + VERSION + '.md')).read_bytes()),
        ('TESTING.md', (ROOT / 'TESTING.md').read_bytes())]:
        (a.output / n).write_bytes(data)
    sums = {p.name: sha(p.read_bytes()) for p in a.output.iterdir() if p.is_file()}
    (a.output / 'SHA256SUMS.txt').write_text(''.join(f'{h}  {n}\n' for n, h in sorted(sums.items())), encoding='ascii')
    print(json.dumps(dict(output=str(a.output), freshJvmTests=sum(s['tests'] for s in suites),
        nativeReleaseSuites=34, behaviorClassDelta=behavioral, checksums=sums, publicAudits=audits), indent=2))


if __name__ == '__main__':
    main()
