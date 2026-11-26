"""Package a bounded browser-focus update without installing or publishing it."""
from pathlib import Path
from datetime import datetime, timezone
import argparse,io,json,subprocess,sys,tempfile,zipfile
import xml.etree.ElementTree as ET
from package_upstream261 import ROOT,PREFIX,sha,encoded,read_archive,archive,manifest_check

VERSION,BASELINE='0.17.1','0.17.0'
TASKS=['youtubeBrowserTest','youtubeStreamingTest','overlayInputTest','progressiveRegressionTest']
BROWSER='com/muoniumplayer/bedrock/BrowserAudioSession'
ADDED={BROWSER+s+'.class' for s in ['$DesktopApi','$Started','$TcpOwnerApi']}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for key in ['baseline','evidence','audit-tools','output']:parser.add_argument('--'+key,type=Path,required=True)
    args=parser.parse_args();assert not args.output.exists(),'Preserve accepted candidates'
    frozen=json.loads((args.evidence/'frozen-0170.json').read_text('utf-8'))
    assert all(sha((args.baseline/n).read_bytes())==h for n,h in frozen.items())
    previous=json.loads((args.baseline/'VERIFICATION.json').read_text('utf-8'));assert previous['version']==BASELINE
    assert 'BUILD SUCCESSFUL' in (args.evidence/'desktop-regression.log').read_text('utf-8')
    assert 'build ok' in (args.evidence/'native-build.log').read_text('utf-8')
    before=json.loads((args.evidence/'native-before.json').read_text('utf-8'))
    now={p.relative_to(ROOT).as_posix():sha(p.read_bytes()) for directory in ['src','include'] for p in (ROOT/directory).rglob('*') if p.is_file()}
    assert before.keys()==now.keys()
    delta=sorted(n for n in before if before[n]!=now[n]);assert delta==['include/Local3Descriptions.h','include/Version.h'],delta
    descriptor=(ROOT/'modmenu.settings.json').read_bytes()
    assert json.loads(descriptor)['plugin']['versions']==[VERSION]
    assert sha(descriptor) in (ROOT/'include/Local3Descriptions.h').read_text('utf-8')
    assert all(json.loads((ROOT/n).read_text('utf-8'))['version']==VERSION for n in ['manifest.json','mod.json'])
    vendor=ROOT/'vendor/MuoniumPlayer-2.5.2';provenance=json.loads((vendor/'SOURCE-MANIFEST.json').read_text('utf-8'))
    for item in provenance['files']:assert sha((vendor/item['path']).read_bytes()).lower()==item['sha256'].lower(),item['path']
    tests={}
    for task in TASKS:
        suites=[]
        for path in sorted((ROOT/'jvm/build/test-results'/task).glob('TEST-*.xml')):
            suite=ET.parse(path).getroot();assert all(int(suite.get(k))==0 for k in ['failures','errors','skipped']),path.name
            assert path.stat().st_mtime>(args.evidence/'native-before.json').stat().st_mtime
            suites.append(dict(name=suite.get('name'),tests=int(suite.get('tests'))))
        assert suites,task;tests[task]=dict(tests=sum(s['tests'] for s in suites),suites=suites)
    native=json.loads((args.evidence/'native-release/results.json').read_text('utf-8'))
    assert native['mode']=='release' and len(native['suites'])==34 and all(s['exit']==0 for s in native['suites'].values())
    baseline_name=f'MuoniumPlayerBedrock-{BASELINE}-vanilla-mc26.45-26.51-26.52-26.60-full.zip'
    baseline=read_archive(args.baseline/baseline_name);manifest_check(baseline)
    host=(ROOT/'jvm/build/libs/MuoniumBedrockHost.jar').read_bytes()
    assert (ROOT/'jvm/build/install/MuoniumBedrockHost/lib/MuoniumBedrockHost.jar').read_bytes()==host
    old_host=baseline[PREFIX+'jvm/lib/MuoniumBedrockHost.jar'];assert sha(old_host)==previous['hostJarSha256']
    with zipfile.ZipFile(io.BytesIO(host)) as current,zipfile.ZipFile(io.BytesIO(old_host)) as old:
        a={n:old.read(n) for n in old.namelist() if n.endswith('.class')}
        b={n:current.read(n) for n in current.namelist() if n.endswith('.class')}
        assert not a.keys()-b.keys();assert b.keys()-a.keys()==ADDED,b.keys()-a.keys()
        changed=[n for n in a if a[n].replace(BASELINE.encode(),VERSION.encode())!=b[n]]
        assert set(changed)=={BROWSER+'.class',BROWSER+'$1.class',BROWSER+'$2.class'},changed
        javap=Path(__import__('os').environ['JAVA_HOME'])/'bin/javap.exe'
        with tempfile.TemporaryDirectory(dir=args.evidence) as temp:
            prior=Path(temp)/'prior.jar';prior.write_bytes(old_host)
            names=[(BROWSER+s).replace('/','.') for s in ['$1','$2']]
            options=[str(javap),'-c','-p','-s','-classpath']
            assert subprocess.check_output(options+[str(prior)]+names)==subprocess.check_output(options+[str(ROOT/'jvm/build/libs/MuoniumBedrockHost.jar')]+names)
        assert {int.from_bytes(v[6:8],'big') for v in b.values()}=={65}
        resources=lambda z:{n:z.read(n) for n in z.namelist() if not n.endswith(('.class','/'))}
        assert resources(current)==resources(old),'No media bridge or other resource changes'
    sys.path.insert(0,str(args.audit_tools.resolve()));from pe_audit import PeImage,audit_runtime_dependencies
    dlls={};binaries={}
    for edition,name in [('vanilla','MuoniumPlayerBedrock'),('levilamina','MuoniumPlayerBedrockLL')]:
        data=(ROOT/f'build/windows/x64/release/{name}.dll').read_bytes()
        imports=audit_runtime_dependencies(data);exports=sorted(n for n in PeImage(data).exports().values() if n)
        assert {'MPB_GetVersion','MPB_GetStatus'}<=set(exports)
        assert ('ll_mod_load' in exports)==(edition=='levilamina')
        assert not {'modmenube.dll','modmenubedrock.dll','lamium.dll'}&set(imports)
        dlls[edition]=data;binaries[edition]=dict(bytes=len(data),sha256=sha(data),runtimeImports=imports,exports=exports)
    service={}
    for key in ['live','seek','live-confirm']:
        path=args.evidence/f'focus-release-{key}-result.json'
        value=json.loads(path.read_text('utf-8'));log=(args.evidence/f'focus-release-{key}.log').read_text('utf-8')
        assert value['hostSha256']==sha(host) and value['profileUnchanged'] and value['noOwnedForeground']
        assert value['exit'] in [0,1]
        if value['exit']==0:
            assert value['ended'] and 'OFFICIAL_BACKGROUND visibleWindows=0' in log
            assert all(k+'=true' in log for k in (['audible120','audible195'] if key=='seek' else ['audible64','audible76']))
            value['strictPcmEofCoveragePassed']=True
        else:
            value['strictPcmEofCoveragePassed']=False
            if key=='live':
                assert not value['ended'] and 'bodyReady":"loading"' in log and 'bridgePresent":false' in log
                value['failure']='Document reload invalidated the PCM bridge during startup'
            else:
                assert key=='live-confirm' and value['ended'] and value['hidden']
                assert value['audible64'] and value['audible76'] and value['audible195']
                assert 'Official service probe did not satisfy PCM/EOF coverage' in log
                import re
                tail=re.search(r'OFFICIAL_AUDIO ended=true frames=(\d+) declared=(\d+)',log);assert tail
                value['lastFrame'],value['declaredFrames']=map(int,tail.groups())
                value['missingTailFrames']=value['declaredFrames']-value['lastFrame']
                assert value['missingTailFrames']>6*4096
                value['failure']='Playback reached the end but retained a PCM tail gap beyond the existing acceptance bound'
        value['logSha256']=sha(log.encode('utf-8'));service[key]=value
    assert service['seek']['exit']==0
    installed=json.loads((args.evidence/'installed-before.json').read_text('utf-8'))
    assert all(sha(Path(p).read_bytes())==h for p,h in installed.items()),'Installed files changed'
    verification=dict(version=VERSION,generatedUtc=datetime.now(timezone.utc).isoformat(),upstream=previous['upstream'],
        baseline=dict(version=BASELINE,fullArchive=baseline_name,sha256=frozen[baseline_name]),
        hostJarSha256=sha(host),binaries=binaries,tests=tests,freshJvmTests=sum(t['tests'] for t in tests.values()),
        nativeRelease=native,changedNativeSources=delta,nativeHookAndLayoutChanges=False,
        behaviorClassDelta=[BROWSER+'.class'],addedProductionClasses=sorted(ADDED),local3ExactDescriptorSha256=sha(descriptor),
        unchangedVendorFiles=len(provenance['files']),unchangedMediaBridgeAndResources=True,realYouTube=service,
        song='YJfHuATJYsQ',focusObservedWithoutActivation=True,youTubeFullPlaybackVerified=False,manualMinecraftAcceptance='pending',
        installedGameModified=False,otherPluginsIncluded=False,userConfigurationIncluded=False,githubPublication=False,
        knownLimitations=['One observed startup document reload invalidated the PCM bridge',
            'Continuous playback passed 64/76 seconds and reached EOF, but missed the strict PCM tail-coverage bound; not a complete YouTube playback fix',
            'Official authentication, advertising, regional restrictions and network conditions remain',
            'Only the tested song and local Chrome environment were verified',
            'In-game focus/input recovery requires manual acceptance'])
    (ROOT/'docs/PUBLIC-VERIFICATION.json').write_bytes(encoded(verification))
    args.output.mkdir(parents=True,exist_ok=False)
    tracked=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=ROOT).decode().split('\0')
    source={n:(ROOT/n).read_bytes() for n in tracked if n and (ROOT/n).is_file()}
    assert not any(n.split('/')[0] in {'artifacts','build','releases','.git'} for n in source)
    source_name=f'MuoniumPlayerBedrock-{VERSION}-source.zip';archive(args.output/source_name,source)
    for edition in ['vanilla','levilamina']:
        suffix='mc26.45-26.51-26.52-26.60' if edition=='vanilla' else 'mc26.60'
        for kind in ['full','update']:
            files=read_archive(args.baseline/f'MuoniumPlayerBedrock-{BASELINE}-{edition}-{suffix}-{kind}.zip')
            manifest_check(files);del files[PREFIX+'PACKAGE-MANIFEST.json']
            for n in list(files):
                if n.startswith(PREFIX+'sources/MuoniumPlayerBedrock-'):del files[n]
            for n in ['README.md','TESTING.md','modmenu.settings.json']:
                files[PREFIX+n]=(ROOT/n).read_bytes()
            files[PREFIX+'interface/modmenu.settings.json']=descriptor
            for n in ['manifest.json','mod.json']:
                meta=json.loads(files[PREFIX+n]);meta['version']=VERSION;files[PREFIX+n]=encoded(meta)
            files[PREFIX+'MuoniumPlayerBedrock.dll']=dlls[edition];files[PREFIX+'jvm/lib/MuoniumBedrockHost.jar']=host
            files[PREFIX+f'docs/UPDATE-{VERSION}.md']=(ROOT/f'docs/UPDATE-{VERSION}.md').read_bytes()
            if kind=='full':files[PREFIX+'sources/'+source_name]=(args.output/source_name).read_bytes()
            files['TESTING.md']=(ROOT/'TESTING.md').read_bytes();files['VERIFICATION.json']=encoded(verification)
            files['INSTALL.md']=files['INSTALL.md'].replace(BASELINE.encode(),VERSION.encode())
            candidate=json.loads(files['CANDIDATE.json']);candidate['version']=VERSION;files['CANDIDATE.json']=encoded(candidate)
            assert not any(n.lower().endswith(('modmenube.dll','modmenubedrock.dll','/config.json','/muoniumplayerbedrock.json')) for n in files)
            files[PREFIX+'PACKAGE-MANIFEST.json']=encoded(dict(version=VERSION,edition=edition,package=kind,
                files={n:dict(bytes=len(d),sha256=sha(d)) for n,d in sorted(files.items())}))
            archive(args.output/f'MuoniumPlayerBedrock-{VERSION}-{edition}-{suffix}-{kind}.zip',files,True)
    from verify_public_assets import audit
    audits={p.name:audit(p) for p in sorted(args.output.glob('*.zip'))}
    assert all(sha((args.baseline/n).read_bytes())==h for n,h in frozen.items())
    for n,d in [('VERIFICATION.json',encoded(verification)),('PUBLIC-ASSET-AUDIT.json',encoded(audits)),
        (f'UPDATE-{VERSION}.md',(ROOT/f'docs/UPDATE-{VERSION}.md').read_bytes()),('TESTING.md',(ROOT/'TESTING.md').read_bytes())]:(args.output/n).write_bytes(d)
    sums={p.name:sha(p.read_bytes()) for p in args.output.iterdir() if p.is_file()}
    (args.output/'SHA256SUMS.txt').write_text(''.join(f'{h}  {n}\n' for n,h in sorted(sums.items())),encoding='ascii')
    print(json.dumps(dict(output=str(args.output),freshJvmTests=verification['freshJvmTests'],nativeSuites=34,publicAudits=audits),indent=2))

if __name__=='__main__':main()
