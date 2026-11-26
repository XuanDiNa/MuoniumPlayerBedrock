"""Package 0.15.2 authenticated source-playback fixes from verified stable sources, fresh builds and a frozen runtime.

No installation or publication. Use a new output directory for every candidate.
"""
from pathlib import Path, PurePosixPath
from datetime import datetime, timezone
import argparse
import hashlib
import io
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = '0.15.2'
PIN = 'eb541684e9d681d8145f5f9d681cf31980646dc7'
PREFIX = 'mods/MuoniumPlayerBedrock/'
TASKS = ['stable260Test', 'test', 'acousticsRegressionTest', 'stageRenderTest',
         'nativeD3D12RenderTest', 'playbackRecoveryTest', 'worldSpeakerTest',
         'cinematicHostTest', 'cinematicMotionTest', 'roomProxyTest', 'roomProxyUiTest', 'sourceDiagnosticsTest']


def sha(data):
    return hashlib.sha256(data).hexdigest().upper()


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def read_archive(path):
    with zipfile.ZipFile(path) as z:
        assert z.testzip() is None, path.name
        names = z.namelist()
        assert len(names) == len(set(n.casefold() for n in names))
        for n in names:
            p = PurePosixPath(n)
            assert not p.is_absolute() and '..' not in p.parts and '\\' not in n and ':' not in n, n
        return {n: z.read(n) for n in names if not n.endswith('/')}


def manifest_check(files):
    name = PREFIX + 'PACKAGE-MANIFEST.json'
    entries = json.loads(files[name])['files']
    assert set(entries) == set(files) - {name}
    for n, value in entries.items():
        assert value == dict(bytes=len(files[n]), sha256=sha(files[n])), n


