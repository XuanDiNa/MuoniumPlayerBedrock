"""Package the optional BSChat candidate; never install or publish it."""
from pathlib import Path
from datetime import datetime, timezone
import argparse, io, json, os, re, subprocess, sys, tempfile, zipfile
import xml.etree.ElementTree as ET
from package_upstream261 import ROOT, PREFIX, sha, encoded, read_archive, archive, manifest_check

VERSION = '0.18.0'
BSCHAT_VERSION = '0.1.4-muonium.1'
PIN = '31e1dfc7cdf753949e06444ee483a40e54d31055'
TASKS = ['voiceBroadcastTest', 'voiceBroadcastRenderTest', 'settingsAppearanceTest',
         'overlayInputTest', 'progressiveRegressionTest', 'playbackRecoveryTest']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ['baseline', 'evidence', 'client-sdk', 'audit-tools', 'output']:
        parser.add_argument('--' + key, type=Path, required=True)
    args = parser.parse_args()
    assert not args.output.exists(), 'Preserve existing candidates'
    e = args.evidence
    frozen = json.loads((e/'frozen-0171.json').read_text('utf-8'))
    assert frozen and all(sha((args.baseline/n).read_bytes()) == h for n,h in frozen.items())
    before = json.loads((e/'native-before.json').read_text('utf-8'))
    now = {p.relative_to(ROOT).as_posix():sha(p.read_bytes())
           for d in ['src','include'] for p in (ROOT/d).rglob('*') if p.is_file()}
    assert now.keys() == before.keys()
    delta = sorted(n for n in now if before[n] != now[n])
    assert delta == ['include/Local3Descriptions.h','include/Version.h'], delta
    vendor = ROOT/'vendor/MuoniumPlayer-2.5.2'
    vendor_before = json.loads((e/'vendor-before.json').read_text('utf-8'))
    vendor_now = {p.relative_to(vendor).as_posix():sha(p.read_bytes()) for p in vendor.rglob('*') if p.is_file()}
    assert vendor_before == vendor_now, 'Pinned vendor changed during this task'
    descriptor = (ROOT/'modmenu.settings.json').read_bytes()
    assert json.loads(descriptor)['plugin']['versions'] == [VERSION]
    assert sha(descriptor) in (ROOT/'include/Local3Descriptions.h').read_text('utf-8')
    for n in ['manifest.json','mod.json']:
        assert json.loads((ROOT/n).read_text('utf-8'))['version'] == VERSION
    for name, marker in [('native-build-final.log','build ok'), ('client-build-final.log','build ok'),
                         ('server-build.log','build ok'), ('client-tests-complete.log','Total failures: 0'),
                         ('mailbox-final.log','mailbox failures=0'),
                         ('host-regression-final.log','BUILD SUCCESSFUL'), ('host-voice-final.log','BUILD SUCCESSFUL')]:
        assert marker in (e/name).read_text('utf-8'), name
    jvm_tests = {}
    for task in TASKS:
        suites = []
        for p in sorted((ROOT/'jvm/build/test-results'/task).glob('TEST-*.xml')):
            value = ET.parse(p).getroot()
            assert all(int(value.get(k)) == 0 for k in ['failures','errors','skipped']), p
            assert p.stat().st_mtime >= (e/'native-before.json').stat().st_mtime, ('stale',p)
            suites.append(dict(name=value.get('name'),tests=int(value.get('tests'))))
        assert suites,task
        jvm_tests[task] = dict(tests=sum(v['tests'] for v in suites),suites=suites)
    native = json.loads((e/'native-release/results.json').read_text('utf-8'))
    assert native['mode'] == 'release' and len(native['suites']) == 34
    assert all(s['exit'] == 0 for s in native['suites'].values())

    base_name = 'MuoniumPlayerBedrock-0.17.1-levilamina-mc26.60-full.zip'
    baseline = read_archive(args.baseline/base_name); manifest_check(baseline)
    previous = json.loads((args.baseline/'VERIFICATION.json').read_text('utf-8'))
    host = (ROOT/'jvm/build/libs/MuoniumBedrockHost.jar').read_bytes()
    assert (ROOT/'jvm/build/install/MuoniumBedrockHost/lib/MuoniumBedrockHost.jar').read_bytes() == host
    assert sha(baseline[PREFIX+'jvm/lib/MuoniumBedrockHost.jar']) == previous['hostJarSha256']
    with zipfile.ZipFile(io.BytesIO(baseline[PREFIX+'jvm/lib/MuoniumBedrockHost.jar'])) as old, zipfile.ZipFile(io.BytesIO(host)) as new:
        a={n:old.read(n) for n in old.namelist() if n.endswith('.class')}
        b={n:new.read(n) for n in new.namelist() if n.endswith('.class')}
        assert not a.keys()-b.keys()
        added=sorted(b.keys()-a.keys())
        assert all(n.startswith(('com/muoniumplayer/bedrock/Voice','com/muoniumplayer/bedrock/BroadcastPcmQueue')) for n in added)
        changed=sorted(n for n in a if a[n].replace(b'0.17.1',VERSION.encode()) != b[n])
        model='com/muoniumplayer/core/screens/hud/HudSettingsModel'
        behavior={
            'com/muoniumplayer/bedrock/BedrockOverlay.class',
            'com/muoniumplayer/core/ncm/music/AudioPlayer.class',
            model+'.class',model+'$Kind.class',
            *(model+'$'+str(i)+'.class' for i in range(163,167))}
        assert set(changed)<=behavior|{n for n in a if n.startswith(model+'$')},changed
        debug_only=sorted(set(changed)-behavior)
        # Adding the read-only enum shifts source line tables in every nested
        # model class. Compare executable instructions without debug metadata.
        if debug_only:
            with tempfile.TemporaryDirectory(dir=e) as temp:
                prior=Path(temp)/'prior.jar';prior.write_bytes(baseline[PREFIX+'jvm/lib/MuoniumBedrockHost.jar'])
                names=[n[:-6].replace('/','.') for n in debug_only]
                javap=Path(os.environ['JAVA_HOME'])/'bin/javap.exe'
                options=[str(javap),'-c','-p','-s','-classpath']
                assert subprocess.check_output(options+[str(prior)]+names)==subprocess.check_output(options+[str(ROOT/'jvm/build/libs/MuoniumBedrockHost.jar')]+names)
        resources=lambda z:{n:z.read(n) for n in z.namelist() if not n.endswith(('.class','/'))}
        assert resources(old)==resources(new), 'Media resources unexpectedly changed'
        assert {int.from_bytes(v[6:8],'big') for v in b.values()} == {65}

    sys.path.insert(0,str(args.audit_tools.resolve()))
    from pe_audit import PeImage, audit_runtime_dependencies
    def audit_binary(data):
        imports=audit_runtime_dependencies(data)
        exports=sorted(n for n in PeImage(data).exports().values() if n)
        assert 'll_mod_load' in exports
        assert not {'modmenube.dll','modmenubedrock.dll','lamium.dll'}&set(imports)
        return dict(bytes=len(data),sha256=sha(data),imports=imports,exports=exports)
    muonium=(ROOT/'build/windows/x64/release/MuoniumPlayerBedrockLL.dll').read_bytes()
    binaries={'muonium':audit_binary(muonium)}
    addons={}
    for target in ['client','server']:
        files={p.relative_to(e/(target+'-final')).as_posix():p.read_bytes()
               for p in (e/(target+'-final')).rglob('*') if p.is_file()}
        meta=json.loads(files['manifest.json'])
        assert meta['name']=='bschat' and meta['platform']==target and meta['version']==BSCHAT_VERSION
        assert meta['dependencies']==[{'name':'LeviLamina','version':'=26.60.7'}]
        assert set(files) >= {'bschat.dll','manifest.json'}
        binaries['bschat-'+target]=audit_binary(files['bschat.dll']);addons[target]=files
    assert binaries['bschat-client']['sha256']!=binaries['bschat-server']['sha256']

    upstream_root=ROOT/'integrations/bschat/upstream'
    original_root=e/'baseline'
    ignored={'build','bin','.xmake','__pycache__','.git'}
    def tree(p):
        return {f.relative_to(p).as_posix():f.read_bytes() for f in p.rglob('*')
                if f.is_file() and not ignored.intersection(f.relative_to(p).parts)}
    original=tree(original_root);current=tree(upstream_root)
    upstream_changes=dict(pin=PIN,version=BSCHAT_VERSION,license='AGPL-3.0-or-later',
        added={n:sha(current[n]) for n in sorted(current.keys()-original.keys())},
        removed=sorted(original.keys()-current.keys()),
        modified={n:dict(before=sha(original[n]),after=sha(current[n])) for n in sorted(original.keys()&current.keys()) if original[n]!=current[n]})
    (ROOT/'integrations/bschat/UPSTREAM-FILES.json').write_bytes(encoded(upstream_changes))
    verification=dict(version=VERSION,bschatVersion=BSCHAT_VERSION,generatedUtc=datetime.now(timezone.utc).isoformat(),
        baseline=dict(version='0.17.1',archive=base_name,sha256=frozen[base_name]),
        supportedVoicePlatform='Windows x64 / Minecraft 1.26.60.05 / LeviLamina 26.60.7',
        bschatPin=PIN,bschatChanges=upstream_changes,
        clientSdk=json.loads((args.client_sdk/'provenance.json').read_text('utf-8')),
        serverSdk=json.loads((e/'server-sdk/provenance.json').read_text('utf-8')),
        hostJarSha256=sha(host),binaries=binaries,tests=jvm_tests,
        freshJvmTests=sum(v['tests'] for v in jvm_tests.values()),nativeRelease=native,
        bschatNativeTests=len(re.findall(r'^\[ RUN  \]',(e/'client-tests-complete.log').read_text('utf-8'),re.M)),
        mailboxFixture='passed; real Windows mapping, generation, contention, freshness and silent cadence',
        outputTap='existing final EngineOutputTap; bounded queue only in Java audio callback',
        microphoneDefault=False,broadcastDefault=False,keysDefault='unbound',
        changedMuoniumNativeSources=delta,nativeHookAndLayoutChanges=False,
        unchangedVendorFiles=len(vendor_now),changedProductionClasses=changed,debugMetadataOnlyClasses=debug_only,addedProductionClasses=added,
        unchangedMediaResources=True,local3ExactDescriptorSha256=sha(descriptor),
        installedGameModified=False,accepted0171Preserved=True,modMenuIncluded=False,
        userConfigurationIncluded=False,githubPublication=False,manualMinecraftAcceptance='pending',
        knownLimitations=['Both endpoints need BSChat and exact LeviLamina 26.60.7',
            'Two-player gameplay, physical microphone and HUD texture rendering remain unverified',
            'Music is mono Opus and follows server permissions and game tick/connection',
            'Music requires the supported 48000 Hz mono negotiated voice format',
            'Existing YouTube playback limitations are unchanged'])
    (ROOT/'docs/PUBLIC-VERIFICATION.json').write_bytes(encoded(verification))
    tracked=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=ROOT).decode().split('\0')
    source={n:(ROOT/n).read_bytes() for n in tracked if n and (ROOT/n).is_file()}
    assert not any(n.split('/')[0] in {'artifacts','build','releases','.git'} for n in source)
    args.output.mkdir(parents=True,exist_ok=False)
    source_name=f'MuoniumPlayerBedrock-{VERSION}-source.zip';archive(args.output/source_name,source)
    addon_source={n:d for n,d in source.items() if n.startswith('integrations/bschat/')}
    with io.BytesIO() as buffer:
        with zipfile.ZipFile(buffer,'w',zipfile.ZIP_DEFLATED) as z:
            for n,d in sorted(addon_source.items()):z.writestr(n,d)
        addon_source_zip=buffer.getvalue()
    def add_addon(files,target,prefix):
        files.update({prefix+n:d for n,d in addons[target].items()})
        files[prefix+'LICENSE']=current['LICENSE']
        files[prefix+'THIRD_PARTY_NOTICES.md']=current['THIRD_PARTY_NOTICES.md']
        for n,d in current.items():
            if n.startswith('licenses/'):files[prefix+n]=d
        for n in ['README.md','UPSTREAM.md','UPSTREAM-FILES.json']:
            files[prefix+n]=(ROOT/'integrations/bschat'/n).read_bytes()
        files[prefix+'sources/BSChat-'+BSCHAT_VERSION+'-source.zip']=addon_source_zip
    for kind in ['full','update']:
        files=read_archive(args.baseline/f'MuoniumPlayerBedrock-0.17.1-levilamina-mc26.60-{kind}.zip')
        manifest_check(files);del files[PREFIX+'PACKAGE-MANIFEST.json']
        for n in list(files):
            if n.startswith(PREFIX+'sources/MuoniumPlayerBedrock-'):del files[n]
        for n in ['README.md','TESTING.md','NOTICE','modmenu.settings.json']:
            files[PREFIX+n]=(ROOT/n).read_bytes()
        files[PREFIX+'interface/modmenu.settings.json']=descriptor
        for n in ['manifest.json','mod.json']:
            meta=json.loads(files[PREFIX+n]);meta['version']=VERSION;files[PREFIX+n]=encoded(meta)
        files[PREFIX+'MuoniumPlayerBedrock.dll']=muonium
        files[PREFIX+'jvm/lib/MuoniumBedrockHost.jar']=host
        files[PREFIX+'docs/UPDATE-'+VERSION+'.md']=(ROOT/f'docs/UPDATE-{VERSION}.md').read_bytes()
        if kind=='full':files[PREFIX+'sources/'+source_name]=(args.output/source_name).read_bytes()
        files['TESTING.md']=(ROOT/'integrations/bschat/README.md').read_bytes()
        files['VERIFICATION.json']=encoded(verification)
        files['INSTALL.md']=files['INSTALL.md'].replace(b'0.17.1',VERSION.encode())+b'\nSee TESTING.md for optional BSChat client/server deployment.\n'
        meta=json.loads(files['CANDIDATE.json']);meta['version']=VERSION;meta['optionalBSChat']=BSCHAT_VERSION;files['CANDIDATE.json']=encoded(meta)
        add_addon(files,'client','mods/bschat/')
        assert not any(n.lower().endswith(('modmenube.dll','modmenubedrock.dll','/config.json','/muoniumplayerbedrock.json','/bschat.json')) for n in files)
        files[PREFIX+'PACKAGE-MANIFEST.json']=encoded(dict(version=VERSION,edition='levilamina',package=kind,
            files={n:dict(bytes=len(d),sha256=sha(d)) for n,d in sorted(files.items())}))
        archive(args.output/f'MuoniumPlayerBedrock-{VERSION}-levilamina-mc26.60-{kind}.zip',files,True)
    server={};add_addon(server,'server','plugins/bschat/')
    server['TESTING.md']=(ROOT/'integrations/bschat/README.md').read_bytes();server['VERIFICATION.json']=encoded(verification)
    server['PACKAGE-MANIFEST.json']=encoded(dict(version=BSCHAT_VERSION,platform='server',
        files={n:dict(bytes=len(d),sha256=sha(d)) for n,d in sorted(server.items())}))
    archive(args.output/f'BSChat-{BSCHAT_VERSION}-server-ll26.60.7.zip',server)
    from verify_public_assets import audit,inspect
    audits={}
    for p in sorted(args.output.glob('*.zip')):
        if p.name.startswith('BSChat-'):
            count={'files':0,'archives':0,'notoFonts':0};inspect(p.name,p.read_bytes(),count);audits[p.name]=count
        else:audits[p.name]=audit(p)
    assert all(sha((args.baseline/n).read_bytes())==h for n,h in frozen.items())
    for n,d in [('VERIFICATION.json',encoded(verification)),('PUBLIC-ASSET-AUDIT.json',encoded(audits)),
                ('TESTING.md',(ROOT/'integrations/bschat/README.md').read_bytes()),
                (f'UPDATE-{VERSION}.md',(ROOT/f'docs/UPDATE-{VERSION}.md').read_bytes())]:
        (args.output/n).write_bytes(d)
    sums={p.name:sha(p.read_bytes()) for p in args.output.iterdir() if p.is_file()}
    (args.output/'SHA256SUMS.txt').write_text(''.join(f'{h}  {n}\n' for n,h in sorted(sums.items())),encoding='ascii')
    print(json.dumps(dict(output=str(args.output),jvmTests=verification['freshJvmTests'],nativeSuites=34,
                         bschatTests=verification['bschatNativeTests'],audits=audits),indent=2))

if __name__=='__main__':main()
