"""Package 0.16.3 camera continuity and clipping diagnostics over frozen 0.16.2.

Read verified source/build/test evidence, preserve the accepted runtime and
libraries, and audit every package. Never install, launch Minecraft or publish.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import io
import json
import subprocess
import sys
import struct
import xml.etree.ElementTree as ET
import zipfile
from package_upstream261 import ROOT, PREFIX, sha, encoded, read_archive, archive, manifest_check

VERSION = '0.16.3'
BASELINE = '0.16.2'
TASKS = ['cinematicHostTest', 'cinematicMotionTest', 'stageRenderTest', 'nativeD3D12RenderTest', 'youtubeStreamingTest', 'sourceDiagnosticsTest', 'playbackRecoveryTest', 'stable260Test', 'cinematicClipRenderTest']


def without_line_numbers(data):
    """Remove only JVM Code.LineNumberTable; preserve all executable bytes."""
    assert data[:4] == b'\xca\xfe\xba\xbe'
    u2 = lambda p: struct.unpack_from('>H', data, p)[0]
    u4 = lambda p: struct.unpack_from('>I', data, p)[0]
    names = {}; p = 10; index = 1
    while index < u2(8):
        tag = data[p]; p += 1
        if tag == 1:
            length = u2(p); names[index] = data[p+2:p+2+length]; p += 2+length
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18): p += 4
        elif tag in (5, 6): p += 8; index += 1
        elif tag in (7, 8, 16, 19, 20): p += 2
        elif tag == 15: p += 3
        else: raise AssertionError(('constant pool tag', tag))
        index += 1

    def attrs(at):
        count = u2(at); at += 2; items = []
        for _ in range(count):
            name = u2(at); size = u4(at+2); start = at+6; end = start+size
            assert end <= len(data)
            value = data[start:end]
            if names[name] == b'Code':
                nested = start+8+u4(start+4)
                nested += 2+8*u2(nested)
                rewritten, finish = attrs(nested); assert finish == end
                value = data[start:nested]+rewritten
            if names[name] != b'LineNumberTable':
                items.append(struct.pack('>HI', name, len(value))+value)
            at = end
        return struct.pack('>H', len(items))+b''.join(items), at

    p += 6; p += 2+2*u2(p); result = data[:p]
    for _ in range(2): # fields, methods
        count = u2(p); result += data[p:p+2]; p += 2
        for _ in range(count):
            result += data[p:p+6]; p += 6
            rewritten, p = attrs(p); result += rewritten
    rewritten, p = attrs(p); assert p == len(data)
    return result+rewritten


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ['baseline', 'evidence', 'audit-tools', 'output']:
        p.add_argument('--' + name, type=Path, required=True)
    a = p.parse_args()
    assert not a.output.exists(), 'Use a new candidate directory; preserve completed releases'
    frozen = json.loads((a.evidence / 'frozen-0162.json').read_text('utf-8'))
    assert frozen and all(sha((a.baseline / n).read_bytes()) == d for n, d in frozen.items())
    previous = json.loads((a.baseline / 'VERIFICATION.json').read_text('utf-8'))
    assert previous['version'] == BASELINE
    assert 'BUILD SUCCESSFUL' in (a.evidence / 'verification.log').read_text('utf-8')
    assert 'BUILD SUCCESSFUL' in (a.evidence / 'final-affected.log').read_text('utf-8')
    assert 'build ok' in (a.evidence / 'native-build.log').read_text('utf-8')
    before_sources = json.loads((a.evidence / 'source-before.json').read_text('utf-8'))
    changed = sorted(n for n, d in before_sources.items() if sha((ROOT / n).read_bytes()) != d)
    assert set(changed) == {'include/Version.h', 'include/Local3Descriptions.h',
        'jvm/src/main/java/com/muoniumplayer/MuoniumPlayerClient.java',
        'jvm/src/main/java/com/muoniumplayer/bedrock/CinematicRuntime.java',
        'jvm/src/main/java/com/muoniumplayer/bedrock/CinematicReader.java',
        'jvm/src/main/java/com/muoniumplayer/bedrock/VoxelLyricClip.java',
        'jvm/src/main/java/com/muoniumplayer/bedrock/StageLyrics.java'}, changed
    native_before = {n: d for n, d in before_sources.items() if n.startswith(('src/', 'include/'))}
    native_now = {f.relative_to(ROOT).as_posix(): sha(f.read_bytes())
        for folder in ['src', 'include'] for f in (ROOT / folder).rglob('*') if f.is_file()}
    assert native_now.keys() == native_before.keys()
    assert {n for n in native_now if native_now[n] != native_before[n]} == {'include/Version.h', 'include/Local3Descriptions.h'}
    descriptor = (ROOT / 'modmenu.settings.json').read_bytes()
    assert json.loads(descriptor)['plugin']['versions'] == [VERSION]
    assert sha(descriptor) in (ROOT / 'include/Local3Descriptions.h').read_text()
    vendor = ROOT / 'vendor/MuoniumPlayer-2.5.2'
    provenance = json.loads((vendor / 'SOURCE-MANIFEST.json').read_text('utf-8'))
    assert provenance['version'] == '2.6.1' and provenance['commit'] == '4011d03b535cc40fa54b2cb8324cd5405eb9d6e0'
    for item in provenance['files']:
        assert sha((vendor / item['path']).read_bytes()).lower() == item['sha256'].lower(), item['path']
    tasks = {}
    for name in TASKS:
        files = list((ROOT / 'jvm/build/test-results' / name).glob('TEST-*.xml'))
        assert files and all(f.stat().st_mtime > (a.evidence / 'source-before.json').stat().st_mtime for f in files), name
        suites = [ET.parse(f).getroot() for f in files]
        assert all(int(s.get('errors')) == int(s.get('failures')) == int(s.get('skipped')) == 0 for s in suites), name
        tasks[name] = dict(tests=sum(int(s.get('tests')) for s in suites),
            suites=[dict(name=s.get('name'), tests=int(s.get('tests'))) for s in suites])
    assert tasks['youtubeStreamingTest']['tests'] == 28
    fresh = {s['name'].rsplit('.', 1)[-1]: s for t in tasks.values() for s in t['suites']}
    assert {'CinematicHostIntegrationTest','CinematicReaderTest','CinematicSnapshotCacheTest','VoxelLyricClipTest','LyricOcclusionRenderTest','CinematicEffectsRenderTest','NativeD3D12RenderTest'} <= fresh.keys()
    assert fresh['CinematicSnapshotCacheTest']['tests'] == 5 and fresh['VoxelLyricClipTest']['tests'] == 8
    red = ET.parse(a.evidence / 'red.xml').getroot()
    assert int(red.get('tests')) == int(red.get('failures')) == 1 and int(red.get('errors')) == 0
    green = ET.parse(ROOT / 'jvm/build/test-results/youtubeStreamingTest/TEST-com.muoniumplayer.bedrock.YouTubeWindowRecoveryTest.xml').getroot()
    assert 'YOUTUBE_RANGE_PCM fixture=owned-fmp4 audibleSeconds=64,76 end=decoded selector=url-only' in green.findtext('system-out')
    native = json.loads((a.evidence / 'native-release/results.json').read_text('utf-8'))
    assert native['mode'] == 'release' and len(native['suites']) == 34
    assert all(s['exit'] == 0 for s in native['suites'].values())
    assert (a.evidence / 'native-release/results.json').stat().st_mtime > (a.evidence / 'source-before.json').stat().st_mtime
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
        before = {n: old.read(n) for n in old.namelist() if n.endswith('.class')}
        added = sorted(now.keys() - before.keys())
        assert not before.keys() - now.keys()
        assert added == ['com/muoniumplayer/bedrock/CinematicSnapshotCache.class',
            'com/muoniumplayer/bedrock/LyricOcclusionTrace.class'], added
        delta = sorted(n for n in before if now[n] != before[n])
        line_only = [n for n in delta if without_line_numbers(now[n]) == without_line_numbers(before[n])]
        assert line_only == ['com/muoniumplayer/bedrock/CinematicRuntime$Probe.class',
            'com/muoniumplayer/bedrock/StageLyrics$1.class', 'com/muoniumplayer/bedrock/StageLyrics$Page.class'], line_only
        behavioral = [n for n in delta if n not in line_only and now[n] != before[n].replace(BASELINE.encode(), VERSION.encode())]
        assert set(behavioral) == {'com/muoniumplayer/bedrock/CinematicReader.class',
            'com/muoniumplayer/bedrock/CinematicRuntime.class', 'com/muoniumplayer/bedrock/StageLyrics.class',
            'com/muoniumplayer/bedrock/VoxelLyricClip.class'}, behavioral
        assert {int.from_bytes(d[6:8], 'big') for d in now.values()} == {65}
        assert {n: z.read(n) for n in z.namelist() if not n.endswith('.class')} == {n: old.read(n) for n in old.namelist() if not n.endswith('.class')}
        for n in before:
            if n.startswith(('com/muoniumplayer/core/ncm/music/YouTubeMusicService', 'com/muoniumplayer/core/cine/')):
                assert now[n] == before[n], n
        cine = 'com/muoniumplayer/core/cine/CineSession.class'
        camera_hash = sha(now[cine])
    for n, data in jars.items():
        if n != 'MuoniumBedrockHost.jar':
            assert baseline[PREFIX + 'jvm/lib/' + n] == data, n
    verification = dict(version=VERSION, generatedUtc=datetime.now(timezone.utc).isoformat(),
        upstream=previous['upstream'], baseline=dict(version=BASELINE, fullArchive=baseline_name,
            sha256=frozen[baseline_name], verificationSha256=frozen['VERIFICATION.json']),
        cameraEvidence=json.loads((a.evidence / 'INVESTIGATION.json').read_text('utf-8')),
        jvmFreshTasks=tasks, nativeRelease=native, supplementalNative=supplemental, binaries=binaries,
        changedExistingSources=changed, productionClassDelta=delta, behaviorClassDelta=behavioral, addedProductionClasses=added,
        lineNumberOnlyClassDelta=line_only, executableBytesVerifiedUnchangedForLineOnlyClasses=True,
        unchangedRuntimeAndLibraryJars=True, unchangedHostResourceFiles=True, unchangedVendorFiles=len(provenance['files']),
        nativeHookAndLayoutChanges=False, llCameraRevision='ll-coexist2', preserved0162CineSessionSha256=camera_hash,
        unchangedNativeSourceSha256={n: d for n, d in native_now.items() if n not in changed},
        local3ExactDescriptorSha256=sha(descriptor), hostJarSha256=sha(host), hostClassMajorVersions=[65], jars=46,
        cameraReproductionRed=dict(tests=1, failures=1), rangeRecoveryGreen=dict(tests=28, failures=0,
            actualPcmFixture='owned fragmented AAC', audibleSeconds=[64, 76], decodedToEof=True),
        baselineCoverage='Fresh camera, clipping, render and YouTube/audio suites; other source coverage inherited from frozen 0.16.2. Original director, YouTube production classes, native hooks/layouts and all libraries unchanged.',
        finalAffectedRenderLog='final-affected.log: camera mailbox, clipping and letterbox render rerun after final diagnostics change',
        manualMinecraftAcceptance='pending for this DLL/JAR; local HTTP and synthetic PCM are not live YouTube acceptance',
        frozen0162Files=frozen, installedGameModified=False, userConfigurationIncluded=False, otherPluginsIncluded=False, githubPublication=False)
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
            files[PREFIX + 'docs/UPDATE-0.16.2.md'] = (ROOT / 'docs/UPDATE-0.16.2.md').read_bytes()
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
