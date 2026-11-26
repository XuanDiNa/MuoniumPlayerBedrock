"""Audit and package the 0.16.7 background YouTube browser candidate. No install or publish."""
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

VERSION, BASELINE = '0.16.7', '0.16.6'
AUDIO_CLASSES = ('com/muoniumplayer/bedrock/BrowserAudioSession',
    'com/muoniumplayer/bedrock/YouTubeBrowserAudio')
TASKS = ['youtubeBrowserTest', 'progressiveRegressionTest', 'youtubeStreamingTest',
    'playbackRecoveryTest', 'overlayInputTest', 'sourceDiagnosticsTest', 'stable260Test']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['baseline', 'evidence', 'audit-tools', 'output']:
        parser.add_argument('--' + name, type=Path, required=True)
    a = parser.parse_args()
    assert not a.output.exists(), 'Preserve frozen releases; choose a new directory'
    frozen = json.loads((a.evidence / 'frozen-0166.json').read_text('utf-8'))
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
    tests = {}
    for task in TASKS:
        suites = []
        for p in sorted((ROOT / 'jvm/build/test-results' / task).glob('TEST-*.xml')):
            suite = ET.parse(p).getroot()
            assert all(int(suite.get(k)) == 0 for k in ['failures', 'errors', 'skipped']), p.name
            suites.append(dict(name=suite.get('name'), tests=int(suite.get('tests'))))
        assert suites, task
        assert all(p.stat().st_mtime > (a.evidence / 'native-before.json').stat().st_mtime for p in (ROOT / 'jvm/build/test-results' / task).glob('TEST-*.xml')), task
        tests[task] = dict(tests=sum(s['tests'] for s in suites), suites=suites)
    assert tests['youtubeBrowserTest']['tests'] == 16
    assert tests['overlayInputTest']['tests'] == 23
    real = (a.evidence / 'integrated-live-final.log').read_text('utf-8')
    late = (a.evidence / 'integrated-seek-final.log').read_text('utf-8')
    assert 'ended=true' in real and 'audible64=true audible76=true' in real
    assert all(json.loads((a.evidence / ('integrated-' + mode + '-result.json')).read_text('utf-8'))['exit'] == 0 for mode in ['live','seek'])
    assert 'seconds=120' in late and 'ended=true' in late
    assert 'OFFICIAL_AUDIO audible120=true audible195=true' in late
    assert 'OFFICIAL_SESSION {"signedIn":true}' in real and 'OFFICIAL_SESSION {"signedIn":true}' in late
    for name,failures in [('background-red.xml',1),('hidden-input-red.xml',2),('startup-quoting-red.xml',1),('hidden-tail-red.xml',1),('hidden-tail-six-red.xml',1)]:
        red = ET.parse(a.evidence / name).getroot()
        assert int(red.get('failures')) == failures and int(red.get('errors')) == 0,name
    assert 'OFFICIAL_BACKGROUND visibleWindows=0' in real and 'OFFICIAL_BACKGROUND visibleWindows=0' in late
    native = json.loads((a.evidence / 'native-release/results.json').read_text('utf-8'))
    assert native['mode'] == 'release' and len(native['suites']) == 34
    assert all(s['exit'] == 0 for s in native['suites'].values())
    host = (ROOT / 'jvm/build/libs/MuoniumBedrockHost.jar').read_bytes()
    jars = {p.name: p.read_bytes() for p in (ROOT / 'jvm/build/install/MuoniumBedrockHost/lib').glob('*.jar')}
    assert len(jars) == 46 and jars['MuoniumBedrockHost.jar'] == host
    assert all(json.loads((a.evidence / ('integrated-' + mode + '-result.json')).read_text('utf-8'))['hostSha256'] == sha(host) for mode in ['live','seek'])
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
        assert not added, added
        delta = sorted(n for n in original if now[n] != original[n])
        behavioral = [n for n in delta if now[n] != original[n].replace(BASELINE.encode(), VERSION.encode())]
        assert behavioral and all(any(n.startswith(c + '$') or n == c + '.class' for c in AUDIO_CLASSES) for n in behavioral), behavioral
        assert {int.from_bytes(d[6:8], 'big') for d in now.values()} == {65}
        resources = {n: z.read(n) for n in z.namelist() if not n.endswith(('.class', '/'))}
        old_resources = {n: old.read(n) for n in old.namelist() if not n.endswith(('.class', '/'))}
        assert set(resources) == set(old_resources)
        resource_delta=sorted(n for n in resources if resources[n]!=old_resources[n])
        assert resource_delta==['muonium/bedrock/browser-audio.js'],resource_delta
        assert resources['muonium/bedrock/browser-audio.js'] == (ROOT / 'jvm/src/main/resources/muonium/bedrock/browser-audio.js').read_bytes()
        assert not any('BroadcastPcmQueue' in n for n in now)
        assert not any(n.endswith('Test.class') for n in now)
        # Existing production types include SceneProbe/AccountProbe and similar
        # helpers. Preserve those exact baseline classes; admit no test probes.
        probes={n for n in now if n.endswith('Probe.class')}
        assert probes=={n for n in original if n.endswith('Probe.class')}
        assert all(now[n]==original[n] for n in probes)
        assert 'com/muoniumplayer/bedrock/YouTubeBrowserPlaybackProbe.class' not in now
        preserved = {n: sha(d) for n, d in original.items() if n.startswith((
            'com/muoniumplayer/core/cine/', 'com/muoniumplayer/bedrock/Cinematic',
            'com/muoniumplayer/bedrock/StageLyrics', 'com/muoniumplayer/bedrock/VoxelLyric',
            'com/muoniumplayer/bedrock/HostInputState', 'com/muoniumplayer/bedrock/BedrockOverlay',
            'com/muoniumplayer/compat/lwjgl2/Keyboard', 'com/muoniumplayer/compat/lwjgl2/Mouse'))}
        version_only_preserved=sorted(n for n,h in preserved.items() if sha(now[n])!=h)
        assert all(now[n]==original[n].replace(BASELINE.encode(),VERSION.encode()) for n in version_only_preserved)
    for n, data in jars.items():
        if n != 'MuoniumBedrockHost.jar':
            assert baseline[PREFIX + 'jvm/lib/' + n] == data, n
    verification = dict(version=VERSION, generatedUtc=datetime.now(timezone.utc).isoformat(),
        upstream=previous['upstream'], baseline=dict(version=BASELINE, fullArchive=baseline_name,
            sha256=frozen[baseline_name], verificationSha256=frozen['VERIFICATION.json']),
        playerReport='Keep the dedicated YouTube playback browser completely in the background from startup',
        evidenceLimit='official service PCM and owned-browser tests; no Minecraft gameplay acceptance',
        jvmFreshTasks=tests, nativeRelease=native, binaries=binaries, changedNativeSources=changed_native,
        productionClassDelta=delta, behaviorClassDelta=behavioral, addedProductionClasses=added,
        unchangedRuntimeAndLibraryJars=True, resourceDelta=resource_delta,
        unchangedVendorFiles=len(provenance['files']), nativeHookAndLayoutChanges=False,
        preservedCameraLyricsAndInputClasses=preserved,versionOnlyChangesWithinPreservedClasses=version_only_preserved,local3ExactDescriptorSha256=sha(descriptor),
        hostJarSha256=sha(host), hostClassMajorVersions=[65], jars=46,
        liveService=dict(videoId='FtutLA63Cp8', fullPlaybackPcm=True, nonSilentAt64And76=True,
            lateSeekSeconds=120, lateSeekPcmThroughEof=True, explicitTestProxy=True,
            officialSessionSignedIn=True,nonSilentAt120And195=True,originalLoginProfileUnchanged=True,
            applicationProxyConfigurationModified=False,visiblePlaybackWindows=0,windowChecksDuringFullPlaybackAndSeek=True,
            logSha256=sha(real.encode()), lateSeekLogSha256=sha(late.encode())),
        coverage='hidden Windows startup/navigation/media, exposed official skip via owned-tab Enter, PCM, sparse seeks, EOF/backseek, backpressure, cancellation and cleanup',
        globalInputInjection=False,ownedBrowserTabInput='Enter on an exposed focused official skip control only',
        playbackBrowser=dict(noStartupWindow=False,windowsStartupShowWindow='SW_HIDE',hiddenBootstrapPage=True,hiddenTarget=False,ordinaryOwnedPage=True,mediaViewport=[1280,720],headless=False,
            osFocusChanges=False,interactiveLoginWindowVisible=True),manualMinecraftAcceptance='pending',
        knownLimitations=['Requires an installed Chrome or Edge for the official-page route',
            'Official page startup and advertisements can delay audio; login/verification restrictions remain',
            'Dedicated browser CPU and RAM overhead requires gameplay measurement',
            'BSChat drafts are not an integrated runtime feature'],
        frozen0166Files=frozen, installedGameModified=False, userConfigurationIncluded=False,
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
            for n in ['README.md', 'TESTING.md', 'modmenu.settings.json', 'docs/INSTALL.md', 'docs/DEPENDENCIES.md']:
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
    print(json.dumps(dict(output=str(a.output), freshJvmTests=sum(t['tests'] for t in tests.values()),
        nativeReleaseSuites=34, behaviorClassDelta=behavioral, checksums=sums, publicAudits=audits), indent=2))


if __name__ == '__main__':
    main()

