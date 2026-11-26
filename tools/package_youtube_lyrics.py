"""Package 0.16.8 with a strict two-class behavior delta from frozen 0.16.7."""
from pathlib import Path
from datetime import datetime, timezone
import argparse, io, json, subprocess, sys, zipfile, tempfile
import xml.etree.ElementTree as ET
from package_upstream261 import ROOT, PREFIX, sha, encoded, read_archive, archive, manifest_check

VERSION, BASELINE = '0.16.8', '0.16.7'
TASKS = ['youtubeStreamingTest', 'youtubeBrowserTest', 'progressiveRegressionTest',
         'playbackRecoveryTest', 'overlayInputTest', 'sourceDiagnosticsTest', 'stable260Test']
ALLOWED = ['com/muoniumplayer/core/ncm/music/YouTubeMusicService.class',
           'com/muoniumplayer/core/ncm/music/LyricLoadService.class']


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for n in ['baseline', 'evidence', 'audit-tools', 'output']:
        p.add_argument('--' + n, type=Path, required=True)
    a = p.parse_args()
    assert not a.output.exists(), 'Preserve existing candidates'
    frozen = json.loads((a.evidence/'frozen-0167.json').read_text('utf-8'))
    assert all(sha((a.baseline/n).read_bytes()) == h for n,h in frozen.items())
    previous = json.loads((a.baseline/'VERIFICATION.json').read_text('utf-8'))
    assert previous['version'] == BASELINE
    assert 'BUILD SUCCESSFUL' in (a.evidence/'verification-final.log').read_text('utf-8')
    assert 'build ok' in (a.evidence/'native-build.log').read_text('utf-8')
    red = ET.parse(a.evidence/'lyrics-red.xml').getroot()
    assert int(red.get('failures')) == 12 and int(red.get('errors')) == 0
    before = json.loads((a.evidence/'native-before.json').read_text('utf-8'))
    now = {p.relative_to(ROOT).as_posix():sha(p.read_bytes()) for folder in ['src','include']
           for p in (ROOT/folder).rglob('*') if p.is_file()}
    assert before.keys() == now.keys()
    changed_native = sorted(n for n in before if before[n] != now[n])
    assert changed_native == ['include/Local3Descriptions.h','include/Version.h'], changed_native
    descriptor = (ROOT/'modmenu.settings.json').read_bytes()
    assert json.loads(descriptor)['plugin']['versions'] == [VERSION]
    assert sha(descriptor) in (ROOT/'include/Local3Descriptions.h').read_text('utf-8')
    assert all(json.loads((ROOT/n).read_text('utf-8'))['version'] == VERSION for n in ['manifest.json','mod.json'])
    vendor = ROOT/'vendor/MuoniumPlayer-2.5.2'
    provenance = json.loads((vendor/'SOURCE-MANIFEST.json').read_text('utf-8'))
    for item in provenance['files']:
        assert sha((vendor/item['path']).read_bytes()).lower() == item['sha256'].lower(), item['path']
    tests = {}
    for task in TASKS:
        suites = []
        for f in sorted((ROOT/'jvm/build/test-results'/task).glob('TEST-*.xml')):
            s = ET.parse(f).getroot()
            assert all(int(s.get(k)) == 0 for k in ['failures','errors','skipped']), f.name
            assert f.stat().st_mtime > (a.evidence/'native-before.json').stat().st_mtime
            suites.append(dict(name=s.get('name'),tests=int(s.get('tests'))))
        assert suites,task
        tests[task] = dict(tests=sum(s['tests'] for s in suites),suites=suites)
    native = json.loads((a.evidence/'native-release/results.json').read_text('utf-8'))
    assert native['mode'] == 'release' and len(native['suites']) == 34
    assert all(s['exit'] == 0 for s in native['suites'].values())
    baseline_name = f'MuoniumPlayerBedrock-{BASELINE}-vanilla-mc26.45-26.51-26.52-26.60-full.zip'
    baseline = read_archive(a.baseline/baseline_name); manifest_check(baseline)
    host = (ROOT/'jvm/build/libs/MuoniumBedrockHost.jar').read_bytes()
    jars = {f.name:f.read_bytes() for f in (ROOT/'jvm/build/install/MuoniumBedrockHost/lib').glob('*.jar')}
    assert len(jars) == 46 and jars['MuoniumBedrockHost.jar'] == host
    old_host = baseline[PREFIX+'jvm/lib/MuoniumBedrockHost.jar']
    assert sha(old_host) == previous['hostJarSha256']
    with zipfile.ZipFile(io.BytesIO(host)) as z, zipfile.ZipFile(io.BytesIO(old_host)) as old:
        original = {n:old.read(n) for n in old.namelist() if n.endswith('.class')}
        current = {n:z.read(n) for n in z.namelist() if n.endswith('.class')}
        assert original.keys() == current.keys(), 'No new production classes'
        delta = sorted(n for n in current if current[n] != original[n])
        behavioral = [n for n in delta if current[n] != original[n].replace(BASELINE.encode(),VERSION.encode())]
        debug_only = [n for n in behavioral if n not in ALLOWED]
        # javac regenerates nested-class LineNumberTable attributes when the
        # containing service gains methods/lines. Admit those only after an
        # exact private/public instruction and descriptor comparison.
        with tempfile.TemporaryDirectory(dir=a.evidence) as temp:
            prior_jar = Path(temp)/'baseline.jar'; prior_jar.write_bytes(old_host)
            javap = Path(r'C:\Program Files\Java\jdk-21\bin\javap.exe')
            for n in debug_only:
                assert any(n.startswith(c[:-6]+'$') for c in ALLOWED), n
                args = [str(javap),'-c','-p','-s','-classpath']
                name = n[:-6].replace('/','.')
                old_code = subprocess.check_output(args+[str(prior_jar),name])
                new_code = subprocess.check_output(args+[str(ROOT/'jvm/build/libs/MuoniumBedrockHost.jar'),name])
                assert old_code == new_code, n
        behavioral = [n for n in behavioral if n not in debug_only]
        assert behavioral == sorted(ALLOWED), behavioral
        assert {int.from_bytes(d[6:8],'big') for d in current.values()} == {65}
        resources = {n:z.read(n) for n in z.namelist() if not n.endswith(('.class','/'))}
        old_resources = {n:old.read(n) for n in old.namelist() if not n.endswith(('.class','/'))}
        assert resources == old_resources, 'Browser/audio resources must remain byte identical'
        assert not any(n.endswith('Test.class') for n in current)
    for n,data in jars.items():
        if n != 'MuoniumBedrockHost.jar': assert baseline[PREFIX+'jvm/lib/'+n] == data,n
    sys.path.insert(0,str(a.audit_tools.resolve()))
    from pe_audit import PeImage, audit_runtime_dependencies
    dlls,binaries = {},{}
    for edition,name in [('vanilla','MuoniumPlayerBedrock'),('levilamina','MuoniumPlayerBedrockLL')]:
        data = (ROOT/f'build/windows/x64/release/{name}.dll').read_bytes()
        imports = audit_runtime_dependencies(data)
        exports = sorted(n for n in PeImage(data).exports().values() if n is not None)
        assert {'MPB_GetVersion','MPB_GetStatus'} <= set(exports)
        assert ('ll_mod_load' in exports) == (edition == 'levilamina')
        assert not {'modmenube.dll','modmenubedrock.dll','lamium.dll'} & set(imports)
        dlls[edition] = data
        binaries[edition] = dict(bytes=len(data),sha256=sha(data),exports=exports,runtimeImports=imports)
    service = (a.evidence/'platform-lyrics-after.log').read_text('utf-8')
    assert 'LYRIC_RESULT timed=false plainRows=24 lrcEmpty=true' in service
    assert '[Music/YouTube] 官方歌词缺少有效时间轴' in service and 'LOADED_TIMES rows=' in service
    verification = dict(version=VERSION,generatedUtc=datetime.now(timezone.utc).isoformat(),
        upstream=previous['upstream'],baseline=dict(version=BASELINE,fullArchive=baseline_name,sha256=frozen[baseline_name]),
        playerReport='YouTube lyrics immediately select the last line',
        rootCause='Official timedLyricsData rows lack cueRange; missing timestamps were converted to zero',
        correction='Validate complete official cues; untimed text allows existing timed lyric matching',
        jvmFreshTasks=tests,nativeRelease=native,binaries=binaries,hostJarSha256=sha(host),
        productionClassDelta=delta,behaviorClassDelta=behavioral,debugOnlyClassDelta=debug_only,
        debugOnlyClassCheck='javap -c -p -s exact instruction/descriptor equality',addedProductionClasses=[],resourceDelta=[],
        unchangedPlaybackBrowserAudioInputCameraAndRender=True,unchangedRuntimeAndLibraryJars=True,
        unchangedVendorFiles=len(provenance['files']),changedNativeSources=changed_native,nativeHookAndLayoutChanges=False,
        local3ExactDescriptorSha256=sha(descriptor),hostClassMajorVersions=[65],jars=46,
        regressionRed=dict(failures=12,errors=0,sha256=sha((a.evidence/'lyrics-red.xml').read_bytes())),
        realLyricService=dict(videoId='YJfHuATJYsQ',officialRows=24,missingCues=True,
            officialResultUntimed=True,loaderContinuesMatching=True,logSha256=sha(service.encode())),
        inheritedPlaybackEvidence=dict(version=BASELINE,verificationSha256=frozen['VERIFICATION.json'],
            note='Existing full playback and seek PCM evidence; playback code unchanged, not rerun this candidate'),
        manualMinecraftAcceptance='pending',installedGameModified=False,githubPublication=False,
        knownLimitations=['Third-party lyrics may be unavailable or match another song version',
            'No synchronized lyrics when neither official nor fallback timestamps are available',
            'Existing browser overhead and official-page login/advertisement restrictions remain'],
        frozen0167Files=frozen,userConfigurationIncluded=False,otherPluginsIncluded=False)
    (ROOT/'docs/PUBLIC-VERIFICATION.json').write_bytes(encoded(verification))
    a.output.mkdir(parents=True,exist_ok=False)
    tracked = subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=ROOT).decode().split('\0')
    source = {n:(ROOT/n).read_bytes() for n in tracked if n and (ROOT/n).is_file()}
    assert not any(n.split('/')[0] in {'build','artifacts','releases','.git'} for n in source)
    source_name = f'MuoniumPlayerBedrock-{VERSION}-source.zip'
    archive(a.output/source_name,source)
    for edition in ['vanilla','levilamina']:
        suffix = 'mc26.45-26.51-26.52-26.60' if edition == 'vanilla' else 'mc26.60'
        for kind in ['full','update']:
            files = read_archive(a.baseline/f'MuoniumPlayerBedrock-{BASELINE}-{edition}-{suffix}-{kind}.zip')
            manifest_check(files);del files[PREFIX+'PACKAGE-MANIFEST.json']
            for n in list(files):
                if n.startswith(PREFIX+'sources/MuoniumPlayerBedrock-'): del files[n]
            for n in ['README.md','TESTING.md','modmenu.settings.json','docs/INSTALL.md','docs/DEPENDENCIES.md']:
                files[PREFIX+n] = (ROOT/n).read_bytes()
            files[PREFIX+'interface/modmenu.settings.json'] = descriptor
            for n in ['manifest.json','mod.json']:
                meta=json.loads(files[PREFIX+n]);meta['version']=VERSION;files[PREFIX+n]=encoded(meta)
            files[PREFIX+'MuoniumPlayerBedrock.dll']=dlls[edition]
            files[PREFIX+'jvm/lib/MuoniumBedrockHost.jar']=host
            files[PREFIX+f'docs/UPDATE-{VERSION}.md']=(ROOT/f'docs/UPDATE-{VERSION}.md').read_bytes()
            if kind=='full': files[PREFIX+'sources/'+source_name]=(a.output/source_name).read_bytes()
            files['TESTING.md']=(ROOT/'TESTING.md').read_bytes();files['VERIFICATION.json']=encoded(verification)
            files['INSTALL.md']=files['INSTALL.md'].replace(BASELINE.encode(),VERSION.encode())
            candidate=json.loads(files['CANDIDATE.json']);candidate['version']=VERSION;files['CANDIDATE.json']=encoded(candidate)
            assert not any(n.lower().endswith(('modmenube.dll','modmenubedrock.dll','/config.json','/muoniumplayerbedrock.json')) for n in files)
            files[PREFIX+'PACKAGE-MANIFEST.json']=encoded(dict(version=VERSION,edition=edition,package=kind,
                files={n:dict(bytes=len(d),sha256=sha(d)) for n,d in sorted(files.items())}))
            archive(a.output/f'MuoniumPlayerBedrock-{VERSION}-{edition}-{suffix}-{kind}.zip',files,True)
    from verify_public_assets import audit
    audits={f.name:audit(f) for f in sorted(a.output.glob('*.zip'))}
    assert all(sha((a.baseline/n).read_bytes()) == h for n,h in frozen.items())
    for n,data in [('VERIFICATION.json',encoded(verification)),('PUBLIC-ASSET-AUDIT.json',encoded(audits)),
                   (f'UPDATE-{VERSION}.md',(ROOT/f'docs/UPDATE-{VERSION}.md').read_bytes()),('TESTING.md',(ROOT/'TESTING.md').read_bytes())]:
        (a.output/n).write_bytes(data)
    sums={f.name:sha(f.read_bytes()) for f in a.output.iterdir() if f.is_file()}
    (a.output/'SHA256SUMS.txt').write_text(''.join(f'{h}  {n}\n' for n,h in sorted(sums.items())),encoding='ascii')
    print(json.dumps(dict(output=str(a.output),freshJvmTests=sum(t['tests'] for t in tests.values()),
        nativeReleaseSuites=34,behaviorClassDelta=behavioral,checksums=sums,publicAudits=audits),indent=2))


if __name__ == '__main__': main()
