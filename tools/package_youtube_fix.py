"""Package a local YouTube-only 0.15.4 candidate. Never installs or publishes."""
from pathlib import Path
import argparse
import io
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone
from package_source_fixes import sha, encoded, read_archive, archive

ROOT = Path(__file__).resolve().parents[1]
VERSION = '0.15.4'
PREFIX = 'mods/MuoniumPlayerBedrock/'
TASKS = ['youtubeStreamingTest', 'sourceDiagnosticsTest', 'playbackRecoveryTest',
         'stable260Test', 'test', 'acousticsRegressionTest', 'stageRenderTest',
         'nativeD3D12RenderTest', 'worldSpeakerTest', 'cinematicHostTest',
         'cinematicMotionTest', 'roomProxyTest', 'roomProxyUiTest']


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--baseline', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--evidence', type=Path, required=True)
    p.add_argument('--audit-tools', type=Path, required=True)
    args = p.parse_args()
    assert not args.output.exists(), 'Preserve existing candidates'
    ev = args.evidence.resolve()
    start = (ev / 'native-before.json').stat().st_mtime
    for name in ['verification-final.log', 'targeted-final.log']:
        assert 'BUILD SUCCESSFUL' in (ev / name).read_text('utf-8'), name
    tests = {}
    for task in TASKS:
        reports = list((ROOT / 'jvm/build/test-results' / task).glob('TEST-*.xml'))
        assert reports, task
        rows = []
        for report in reports:
            assert report.stat().st_mtime >= start, ('stale report', report)
            t = ET.parse(report).getroot()
            assert all(int(t.get(n, 0)) == 0 for n in ['failures', 'errors', 'skipped']), report
            rows.append(dict(name=t.get('name'), tests=int(t.get('tests'))))
        tests[task] = dict(tests=sum(r['tests'] for r in rows), suites=rows)
    assert tests['youtubeStreamingTest']['tests'] == 23
    red = (ev / 'red-frozen-0153-final.log').read_text('utf-8')
    assert '23 tests found' in red and '9 tests failed' in red
    prior_verification = json.loads((args.baseline / 'VERIFICATION.json').read_text('utf-8'))
    assert sha((ev / 'baseline-0153-host.jar').read_bytes()) == prior_verification['hostJarSha256']
    native = json.loads((ev / 'native-release/results.json').read_text('utf-8'))
    assert len(native['suites']) == 34 and all(s['exit'] == 0 for s in native['suites'].values())
    assert (ev / 'native-release/results.json').stat().st_mtime >= start
    before_vendor = json.loads((ev / 'vendor-before.json').read_text('utf-8'))
    assert all(sha((ROOT / n).read_bytes()) == value for n, value in before_vendor.items())
    provenance = json.loads((ROOT / 'vendor/MuoniumPlayer-2.5.2/SOURCE-MANIFEST.json').read_text('utf-8'))
    assert provenance['commit'] == 'eb541684e9d681d8145f5f9d681cf31980646dc7'
    for entry in provenance['files']:
        assert sha((ROOT / 'vendor/MuoniumPlayer-2.5.2' / entry['path']).read_bytes()).lower() == entry['sha256'].lower()

    old_source = args.baseline / 'MuoniumPlayerBedrock-0.15.3-source.zip'
    with zipfile.ZipFile(old_source) as z:
        native_changed = {n for n in z.namelist() if n.startswith(('src/', 'include/'))
                          and (ROOT / n).is_file() and (ROOT / n).read_bytes() != z.read(n)}
        assert native_changed == {'include/Version.h', 'include/Local3Descriptions.h'}, native_changed
        for n in ['tools/prepare_jvm_sources.py',
                  'jvm/src/main/java/com/muoniumplayer/core/ncm/music/SourceFailureDetails.java',
                  'jvm/src/test/java/com/muoniumplayer/bedrock/QQPlaybackResponseTest.java']:
            assert (ROOT / n).read_bytes() == z.read(n), ('Unpublished QQ draft became active', n)
    assert not (ROOT / 'tools/qq_auth_overlay.py').exists()
    frozen = json.loads((ev / 'frozen-0153.json').read_text('utf-8'))
    assert all(sha((args.baseline / n).read_bytes()) == value for n, value in frozen.items())
    descriptor = (ROOT / 'modmenu.settings.json').read_bytes()
    assert json.loads(descriptor)['plugin']['versions'] == [VERSION]
    assert sha(descriptor) in (ROOT / 'include/Local3Descriptions.h').read_text()

    sys.path.insert(0, str(args.audit_tools.resolve()))
    from pe_audit import PeImage, audit_runtime_dependencies
    binaries, native_reports = {}, {}
    for edition, target in [('vanilla', 'MuoniumPlayerBedrock'), ('levilamina', 'MuoniumPlayerBedrockLL')]:
        data = (ROOT / f'build/windows/x64/release/{target}.dll').read_bytes()
        imports = audit_runtime_dependencies(data)
        exports = sorted(n for n in PeImage(data).exports().values() if n is not None)
        assert {'MPB_GetVersion', 'MPB_GetStatus'} <= set(exports)
        assert ('ll_mod_load' in exports) == (edition == 'levilamina')
        assert not {'modmenube.dll', 'modmenubedrock.dll'} & imports.keys()
        assert not any(n.startswith(('Fixture', 'Owner', 'WorkspaceBegin', 'WorkspaceMode', 'Frozen_')) for n in exports)
        binaries[edition] = data
        native_reports[edition] = dict(bytes=len(data), sha256=sha(data), exports=exports, imports=imports)
    jars = {p.name: p.read_bytes() for p in (ROOT / 'jvm/build/install/MuoniumBedrockHost/lib').glob('*.jar')}
    assert len(jars) == 46 and jars['MuoniumBedrockHost.jar'] == (ROOT / 'jvm/build/libs/MuoniumBedrockHost.jar').read_bytes()
    with zipfile.ZipFile(io.BytesIO(jars['MuoniumBedrockHost.jar'])) as z:
        assert {int.from_bytes(z.read(n)[6:8], 'big') for n in z.namelist() if n.endswith('.class')} == {65}
        final_classes = {n: sha(z.read(n)) for n in z.namelist() if n.endswith('.class')}
    with zipfile.ZipFile(ev / 'broad-regression-host.jar') as z:
        broad_classes = {n: sha(z.read(n)) for n in z.namelist() if n.endswith('.class')}
    class_delta = sorted(n for n in set(final_classes) | set(broad_classes)
                         if final_classes.get(n) != broad_classes.get(n))
    assert class_delta and all(n.startswith('com/muoniumplayer/core/ncm/music/YouTubeMusicService')
                               for n in class_delta), ('Unrelated runtime class changed after broad regression', class_delta)
    snapshot = json.loads((ev / 'broad-class-snapshot.json').read_text('utf-8'))
    assert snapshot['classes'] == broad_classes
    verification = dict(version=VERSION, generatedUtc=datetime.now(timezone.utc).isoformat(),
        upstream=provenance['commit'], jvmTasks=tests, nativeRelease=native,
        binaries=native_reports, hostJarSha256=sha(jars['MuoniumBedrockHost.jar']),
        descriptorSha256=sha(descriptor), nativeMetadataChanged=sorted(native_changed),
        nativeHooksChanged=False, vendorUnchanged=True, qqDraftIncluded=False,
        incident=dict(archiveSha256=json.loads((ev / 'input.json').read_text('utf-8'))['archiveSha256'],
            loggedNativeAndJvmVersion='0.15.3', gamePid=27548,
            jvmLog='jvm-private-27548-391273031.log',
            loadedHostJarSha256=prior_verification['hostJarSha256'],
            recordedCause='Repeated HTTP 403; shrink/refresh/wait at offset 1048576; capture ends before terminal event',
            userReported='Normal playback and late seeking both affected',
            external403RootCause='Not established'),
        regression=dict(baseline='0.15.3 frozen host JAR', tests=23, failed=9,
            fixedTests=23, fixedFailed=0, liveYouTubeAccountUsed=False),
        regressionScope=dict(
            broadJarSha256=sha((ev / 'broad-regression-host.jar').read_bytes()),
            broadClasspathVerified=True, finalChangedClasses=class_delta,
            unchangedClasses=len(final_classes)-len(class_delta),
            finalTargetedTasks=['youtubeStreamingTest','sourceDiagnosticsTest','playbackRecoveryTest','stable260Test'],
            note='Broad run preceded final query-range compatibility adjustment. Only YouTube classes changed; final targeted tasks cover the delivered code.'),
        manualMinecraftAcceptance='pending', externalYouTubeAcceptance='pending',
        installedGameModified=False, otherPluginsIncluded=False, githubPublished=False,
        retainedPriorFiles=frozen)
    (ROOT / 'docs/PUBLIC-VERIFICATION.json').write_bytes(encoded(verification))
    names = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT).decode().split('\0')
    sources = {n: (ROOT / n).read_bytes() for n in names if n and (ROOT / n).is_file()}
    assert not any(Path(n).parts[0] in {'build', 'artifacts', 'releases', '.git'} for n in sources)
    with zipfile.ZipFile(old_source) as z:
        old = {n: z.read(n) for n in z.namelist() if not n.endswith('/')}
    delta = sorted(n for n in set(old) | set(sources) if old.get(n) != sources.get(n))
    expected = {'README.md', 'TESTING.md', 'docs/PUBLIC-VERIFICATION.json', 'docs/STREAMING-AUDIO.md',
        'docs/UPDATE-0.15.4.md', 'include/Version.h', 'include/Local3Descriptions.h',
        'jvm/src/main/java/com/muoniumplayer/MuoniumPlayerClient.java',
        'jvm/src/test/java/com/muoniumplayer/bedrock/YouTubeWindowRecoveryTest.java',
        'manifest.json', 'mod.json', 'modmenu.settings.json', 'tools/package_youtube_fix.py',
        'tools/youtube_stream_overlay.py'}
    assert set(delta) == expected, ('Unexpected source changes', set(delta) ^ expected)
    verification['sourceDeltaFrom0153'] = delta
    (ROOT / 'docs/PUBLIC-VERIFICATION.json').write_bytes(encoded(verification))
    sources['docs/PUBLIC-VERIFICATION.json'] = encoded(verification)
    args.output.mkdir(parents=True, exist_ok=False)
    source_name = f'MuoniumPlayerBedrock-{VERSION}-source.zip'
    archive(args.output / source_name, sources)
    source_bytes = (args.output / source_name).read_bytes()
    audits = {}
    from verify_public_assets import audit
    for edition in ['vanilla', 'levilamina']:
        suffix = 'mc26.45-26.51-26.52-26.60' if edition == 'vanilla' else 'mc26.60'
        for kind in ['full', 'update']:
            baseline_name = f'MuoniumPlayerBedrock-0.15.3-{edition}-{suffix}-{kind}.zip'
            files = read_archive(args.baseline / baseline_name)
            del files[PREFIX + 'PACKAGE-MANIFEST.json']
            for n, data in jars.items():
                if n != 'MuoniumBedrockHost.jar':
                    assert files[PREFIX + 'jvm/lib/' + n] == data, ('Unexpected dependency change', n)
                files[PREFIX + 'jvm/lib/' + n] = data
            files[PREFIX + 'MuoniumPlayerBedrock.dll'] = binaries[edition]
            manifest = json.loads((ROOT / 'manifest.json').read_text())
            metadata = json.loads((ROOT / 'mod.json').read_text())
            if edition == 'levilamina':
                manifest['type'] = 'native'
                manifest['optionalDependencies'] = [dict(name='arke')]
                metadata['minecraft'] = ['1.26.60.05']
            assert manifest['version'] == metadata['version'] == VERSION
            files[PREFIX + 'manifest.json'] = encoded(manifest)
            files[PREFIX + 'mod.json'] = encoded(metadata)
            for n in ['README.md', 'BUILDING.md', 'TESTING.md', 'modmenu.settings.json']:
                files[PREFIX + n] = (ROOT / n).read_bytes()
            files[PREFIX + 'interface/modmenu.settings.json'] = descriptor
            for n in ['STREAMING-AUDIO.md', 'UPDATE-0.15.4.md']:
                files[PREFIX + 'docs/' + n] = (ROOT / 'docs' / n).read_bytes()
            if kind == 'full':
                del files[PREFIX + 'sources/MuoniumPlayerBedrock-0.15.3-source.zip']
                files[PREFIX + 'sources/' + source_name] = source_bytes
            files['TESTING.md'] = (ROOT / 'TESTING.md').read_bytes()
            files['VERIFICATION.json'] = encoded(verification)
            files['INSTALL.md'] = (f'Exit Minecraft, extract into the instance root, preserve settings/accounts/playlists.\nEdition: {edition}; version: {VERSION}; package: {kind}.\n'
                + ('Java 21 runtime is included.\n' if kind == 'full' else 'Requires an existing complete Java 21 player runtime.\n')
                + 'Keep the installed ModMenuBE; it is not bundled. Test the previously interrupted YouTube track through its end. Gameplay acceptance is pending.\n').encode()
            files['CANDIDATE.json'] = encoded(dict(version=VERSION, edition=edition, package=kind,
                manualMinecraftAcceptance='pending', externalYouTubeAcceptance='pending',
                menuIncluded=False, userConfigIncluded=False))
            assert not any('/logs/' in n or '/cache/' in n or n.lower().endswith(('modmenube.dll', 'modmenubedrock.dll')) for n in files)
            files[PREFIX + 'PACKAGE-MANIFEST.json'] = encoded(dict(version=VERSION, edition=edition, package=kind,
                files={n: dict(bytes=len(data), sha256=sha(data)) for n, data in sorted(files.items())}))
            dest = args.output / f'MuoniumPlayerBedrock-{VERSION}-{edition}-{suffix}-{kind}.zip'
            archive(dest, files, True)
            audits[dest.name] = audit(dest)
            print('Verified ' + dest.name, flush=True)
    audits[source_name] = audit(args.output / source_name)
    (args.output / 'VERIFICATION.json').write_bytes(encoded(verification))
    (args.output / 'PUBLIC-ASSET-AUDIT.json').write_bytes(encoded(audits))
    (args.output / 'TESTING.md').write_bytes((ROOT / 'TESTING.md').read_bytes())
    (args.output / 'UPDATE-0.15.4.md').write_bytes((ROOT / 'docs/UPDATE-0.15.4.md').read_bytes())
    assert all(sha((args.baseline / n).read_bytes()) == value for n, value in frozen.items())
    sums = {p.name: sha(p.read_bytes()) for p in args.output.iterdir() if p.is_file()}
    (args.output / 'SHA256SUMS.txt').write_text(''.join(f'{value}  {name}\n' for name, value in sorted(sums.items())), encoding='ascii')
    print(json.dumps(dict(jvmTests={k: v['tests'] for k, v in tests.items()}, nativeSuites=34,
        preservedPriorFiles=len(frozen), sourceChangedFiles=len(delta), sha256=sums), indent=2))


if __name__ == '__main__':
    main()