def archive(path, files, distribution=False):
    assert not path.exists(), path.name
    for n in files:
        p = PurePosixPath(n)
        assert not p.is_absolute() and '..' not in p.parts and '\\' not in n and ':' not in n, n
    assert len(files) == len(set(n.casefold() for n in files))
    with zipfile.ZipFile(path, 'x', zipfile.ZIP_DEFLATED) as z:
        for n, data in sorted(files.items()):
            z.writestr(n, data)
    actual = read_archive(path)
    assert actual == files, path.name
    if distribution:
        manifest_check(actual)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--baseline-full', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--audit-tools', type=Path, required=True)
    a = parser.parse_args()
    assert not a.output.exists(), 'Preserve existing releases; use a new output directory'
    evidence = a.evidence.resolve()
    baseline_sha = sha(a.baseline_full.read_bytes())
    assert baseline_sha == '6FEEB74E19001DF8913FC6CA6B104142C9E4C04313307CB9D8B68F994CF4DD9A'
    base = read_archive(a.baseline_full)
    manifest_check(base)
    releases = a.baseline_full.parent.parent
    frozen = {f.relative_to(releases).as_posix(): sha(f.read_bytes())
              for folder in releases.iterdir() if folder.is_dir() and folder.name.startswith(('0.14.8', '0.15.0', '0.15.1'))
              for f in folder.iterdir() if f.is_file()}
    assert frozen

    assert 'BUILD SUCCESSFUL' in (evidence / 'verification-final.log').read_text(encoding='utf-8')
    # Reject test reports from the previous candidate. All suites must be fresh.
    evidence_start = (evidence / 'native-before.json').stat().st_mtime
    jvm = {}
    for task in TASKS:
        suites = []
        for f in sorted((ROOT / 'jvm/build/test-results' / task).glob('TEST-*.xml')):
            assert f.stat().st_mtime >= evidence_start, ('stale test report', f.name)
            t = ET.parse(f).getroot()
            assert int(t.attrib['failures']) == int(t.attrib['errors']) == int(t.attrib['skipped']) == 0, f.name
            suites.append(dict(name=t.attrib['name'], tests=int(t.attrib['tests'])))
        assert suites, task
        jvm[task] = dict(tests=sum(s['tests'] for s in suites), suites=suites)
    names = {s['name'].rsplit('.', 1)[-1] for result in jvm.values() for s in result['suites']}
    assert {'Stable260RegressionTest', 'Stable260RenderTest', 'AutomixLearnedEqTest',
            'DesktopLyricAlignTest', 'FontPickerGeometryTest', 'FontPickerMotionTest',
            'AllSourceStreamingTest', 'StreamingTransportCoverageTest', 'CinematicRoutePlannerTest',
            'CinematicMotionRegressionTest', 'CinematicHostIntegrationTest', 'NativeD3D12RenderTest',
            'RoomLatencyTest', 'PlaybackRetryTest', 'HostWorkspaceTest', 'QQPlaybackResponseTest',
            'SourceHttpFailureTest', 'SpotifySearchFeedbackTest', 'SourceFailureDetailsTest', 'BilibiliPlaybackLoginTest'} <= names
    native = json.loads((evidence / 'native-release/results.json').read_text())
    assert (evidence / 'native-release/results.json').stat().st_mtime >= evidence_start
    assert native['mode'] == 'release' and len(native['suites']) == 34
    assert all(s['exit'] == 0 for s in native['suites'].values())
    supplemental = {}
    for name, marker in [('ll-api.log', '11 checks passed'),
                         ('host-diagnostics.log', 'Real LL refusal diagnostics'),
                         ('host-evidence.log', 'Real LL refusal saved'),
                         ('unsupported-ll.log', 'unsupported-load refusal'),
                         ('unsupported-vanilla.log', 'unsupported-host refusal passed')]:
        text = (evidence / name).read_text(encoding='utf-8').strip()
        assert marker in text, name
        supplemental[name] = text

    before = json.loads((evidence / 'native-before.json').read_text())
    changed = {n for n, digest in before.items() if sha((ROOT / n).read_bytes()) != digest}
    assert changed == {'include/Version.h', 'include/Local3Descriptions.h'}, changed
    current_native = {f.relative_to(ROOT).as_posix(): sha(f.read_bytes())
                      for folder in ['src', 'include'] for f in (ROOT / folder).iterdir() if f.is_file()}
    assert current_native.keys() == before.keys()
    unchanged = {n: digest for n, digest in current_native.items() if n not in changed}
    sdk = {}
    with zipfile.ZipFile(io.BytesIO(base[PREFIX + 'sources/MuoniumPlayerBedrock-0.14.7-source.zip'])) as original:
        for f in (ROOT / 'include/sdk').rglob('*'):
            if f.is_file():
                name = f.relative_to(ROOT).as_posix()
                assert f.read_bytes() == original.read(name), name
                sdk[name] = sha(f.read_bytes())
    descriptor = (ROOT / 'modmenu.settings.json').read_bytes()
    assert json.loads(descriptor)['plugin']['versions'] == [VERSION]
    assert sha(descriptor) in (ROOT / 'include/Local3Descriptions.h').read_text()
    provenance = json.loads((ROOT / 'vendor/MuoniumPlayer-2.5.2/SOURCE-MANIFEST.json').read_text('utf-8'))
    assert provenance['commit'] == PIN and provenance['tag'] == provenance['version'] == '2.6.0'
    for item in provenance['files']:
        assert sha((ROOT / 'vendor/MuoniumPlayer-2.5.2' / item['path']).read_bytes()).lower() == item['sha256'].lower(), item['path']

    sys.path.insert(0, str(a.audit_tools.resolve()))
    from pe_audit import PeImage, audit_runtime_dependencies
    binaries, binary_reports = {}, {}
    for edition, target in [('vanilla', 'MuoniumPlayerBedrock'), ('levilamina', 'MuoniumPlayerBedrockLL')]:
        data = (ROOT / f'build/windows/x64/release/{target}.dll').read_bytes()
        imports = audit_runtime_dependencies(data)
        exports = sorted(n for n in PeImage(data).exports().values() if n is not None)
        assert {'MPB_GetVersion', 'MPB_GetStatus'} <= set(exports)
        assert ('ll_mod_load' in exports) == (edition == 'levilamina')
        assert not {'modmenube.dll', 'modmenubedrock.dll'} & imports.keys()
        assert not any(n.startswith(('Fixture', 'Owner', 'WorkspaceBegin', 'WorkspaceMode', 'Frozen_')) for n in exports)
        binaries[edition] = data
        binary_reports[edition] = dict(bytes=len(data), sha256=sha(data), exports=exports, runtimeImports=imports)
    jars = {f.name: f.read_bytes() for f in (ROOT / 'jvm/build/install/MuoniumBedrockHost/lib').glob('*.jar')}
    assert len(jars) == 46
    assert jars['MuoniumBedrockHost.jar'] == (ROOT / 'jvm/build/libs/MuoniumBedrockHost.jar').read_bytes()
    with zipfile.ZipFile(io.BytesIO(jars['MuoniumBedrockHost.jar'])) as host:
        major_versions = sorted({int.from_bytes(host.read(n)[6:8], 'big')
                                 for n in host.namelist() if n.endswith('.class')})
        assert major_versions == [65], 'The Bedrock host must remain Java 21 bytecode'
    for n, data in jars.items():
        if n != 'MuoniumBedrockHost.jar':
            assert base[PREFIX + 'jvm/lib/' + n] == data, n
    verification = dict(version=VERSION, generatedUtc=datetime.now(timezone.utc).isoformat(),
        upstream=dict(commit=PIN, tag='2.6.0', branch='main', stableRelease=True, curatedFiles=len(provenance['files']), changedFiles=116),
        suppliedJarReference=json.loads((evidence / 'supplied-jar-audit.json').read_text('utf-8')),
        jvmTasks=jvm, nativeRelease=native, supplementalNative=supplemental, binaries=binary_reports,
        nativeUnchangedFiles=unchanged, unchangedSdkFiles=sdk,
        nativeMetadataChanged={n: current_native[n] for n in sorted(changed)},
        sourceAuthenticationFixes=dict(bilibili='Saved account/device cookies for view and playurl; no anonymous retry',
            qq='Authenticated comm envelope, signed POST UrlGetVkey',
            genuineRejections='HTTP 412 and QQ permissions can still fail; authenticated external playback unverified'),
        sourceAuthenticationEvidence=dict(
            archiveSha256=json.loads((evidence / 'input.json').read_text('utf-8'))['sha256'],
            reproducibleRed=dict(tests=18, expectedFailures=3),
            overlays=json.loads((evidence / 'source-overlay-audit.json').read_text('utf-8')),
            anonymousComparison=json.loads((evidence / 'qq-public-protocol-comparison.json').read_text('utf-8')),
            externalAuthenticatedPlayback='not tested; player credentials were not used'),
        nativeHookAndLayoutChanges=False, llCameraRevision='ll-coexist2',
        local3ExactDescriptorSha256=sha(descriptor), hostJarSha256=sha(jars['MuoniumBedrockHost.jar']),
        hostClassMajorVersions=major_versions, jars=46,
        runtimeSource=dict(archive=a.baseline_full.name, sha256=baseline_sha),
        retainedPriorFiles=frozen,
        graphicsTests='Actual Skija/D3D11/D3D12 in owned windows; not Minecraft gameplay',
        manualMinecraftAcceptance='pending for these rebuilt DLL/JAR files, including LL/Lamium coexistence',
        boundaries=['Entity-depth lyric occlusion remains deferred',
                    'Codec/container fallback remains; exhausted HTTP rejections stop duplicate whole-file downloads; no universal CDN/account availability claim',
                    'Remote upstream announcements and sponsor prompts remain disabled',
                    'Shortcuts unbound and next-track prefetch/persistent media cache off by default'],
        installedGameModified=False, otherPluginsIncluded=False, userConfigurationIncluded=False, githubPublication=False)
    (ROOT / 'docs/PUBLIC-VERIFICATION.json').write_bytes(encoded(verification))
    a.output.mkdir(parents=True, exist_ok=False)
    names = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT).decode().split('\0')
    sources = {n: (ROOT / n).read_bytes() for n in names if n and (ROOT / n).is_file()}
    assert not any(PurePosixPath(n).parts[0] in {'build', 'artifacts', 'releases', '.git'} for n in sources)
    source_name = f'MuoniumPlayerBedrock-{VERSION}-source.zip'
    archive(a.output / source_name, sources)
    source_data = (a.output / source_name).read_bytes()
    retained = {n: data for n, data in base.items() if n.startswith(tuple(PREFIX + p for p in
                ['runtime/', 'tunnel/', 'maintenance/', 'sources/openal-soft-']))}
    assert PREFIX + 'runtime/bin/javaw.exe' in retained and PREFIX + 'runtime/lib/modules' in retained
    assert PREFIX + 'tunnel/cloudflared.exe' in retained
    common = {PREFIX + 'jvm/lib/' + n: data for n, data in jars.items()}
    for n in ['LICENSE', 'NOTICE', 'README.md', 'BUILDING.md', 'TESTING.md', 'modmenu.json', 'modmenu.settings.json']:
        common[PREFIX + n] = (ROOT / n).read_bytes()
    common[PREFIX + 'interface/modmenu.settings.json'] = descriptor
    for f in (ROOT / 'licenses').rglob('*'):
        if f.is_file():
            common[PREFIX + f.relative_to(ROOT).as_posix()] = f.read_bytes()
    notices = ROOT / 'vendor/MuoniumPlayer-2.5.2/muonium-common/src/main/resources/META-INF/licenses/public-fonts'
    for f in notices.iterdir():
        if f.is_file():
            common[PREFIX + 'licenses/public-fonts/' + f.name] = f.read_bytes()
    for n in ['INSTALL.md', 'modmenube-migration.md', 'UPDATE-0.15.0.md', 'UPDATE-0.15.1.md', 'UPDATE-0.15.2.md', 'SOURCE-FAILURES-20261122.md', 'SOURCE-AUTH-20261122.md', 'UPSTREAM-260.md', 'DEPENDENCIES.md', 'STREAMING-AUDIO.md',
              'ROOM-PROXY.md', 'LL-CAMERA-COEXIST2.md', 'LL-HOST-DIAGNOSTICS.md', 'LL-HOST-EVIDENCE.md']:
        common[PREFIX + 'docs/' + n] = (ROOT / 'docs' / n).read_bytes()
    for f in (ROOT / 'docs/images').glob('*.png'):
        common[PREFIX + 'docs/images/' + f.name] = f.read_bytes()
    common['TESTING.md'] = common[PREFIX + 'TESTING.md']
    common['VERIFICATION.json'] = encoded(verification)
    for edition, dll in binaries.items():
        manifest = json.loads((ROOT / 'manifest.json').read_text())
        metadata = json.loads((ROOT / 'mod.json').read_text())
        if edition == 'levilamina':
            manifest['type'] = 'native'
            manifest['optionalDependencies'] = [dict(name='arke')]
            metadata['minecraft'] = ['1.26.60.05']
        assert manifest['version'] == metadata['version'] == VERSION
        suffix = 'mc26.45-26.51-26.52-26.60' if edition == 'vanilla' else 'mc26.60'
        for kind in ['full', 'update']:
            files = dict(common)
            files[PREFIX + 'MuoniumPlayerBedrock.dll'] = dll
            files[PREFIX + 'manifest.json'] = encoded(manifest)
            files[PREFIX + 'mod.json'] = encoded(metadata)
            if kind == 'full':
                files.update(retained)
                files[PREFIX + 'sources/' + source_name] = source_data
            files['INSTALL.md'] = ('Exit Minecraft, extract into the instance root, preserve settings/accounts/playlists.\n'
                f'Edition: {edition}; version: {VERSION}; package: {kind}.\n'
                + ('Java 21 runtime is included.\n' if kind == 'full' else 'An existing complete Java 21 player runtime is required.\n')
                + 'Install ModMenuBE separately. See TESTING.md. Gameplay acceptance is pending.\n').encode('utf-8')
            files['CANDIDATE.json'] = encoded(dict(version=VERSION, edition=edition, package=kind,
                upstreamTag='2.6.0', upstreamCommit=PIN, manualMinecraftAcceptance='pending',
                menuIncluded=False, userConfigIncluded=False))
            assert all(n.startswith(PREFIX) for n in files if n.startswith('mods/'))
            assert not any(n.lower().endswith(('modmenube.dll', 'modmenubedrock.dll', 'muoniumplayerbedrock.json', '/config.json')) for n in files)
            assert not any('/logs/' in n or '/cache/' in n for n in files)
            files[PREFIX + 'PACKAGE-MANIFEST.json'] = encoded(dict(version=VERSION, edition=edition, package=kind,
                files={n: dict(bytes=len(d), sha256=sha(d)) for n, d in sorted(files.items())}))
            archive(a.output / f'MuoniumPlayerBedrock-{VERSION}-{edition}-{suffix}-{kind}.zip', files, True)
    from verify_public_assets import audit
    public_audits = {p.name: audit(p) for p in sorted(a.output.glob('*.zip'))}
    assert frozen == {n: sha((releases / n).read_bytes()) for n in frozen}, 'A retained release changed'
    (a.output / 'VERIFICATION.json').write_bytes(encoded(verification))
    (a.output / 'PUBLIC-ASSET-AUDIT.json').write_bytes(encoded(public_audits))
    (a.output / 'TESTING.md').write_bytes(common['TESTING.md'])
    (a.output / 'UPDATE-0.15.2.md').write_bytes((ROOT / 'docs/UPDATE-0.15.2.md').read_bytes())
    sums = {f.name: dict(bytes=f.stat().st_size, sha256=sha(f.read_bytes())) for f in a.output.iterdir() if f.is_file()}
    (a.output / 'SHA256SUMS.txt').write_text(''.join(f'{v["sha256"]}  {n}\n' for n, v in sorted(sums.items())), encoding='ascii')
    print(json.dumps(dict(packages=sums, jvmTests={n: t['tests'] for n, t in jvm.items()},
                         nativeReleaseSuites=34, retainedPriorFiles=len(frozen), publicAudits=public_audits), indent=2))


if __name__ == '__main__':
    main()
