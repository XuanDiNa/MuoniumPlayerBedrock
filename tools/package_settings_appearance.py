"""Package 0.17.0 against frozen 0.16.8 with bounded UI/browser changes.

Never installs to a game instance or publishes to GitHub.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse, io, json, subprocess, sys, zipfile, tempfile
import xml.etree.ElementTree as ET
from package_upstream261 import ROOT, PREFIX, sha, encoded, read_archive, archive, manifest_check

VERSION, BASELINE = '0.17.0', '0.16.8'
TASKS = ['youtubeStreamingTest','youtubeBrowserTest','progressiveRegressionTest',
    'playbackRecoveryTest','overlayInputTest','sourceDiagnosticsTest','stable260Test',
    'settingsAppearanceTest','uiPresentationRenderTest','stageRenderTest']
BEHAVIOR = {
    'com/muoniumplayer/bedrock/BrowserAudioSession.class',
    'com/muoniumplayer/bedrock/YouTubeBrowserAudio.class',
    'com/muoniumplayer/bedrock/YouTubeBrowserAudio$Source.class',
    'com/muoniumplayer/bedrock/UiPresentation$Selection.class',
    'com/muoniumplayer/core/ncm/music/YouTubeMusicService.class',
    'com/muoniumplayer/core/screens/hud/GuiHudEditorPro.class',
    'com/muoniumplayer/core/screens/hud/GuiHudEditorPro$1.class',
    'com/muoniumplayer/core/screens/hud/HudSettingsModel.class',
    'com/muoniumplayer/core/screens/hud/HudSettingsModel$Entry.class',
    'com/muoniumplayer/core/screens/hud/HudSettingsModel$ColorTarget.class',
}
ADDED = {'com/muoniumplayer/bedrock/SettingsAppearance'+s+'.class' for s in ['', '$State','$Palette','$Holder']}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for n in ['baseline','evidence','audit-tools','output']: p.add_argument('--'+n,type=Path,required=True)
    a=p.parse_args();assert not a.output.exists(),'Preserve existing candidates'
    frozen=json.loads((a.evidence/'frozen-0168.json').read_text('utf-8'))
    assert all(sha((a.baseline/n).read_bytes())==h for n,h in frozen.items())
    previous=json.loads((a.baseline/'VERIFICATION.json').read_text('utf-8'));assert previous['version']==BASELINE
    assert 'BUILD SUCCESSFUL' in (a.evidence/'verification-complete.log').read_text('utf-8')
    assert 'build ok' in (a.evidence/'native-build.log').read_text('utf-8')
    reds={}
    for n in ['session-red.xml','navigation-red.xml','appearance-red.xml','appearance-picker-red.xml','cookie-restore-red.xml']:
        s=ET.parse(a.evidence/n).getroot();assert int(s.get('failures'))>0 and int(s.get('errors'))==0,n
        reds[n]=dict(failures=int(s.get('failures')),sha256=sha((a.evidence/n).read_bytes()))
    before=json.loads((a.evidence/'native-before.json').read_text('utf-8'))
    now={p.relative_to(ROOT).as_posix():sha(p.read_bytes()) for d in ['src','include'] for p in (ROOT/d).rglob('*') if p.is_file()}
    assert before.keys()==now.keys()
    changed_native=sorted(n for n in before if before[n]!=now[n])
    assert changed_native==['include/Local3Descriptions.h','include/Version.h'],changed_native
    descriptor=(ROOT/'modmenu.settings.json').read_bytes()
    assert json.loads(descriptor)['plugin']['versions']==[VERSION]
    assert sha(descriptor) in (ROOT/'include/Local3Descriptions.h').read_text('utf-8')
    assert all(json.loads((ROOT/n).read_text('utf-8'))['version']==VERSION for n in ['manifest.json','mod.json'])
    vendor=ROOT/'vendor/MuoniumPlayer-2.5.2';provenance=json.loads((vendor/'SOURCE-MANIFEST.json').read_text('utf-8'))
    for item in provenance['files']:assert sha((vendor/item['path']).read_bytes()).lower()==item['sha256'].lower(),item['path']
    tests={}
    for task in TASKS:
        suites=[]
        for f in sorted((ROOT/'jvm/build/test-results'/task).glob('TEST-*.xml')):
            s=ET.parse(f).getroot();assert all(int(s.get(k))==0 for k in ['failures','errors','skipped']),f.name
            assert f.stat().st_mtime>(a.evidence/'native-before.json').stat().st_mtime
            suites.append(dict(name=s.get('name'),tests=int(s.get('tests'))))
        assert suites,task
        tests[task]=dict(tests=sum(s['tests'] for s in suites),suites=suites)
    native=json.loads((a.evidence/'native-release/results.json').read_text('utf-8'))
    assert native['mode']=='release' and len(native['suites'])==34 and all(s['exit']==0 for s in native['suites'].values())
    baseline_name=f'MuoniumPlayerBedrock-{BASELINE}-vanilla-mc26.45-26.51-26.52-26.60-full.zip'
    baseline=read_archive(a.baseline/baseline_name);manifest_check(baseline)
    host=(ROOT/'jvm/build/libs/MuoniumBedrockHost.jar').read_bytes()
    jars={f.name:f.read_bytes() for f in (ROOT/'jvm/build/install/MuoniumBedrockHost/lib').glob('*.jar')}
    assert len(jars)==46 and jars['MuoniumBedrockHost.jar']==host
    old_host=baseline[PREFIX+'jvm/lib/MuoniumBedrockHost.jar'];assert sha(old_host)==previous['hostJarSha256']
    with zipfile.ZipFile(io.BytesIO(host)) as z,zipfile.ZipFile(io.BytesIO(old_host)) as old:
        original={n:old.read(n) for n in old.namelist() if n.endswith('.class')}
        current={n:z.read(n) for n in z.namelist() if n.endswith('.class')}
        assert not original.keys()-current.keys(),'No removed classes'
        assert current.keys()-original.keys()==ADDED,current.keys()-original.keys()
        delta=sorted(n for n in original if current[n]!=original[n])
        behavioral=[n for n in delta if current[n]!=original[n].replace(BASELINE.encode(),VERSION.encode())]
        debug_only=[n for n in behavioral if n not in BEHAVIOR]
        with tempfile.TemporaryDirectory(dir=a.evidence) as temp:
            prior=Path(temp)/'baseline.jar';prior.write_bytes(old_host)
            javap=Path(r'C:\Program Files\Java\jdk-21\bin\javap.exe')
            for n in debug_only:
                assert any(n.startswith(c[:-6]+'$') for c in BEHAVIOR),n
            args=[str(javap),'-c','-p','-s','-classpath']
            names=[n[:-6].replace('/','.') for n in debug_only]
            assert subprocess.check_output(args+[str(prior)]+names)==subprocess.check_output(args+[str(ROOT/'jvm/build/libs/MuoniumBedrockHost.jar')]+names),'Unexpected nested-class instruction/descriptor change'
        behavioral=sorted(set(behavioral)-set(debug_only));assert set(behavioral)==BEHAVIOR,behavioral
        assert {int.from_bytes(d[6:8],'big') for d in current.values()}=={65}
        resources={n:z.read(n) for n in z.namelist() if not n.endswith(('.class','/'))}
        old_resources={n:old.read(n) for n in old.namelist() if not n.endswith(('.class','/'))}
        assert resources==old_resources,'Audio bridge/resources remain byte identical'
        assert not any(n.endswith('Test.class') for n in current)
    for n,d in jars.items():
        if n!='MuoniumBedrockHost.jar':assert baseline[PREFIX+'jvm/lib/'+n]==d,n
    sys.path.insert(0,str(a.audit_tools.resolve()))
    from pe_audit import PeImage,audit_runtime_dependencies
    dlls,binaries={},{}
    for edition,name in [('vanilla','MuoniumPlayerBedrock'),('levilamina','MuoniumPlayerBedrockLL')]:
        data=(ROOT/f'build/windows/x64/release/{name}.dll').read_bytes();imports=audit_runtime_dependencies(data)
        exports=sorted(n for n in PeImage(data).exports().values() if n is not None)
        assert {'MPB_GetVersion','MPB_GetStatus'}<=set(exports)
        assert ('ll_mod_load' in exports)==(edition=='levilamina')
        assert not {'modmenube.dll','modmenubedrock.dll','lamium.dll'}&set(imports)
        dlls[edition]=data;binaries[edition]=dict(bytes=len(data),sha256=sha(data),exports=exports,runtimeImports=imports)
    service={}
    for mode in ['live','seek']:
        value=json.loads((a.evidence/f'candidate-{mode}-result.json').read_text('utf-8'))
        log=(a.evidence/f'candidate-{mode}.log').read_bytes()
        assert value['hostSha256']==sha(host) and value['originalUnchanged']
        assert value['exit'] in [0,1]
        if value['exit']==0:
            assert value['ended'] and value['hidden']
            assert all(value[k] for k in (['audible64','audible76'] if mode=='live' else ['audible120','audible195']))
        else:
            # A settings candidate may retain a documented external-service
            # failure, but cannot present fixture success as a playback fix.
            assert b'PlaybackFailure' in log and b'OFFICIAL_PAGE_DIAGNOSTIC' in log
            assert not value['ended']
        value['logSha256']=sha(log);service[mode]=value
    playback_verified=all(v['exit']==0 for v in service.values())
    normal=(a.evidence/'normal-page.log').read_text('utf-8',errors='replace')
    normal_run=(a.evidence/'normal-page-run.log').read_text('utf-8',errors='replace')
    assert 'Original login profile unchanged' in normal_run,'Wait for the independent official-page comparison'
    normal_ok='NORMAL_PAGE reached90=true' in normal
    assert normal_ok or 'Official page failed without our audio graph or PCM bridge' in normal or 'Official page did not reach 90 seconds' in normal
    comparison=dict(reached90=normal_ok,noAudioGraphOrPcmBridge=True,
        logSha256=sha((a.evidence/'normal-page.log').read_bytes()),originalProfileUnchanged=True)
    captures={}
    for n in [f'settings-theme-{i}.png' for i in range(4)]+['settings-accent-picker.png']:
        path=ROOT/'jvm/build/stage-captures'/n;assert path.stat().st_size>10000
        captures[n]=sha(path.read_bytes())
    verification=dict(version=VERSION,generatedUtc=datetime.now(timezone.utc).isoformat(),upstream=previous['upstream'],
        baseline=dict(version=BASELINE,fullArchive=baseline_name,sha256=frozen[baseline_name]),
        browserFixes=['Same-document identity permits added query keys while preserving all requested values',
            'Official-browser session runs before independent API-cookie rejection',
            'Restore exported login cookies only when the snapshot has no browser authentication identity'],
        settingsAppearance=dict(entry='Existing HUD editor',themes=['PolyGlass Dark','PolyGlass Light','Minecraft Dark','Minecraft Light'],
            accentPicker=True,animationsToggle=True,legacyLayoutRetained=True,captures=captures,
            decoderAndQueueIdentityPreservedInOwnedRenderTest=True,notFabricOneConfigRuntime=True),
        jvmFreshTasks=tests,nativeRelease=native,binaries=binaries,hostJarSha256=sha(host),
        productionClassDelta=delta,behaviorClassDelta=behavioral,debugOnlyClassDelta=debug_only,
        debugOnlyClassCheck='javap -c -p -s exact instruction/descriptor equality',addedProductionClasses=sorted(ADDED),resourceDelta=[],
        unchangedDecoderInputCameraAndNativeRender=True,unchangedRuntimeAndLibraryJars=True,
        unchangedVendorFiles=len(provenance['files']),changedNativeSources=changed_native,nativeHookAndLayoutChanges=False,
        local3ExactDescriptorSha256=sha(descriptor),hostClassMajorVersions=[65],jars=46,regressionRed=reds,realYouTube=service,
        youTubeFullPlaybackVerified=playback_verified,officialPageComparison=comparison,
        manualMinecraftAcceptance='pending',installedGameModified=False,githubPublication=False,
        knownLimitations=['Requires installed Chrome or Edge and official-page session',
            *([] if playback_verified else ['Real-service full playback and late seek still fail for the tested song; do not claim YouTube playback fixed']),
            'Official advertisements, verification, region and content restrictions remain',
            'Theme palettes apply to the modern settings center; legacy layout retains its own palette',
            'Native Minecraft rendering and in-game interaction require manual acceptance'],
        frozen0168Files=frozen,userConfigurationIncluded=False,otherPluginsIncluded=False)
    (ROOT/'docs/PUBLIC-VERIFICATION.json').write_bytes(encoded(verification))
    a.output.mkdir(parents=True,exist_ok=False)
    tracked=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=ROOT).decode().split('\0')
    source={n:(ROOT/n).read_bytes() for n in tracked if n and (ROOT/n).is_file()}
    assert not any(n.split('/')[0] in {'build','artifacts','releases','.git'} for n in source)
    source_name=f'MuoniumPlayerBedrock-{VERSION}-source.zip';archive(a.output/source_name,source)
    for edition in ['vanilla','levilamina']:
        suffix='mc26.45-26.51-26.52-26.60' if edition=='vanilla' else 'mc26.60'
        for kind in ['full','update']:
            files=read_archive(a.baseline/f'MuoniumPlayerBedrock-{BASELINE}-{edition}-{suffix}-{kind}.zip')
            manifest_check(files);del files[PREFIX+'PACKAGE-MANIFEST.json']
            for n in list(files):
                if n.startswith(PREFIX+'sources/MuoniumPlayerBedrock-'):del files[n]
            for n in ['README.md','TESTING.md','modmenu.settings.json','docs/INSTALL.md','docs/DEPENDENCIES.md']:
                files[PREFIX+n]=(ROOT/n).read_bytes()
            files[PREFIX+'interface/modmenu.settings.json']=descriptor
            for n in ['manifest.json','mod.json']:
                meta=json.loads(files[PREFIX+n]);meta['version']=VERSION;files[PREFIX+n]=encoded(meta)
            files[PREFIX+'MuoniumPlayerBedrock.dll']=dlls[edition];files[PREFIX+'jvm/lib/MuoniumBedrockHost.jar']=host
            files[PREFIX+f'docs/UPDATE-{VERSION}.md']=(ROOT/f'docs/UPDATE-{VERSION}.md').read_bytes()
            if kind=='full':files[PREFIX+'sources/'+source_name]=(a.output/source_name).read_bytes()
            files['TESTING.md']=(ROOT/'TESTING.md').read_bytes();files['VERIFICATION.json']=encoded(verification)
            files['INSTALL.md']=files['INSTALL.md'].replace(BASELINE.encode(),VERSION.encode())
            candidate=json.loads(files['CANDIDATE.json']);candidate['version']=VERSION;files['CANDIDATE.json']=encoded(candidate)
            assert not any(n.lower().endswith(('modmenube.dll','modmenubedrock.dll','/config.json','/muoniumplayerbedrock.json')) for n in files)
            files[PREFIX+'PACKAGE-MANIFEST.json']=encoded(dict(version=VERSION,edition=edition,package=kind,
                files={n:dict(bytes=len(d),sha256=sha(d)) for n,d in sorted(files.items())}))
            archive(a.output/f'MuoniumPlayerBedrock-{VERSION}-{edition}-{suffix}-{kind}.zip',files,True)
    from verify_public_assets import audit
    audits={f.name:audit(f) for f in sorted(a.output.glob('*.zip'))}
    assert all(sha((a.baseline/n).read_bytes())==h for n,h in frozen.items())
    for n,d in [('VERIFICATION.json',encoded(verification)),('PUBLIC-ASSET-AUDIT.json',encoded(audits)),
        (f'UPDATE-{VERSION}.md',(ROOT/f'docs/UPDATE-{VERSION}.md').read_bytes()),('TESTING.md',(ROOT/'TESTING.md').read_bytes())]:(a.output/n).write_bytes(d)
    sums={f.name:sha(f.read_bytes()) for f in a.output.iterdir() if f.is_file()}
    (a.output/'SHA256SUMS.txt').write_text(''.join(f'{h}  {n}\n' for n,h in sorted(sums.items())),encoding='ascii')
    print(json.dumps(dict(output=str(a.output),freshJvmTests=sum(t['tests'] for t in tests.values()),nativeReleaseSuites=34,
        behaviorClassDelta=behavioral,addedProductionClasses=sorted(ADDED),realYouTube=service,checksums=sums,publicAudits=audits),indent=2))

if __name__=='__main__':main()
