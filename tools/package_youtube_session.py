"""Build audited 0.16.4 candidates over frozen 0.16.3. No install or publish."""
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

VERSION = '0.16.4'
BASELINE = '0.16.3'
TASKS = ['youtubeStreamingTest', 'sourceDiagnosticsTest', 'playbackRecoveryTest', 'stable260Test']
YOUTUBE = 'com/muoniumplayer/core/ncm/music/YouTubeMusicService'


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ['baseline', 'evidence', 'audit-tools', 'output']:
        p.add_argument('--' + name, type=Path, required=True)
    a = p.parse_args()
    assert not a.output.exists(), 'Preserve completed releases; select a new directory'
    frozen = json.loads((a.evidence / 'frozen-0163.json').read_text('utf-8'))
    assert frozen and all(sha((a.baseline / n).read_bytes()) == d for n, d in frozen.items())
    previous = json.loads((a.baseline / 'VERIFICATION.json').read_text('utf-8'))
    assert previous['version'] == BASELINE
    for name in ['verification-followup.log', 'verification-final.log']:
        assert 'BUILD SUCCESSFUL' in (a.evidence / name).read_text('utf-8'), name
    assert 'build ok' in (a.evidence / 'native-build.log').read_text('utf-8')
    before = json.loads((a.evidence / 'native-before.json').read_text('utf-8'))
    native_now = {f.relative_to(ROOT).as_posix(): sha(f.read_bytes())
        for folder in ['src', 'include'] for f in (ROOT / folder).rglob('*') if f.is_file()}
    assert before.keys() == native_now.keys()
    changed_native = sorted(n for n in before if before[n] != native_now[n])
    assert changed_native == ['include/Local3Descriptions.h', 'include/Version.h'], changed_native
    descriptor = (ROOT / 'modmenu.settings.json').read_bytes()
    assert json.loads(descriptor)['plugin']['versions'] == [VERSION]
    assert sha(descriptor) in (ROOT / 'include/Local3Descriptions.h').read_text()
    for name in ['manifest.json', 'mod.json']:
        assert json.loads((ROOT / name).read_text('utf-8'))['version'] == VERSION
    vendor = ROOT / 'vendor/MuoniumPlayer-2.5.2'
    provenance = json.loads((vendor / 'SOURCE-MANIFEST.json').read_text('utf-8'))
    assert provenance['version'] == '2.6.1' and provenance['commit'] == '4011d03b535cc40fa54b2cb8324cd5405eb9d6e0'
    for item in provenance['files']:
        assert sha((vendor / item['path']).read_bytes()).lower() == item['sha256'].lower(), item['path']
    tasks = {}
    for name in TASKS:
        files = list((ROOT / 'jvm/build/test-results' / name).glob('TEST-*.xml'))
        assert files and all(f.stat().st_mtime > (a.evidence / 'native-before.json').stat().st_mtime for f in files), name
        suites = [ET.parse(f).getroot() for f in files]
        assert all(int(s.get('failures')) == int(s.get('errors')) == int(s.get('skipped')) == 0 for s in suites), name
        tasks[name] = dict(tests=sum(int(s.get('tests')) for s in suites),
            suites=[dict(name=s.get('name'), tests=int(s.get('tests'))) for s in suites])
    assert tasks['youtubeStreamingTest']['tests'] == 36
    red = ET.parse(a.evidence / 'account-red.xml').getroot()
    assert int(red.get('tests')) == int(red.get('failures')) == 3 and int(red.get('errors')) == 0
    green = ET.parse(ROOT / 'jvm/build/test-results/youtubeStreamingTest/TEST-com.muoniumplayer.bedrock.YouTubeAccountRouteTest.xml').getroot()
    assert int(green.get('tests')) == 8
    assert 'YOUTUBE_SESSION_PCM route=web-auth audibleSeconds=64,76 end=decoded fixture=owned' in green.findtext('system-out')
    native = json.loads((a.evidence / 'native-release/results.json').read_text('utf-8'))
    assert native['mode'] == 'release' and len(native['suites']) == 34
    assert all(s['exit'] == 0 for s in native['suites'].values())
    supplemental = {}
    for name, marker in [('unsupported-vanilla', 'unsupported-host refusal passed'), ('unsupported-ll', 'unsupported-load refusal')]:
        text = (a.evidence / (name + '.log')).read_text('utf-8')
        assert marker in text, name
        supplemental[name] = text.strip()
    host = (ROOT / 'jvm/build/libs/MuoniumBedrockHost.jar').read_bytes()
    jars = {f.name: f.read_bytes() for f in (ROOT / 'jvm/build/install/MuoniumBedrockHost/lib').glob('*.jar')}
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
        now = {n: z.read(n) for n in z.namelist() if n.endswith('.class')}
        original = {n: old.read(n) for n in old.namelist() if n.endswith('.class')}
        added = sorted(now.keys() - original.keys())
        assert not original.keys() - now.keys()
        assert added == [YOUTUBE + '$PublishedWebContext.class'], added
        delta = sorted(n for n in original if now[n] != original[n])
        behavioral = [n for n in delta if now[n] != original[n].replace(BASELINE.encode(), VERSION.encode())]
        assert behavioral and all(n.startswith(YOUTUBE) for n in behavioral), behavioral
        assert {int.from_bytes(d[6:8], 'big') for d in now.values()} == {65}
        assert {n: z.read(n) for n in z.namelist() if not n.endswith('.class')} == {n: old.read(n) for n in old.namelist() if not n.endswith('.class')}
        assert not any('BroadcastPcmQueue' in n for n in now), 'Do not ship BSChat drafts or stale classes'
        camera = {n: sha(d) for n, d in original.items() if n.startswith(('com/muoniumplayer/core/cine/', 'com/muoniumplayer/bedrock/Cinematic', 'com/muoniumplayer/bedrock/StageLyrics', 'com/muoniumplayer/bedrock/VoxelLyric'))}
        assert all(sha(now[n]) == d for n, d in camera.items())
    for n, data in jars.items():
        if n != 'MuoniumBedrockHost.jar':
            assert baseline[PREFIX + 'jvm/lib/' + n] == data, n
    verification = dict(version=VERSION, generatedUtc=datetime.now(timezone.utc).isoformat(),
        upstream=previous['upstream'], baseline=dict(version=BASELINE, fullArchive=baseline_name,
            sha256=frozen[baseline_name], verificationSha256=frozen['VERIFICATION.json']),
        playerEvidence=dict(version='0.16.3', confirmed=['initial fMP4 progressive audio prepared',
            'header and URL ranges returned HTTP 403 at offset 1048576', 'buffering persisted',
            'account library returned HTTP 400 INVALID_ARGUMENT', 'screenshot reported no usable audio source'],
            userObservation='same song plays past the failure position to completion in browser',
            external403Cause='not established', liveRecoveryAcceptance='pending'),
        jvmFreshTasks=tasks, nativeRelease=native, supplementalNative=supplemental, binaries=binaries,
        changedNativeSources=changed_native, productionClassDelta=delta, behaviorClassDelta=behavioral,
        addedProductionClasses=added, unchangedRuntimeAndLibraryJars=True, unchangedHostResourceFiles=True,
        unchangedVendorFiles=len(provenance['files']), nativeHookAndLayoutChanges=False,
        preservedCameraAndLyricClasses=camera, local3ExactDescriptorSha256=sha(descriptor),
        hostJarSha256=sha(host), hostClassMajorVersions=[65], jars=46,
        accountBaselineRed=dict(tests=3, failures=3, errors=0),
        accountRecoveryGreen=dict(tests=8, failures=0, actualPcmFixture='owned fragmented AAC',
            audibleSeconds=[64, 76], decodedToEof=True),
        baselineCoverage='Fresh YouTube, source diagnostics, playback recovery and stable audio suites; unchanged camera/render coverage inherited from frozen 0.16.3.',
        manualMinecraftAcceptance='pending; owned HTTP and AAC fixtures do not prove live YouTube recovery',
        knownLimitations=['WEB_REMIX may not supply direct AAC', 'persistent CDN 403 cause unproven',
            'browser playback is not implemented', 'BSChat drafts are not an integrated runtime feature'],
        frozen0163Files=frozen, installedGameModified=False, userConfigurationIncluded=False,
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
            for name in ['manifest.json', 'mod.json']:
                metadata = json.loads(files[PREFIX + name]); metadata['version'] = VERSION
                files[PREFIX + name] = encoded(metadata)
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
    assert all(sha((a.baseline / n).read_bytes()) == d for n, d in frozen.items())
    for name, data in [('VERIFICATION.json', encoded(verification)), ('PUBLIC-ASSET-AUDIT.json', encoded(audits)),
        ('UPDATE-' + VERSION + '.md', (ROOT / ('docs/UPDATE-' + VERSION + '.md')).read_bytes()),
        ('TESTING.md', (ROOT / 'TESTING.md').read_bytes())]:
        (a.output / name).write_bytes(data)
    sums = {p.name: sha(p.read_bytes()) for p in a.output.iterdir() if p.is_file()}
    (a.output / 'SHA256SUMS.txt').write_text(''.join(f'{d}  {n}\n' for n, d in sorted(sums.items())), encoding='ascii')
    print(json.dumps(dict(output=str(a.output), freshJvmTests=sum(t['tests'] for t in tasks.values()),
        nativeReleaseSuites=34, behaviorClassDelta=behavioral, checksums=sums, publicAudits=audits), indent=2))


if __name__ == '__main__':
    main()
