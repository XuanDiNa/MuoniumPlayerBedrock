"""Package a local-only candidate, preserving settings and previous releases."""
from pathlib import Path
import argparse, hashlib, json, subprocess, sys, zipfile
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
VERSION='0.13.0'
PREFIX='mods/MuoniumPlayerBedrock/'

def sha(data): return hashlib.sha256(data).hexdigest().upper()
def encoded(value): return (json.dumps(value,indent=2,ensure_ascii=False)+'\n').encode('utf-8')

def archive(path,files):
    with zipfile.ZipFile(path,'x',zipfile.ZIP_DEFLATED) as z:
        for name,data in sorted(files.items()):z.writestr(name,data)
    with zipfile.ZipFile(path) as z:
        assert z.testzip() is None and len(z.namelist())==len(set(z.namelist()))
        assert set(z.namelist())==set(files)
        for name,data in files.items():assert z.read(name)==data,name

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--output',required=True,type=Path)
    p.add_argument('--menu-source',required=True,type=Path)
    p.add_argument('--evidence',required=True,type=Path)
    a=p.parse_args();menu=a.menu_source.resolve();evidence=a.evidence.resolve()
    report=json.loads((evidence/'final-native/results.json').read_text())
    for mode in ['debug','release']:
        assert len(report[mode])==40 and all(v['exit']==0 for v in report[mode].values())
    assert report['unsupportedHost']['exit']==report['unsupportedLeviHost']['exit']==0
    suites=[]
    for folder in ['test']:
        for f in (ROOT/'jvm/build/test-results'/folder).glob('TEST-*.xml'):
            t=ET.parse(f).getroot();assert int(t.attrib['failures'])==int(t.attrib['errors'])==0,f
            suites.append({'name':t.attrib['name'],'tests':int(t.attrib['tests']),'skipped':int(t.attrib['skipped'])})
    expected={'ExternalScreenLifecycleTest','OverlaySessionTest','OverlayPolicyTest','FileDialogLifecycleTest','UiPresentationTest','HostWorkspaceTest'}
    assert expected=={s['name'].rsplit('.',1)[-1] for s in suites},suites
    jar=(ROOT/'jvm/build/install/MuoniumBedrockHost/lib/MuoniumBedrockHost.jar').read_bytes()
    assert jar==(ROOT/'jvm/build/libs/MuoniumBedrockHost.jar').read_bytes()
    assert sha(jar)==json.loads((evidence/'jvm-final/results.json').read_text())['jarSha256'].upper()
    sys.path.insert(0,str(menu/'tools'))
    from package_local import pe_exports
    from pe_audit import audit_runtime_dependencies
    render=json.loads((evidence/'render-validation.json').read_text())
    assert render['status']=='unavailable' and render['reason']=='WGL: The driver does not appear to support OpenGL'
    verification={'version':VERSION,'nativeSuites':{'debug':40,'release':40},'jvmSuites':suites,'renderValidation':render,
                  'nativeUnsupportedHostRefusal':True,'manualMinecraftAcceptance':'pending',
                  'gameOrInstalledFilesModified':False,'githubPublication':False,
                  'nativeGuards':json.loads((evidence/'preserved-native-guards.json').read_text()),
                  'local3Compatibility':json.loads((evidence/'local3-audit.json').read_text()),
                  'settingsPreservation':json.loads((evidence/'settings-preservation.json').read_text()),
                  'modmenuUpdated':False,'externalEntry':'local4 separate Workspace buttons and ordinary Configure; local3 owned Configure button fallback',
                  'workspaceNavigation':'Esc dismisses one layer; completed outermost exit returns to Workspace; extra return banner/button removed',
                  'workspaceSdkProvenance':json.loads((ROOT/'vendor/modmenube-local4-workspace/PROVENANCE.json').read_text()),
                  'hostJarSha256':sha(jar),'binaries':{}}
    a.output.mkdir(parents=True,exist_ok=False)
    # A new update never distributes an initial config over the installed one.
    common={PREFIX+n:(ROOT/n).read_bytes() for n in ['LICENSE','NOTICE']}
    common[PREFIX+'interface/modmenu.settings.json']=(ROOT/'modmenu.settings.json').read_bytes()
    common[PREFIX+'modmenu.settings.json']=(ROOT/'modmenu.settings.json').read_bytes()
    common[PREFIX+'modmenu.json']=(ROOT/'modmenu.json').read_bytes()
    common[PREFIX+'jvm/lib/MuoniumBedrockHost.jar']=jar
    common['TESTING.md']=(ROOT/'TESTING.md').read_bytes()
    for n in ['INSTALL.md','modmenube-migration.md','DEPENDENCIES.md']:
        common['docs/'+n]=(ROOT/'docs'/n).read_bytes()
    for edition,binary in [('vanilla','MuoniumPlayerBedrock'),('levilamina','MuoniumPlayerBedrockLL')]:
        dll=(ROOT/f'build/windows/x64/release/{binary}.dll').read_bytes()
        imports=audit_runtime_dependencies(dll)
        assert not any(n.lower() in {'modmenube.dll','modmenubedrock.dll'} for n in imports), 'Optional menu must not be statically imported'
        exports=pe_exports(dll);assert {'MPB_GetStatus','MPB_GetVersion'}<=set(exports)
        assert not any(n.startswith(('Fixture','Owner','WorkspaceBegin','WorkspaceMode','Frozen_')) for n in exports), 'Test fixture leaked into release'
        if edition=='levilamina':assert {'ll_mod_load','ll_mod_disable'}<=set(exports)
        else:assert 'll_mod_load' not in exports
        manifest=json.loads((ROOT/'manifest.json').read_text())
        metadata=json.loads((ROOT/'mod.json').read_text())
        if edition=='levilamina':
            manifest['type']='native';manifest['optionalDependencies']=[{'name':'arke'}]
            metadata['minecraft']=['1.26.60.05']
        assert manifest['name']=='MuoniumPlayerBedrock' and manifest['version']==metadata['version']==VERSION
        sidecar=json.loads(common[PREFIX+'interface/modmenu.settings.json']);assert VERSION in sidecar['plugin']['versions']
        verification['binaries'][edition]={'bytes':len(dll),'sha256':sha(dll),'exports':exports,'runtimeImports':imports}
        files=dict(common);files[PREFIX+'MuoniumPlayerBedrock.dll']=dll
        files[PREFIX+'manifest.json']=encoded(manifest);files[PREFIX+'mod.json']=encoded(metadata)
        files['CANDIDATE.json']=encoded({'version':VERSION,'edition':edition,'runtime':'Existing complete public2 or equivalent runtime required',
                'modmenu':'ModMenuBE local4 Workspace; local3 Configure fallback. No menu DLL included or changed.','manualMinecraftAcceptance':'pending'})
        assert not any(n.endswith('MuoniumPlayerBedrock.json') for n in files)
        suffix='mc26.45-26.51-26.52-26.60' if edition=='vanilla' else 'mc26.60'
        archive(a.output/f'MuoniumPlayerBedrock-{VERSION}-{edition}-{suffix}-update.zip',files)
        for n,data in files.items():
            dest=a.output/edition/n;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(data)
    names=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=ROOT).decode().split('\0')
    sources={n:(ROOT/n).read_bytes() for n in names if n and (ROOT/n).is_file()}
    archive(a.output/f'MuoniumPlayerBedrock-{VERSION}-source.zip',sources)
    (a.output/'VERIFICATION.json').write_bytes(encoded(verification))
    (a.output/'TESTING.md').write_bytes(common['TESTING.md'])
    checks={f.name:{'bytes':f.stat().st_size,'sha256':sha(f.read_bytes())} for f in a.output.glob('*.zip')}
    (a.output/'SHA256SUMS.txt').write_text(''.join(f'{v["sha256"]}  {n}\n' for n,v in sorted(checks.items())),encoding='ascii')
    print(json.dumps({'directory':str(a.output),'packages':checks,'jvmTests':sum(s['tests'] for s in suites)},indent=2))

if __name__=='__main__':main()
