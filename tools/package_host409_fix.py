"""Package a DLL-only 0.14.7 LL hostfix2, preserving accepted releases."""
from pathlib import Path
import argparse, difflib, json, re, sys, zipfile
from package_host_diagnostics import archive, sha, ROOT, PREFIX

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['baseline','evidence','proof','output','audit-tools']:p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();baseline_hashes={}
    for line in (a.baseline/'SHA256SUMS.txt').read_text().splitlines():
        digest,name=line.split(None,1);name=name.strip();actual=sha((a.baseline/name).read_bytes())
        assert actual==digest.upper(),name;baseline_hashes[name]=actual
    previous=json.loads((a.baseline/'VERIFICATION.json').read_text('utf-8'))
    native=json.loads((a.evidence/'native-release/results.json').read_text())
    assert len(native['suites'])==34 and all(s['exit']==0 for s in native['suites'].values())
    proof=json.loads(a.proof.read_text('utf-8'))
    assert proof['playerSha256']=='409C9597C5C973B3FA9B11204612850F4BAE8F83270C7692DCC783C491FCF04C'
    assert proof['originalSectionsByteIdentical'] and proof['completeImportSymbolGraphMatches']
    assert (proof['nativeGuards'],proof['nativeBodiesWithPdataBoundaries'],proof['tableSlots'])==(61,48,3)
    checks={}
    for name in ['host-evidence-debug.log','host-evidence-release.log','host-diagnostics-debug.log',
                 'host-diagnostics-release.log','host-player409-debug.log','host-player409-release.log',
                 'diagnostic-dll-green.log','evidence-dll-green.log']:
        text=(a.evidence/name).read_text('utf-8')
        assert 'passed' in text and 'Traceback' not in text and 'error:' not in text,name
        checks[name]=text.strip()
    baseline_source=a.baseline/'MuoniumPlayerBedrock-0.14.7-source.zip'
    changed={'src/Host.cpp','src/Plugin.cpp','include/Runtime.h','include/NativeProfile.h','include/LoaderPolicy.h'}
    overlays=sorted(changed|{'include/HostEvidence.h','include/HostEvidenceReference.h','src/HostEvidence.cpp',
        'xmake.lua','tests/HostDiagnosticsTests.cpp','tests/HostEvidenceTests.cpp','tests/HostPlayer409Tests.cpp',
        'tests/LoaderPolicyTests.cpp','tools/verify_host_diagnostics.py','tools/verify_host_evidence_dll.py',
        'tools/verify_2660_received_image.py','tools/generate_host_evidence_reference.py',
        'tools/package_host_diagnostics.py','tools/package_host409_fix.py',
        'docs/LL-HOST-DIAGNOSTICS.md','docs/LL-HOST-EVIDENCE.md','docs/LL-HOSTFIX409.md'})
    unchanged={};patch=[]
    with zipfile.ZipFile(baseline_source) as z:
        assert z.testzip() is None
        for name in z.namelist():
            if name.startswith(('src/','include/')) and name not in changed:
                data=(ROOT/name).read_bytes();assert data==z.read(name),name;unchanged[name]=sha(data)
        # All old exact identities and their metadata must be preserved in order.
        rows=lambda text:re.findall(r'\{GameVersion::[^\n]+\}',text)
        old=rows(z.read('include/NativeProfile.h').decode());new=rows((ROOT/'include/NativeProfile.h').read_text())
        assert new[:-1]==old and len(new)==len(old)+1 and proof['playerSha256'] in new[-1]
        for name in overlays:
            old=z.read(name).decode('utf-8-sig') if name in z.namelist() else ''
            new=(ROOT/name).read_text('utf-8-sig')
            patch+=difflib.unified_diff(old.splitlines(True),new.splitlines(True),
                fromfile='a/'+name if old else '/dev/null',tofile='b/'+name)
    jar=(ROOT/'jvm/build/libs/MuoniumBedrockHost.jar').read_bytes()
    assert previous['version']=='0.14.7' and sha(jar)==previous['hostJarSha256']
    dll=(ROOT/'build/windows/x64/release/MuoniumPlayerBedrockLL.dll').read_bytes()
    sys.path.insert(0,str(a.audit_tools.resolve()))
    from pe_audit import audit_runtime_dependencies
    from package_local import pe_exports
    imports,exports=audit_runtime_dependencies(dll),pe_exports(dll)
    assert {'MPB_GetVersion','MPB_GetStatus','ll_mod_load','ll_mod_enable','ll_mod_disable'}<=set(exports)
    assert 'levilamina.dll' in imports and not {'modmenube.dll','modmenubedrock.dll'}&set(imports)
    report=dict(version='0.14.7',candidate='ll-hostfix2',minecraft='1.26.60.05',loader='LeviLamina 26.60.7',
        scope='Exact received 409C host admission plus hash-only local diagnostics; DLL-only',
        dll=dict(bytes=len(dll),sha256=sha(dll),exports=exports),imports=imports,
        unchangedHostJarSha256=sha(jar),receivedImageProof=proof,nativeRelease=native,checks=checks,
        unchangedNativeSources=unchanged,oneExactIdentityAdded=proof['playerSha256'],
        nativeContractsAndGuardsChanged=False,unknownImageFallbackAdmission=False,
        original0147FilesPreserved=baseline_hashes,manualGameplayAcceptance='pending',
        gameModified=False,menuIncluded=False,published=False)
    a.output.mkdir(parents=True,exist_ok=False)
    encoded=(json.dumps(report,indent=2)+'\n').encode();guide=(ROOT/'docs/LL-HOSTFIX409.md').read_bytes()
    source={name:(ROOT/name).read_bytes() for name in overlays}
    source.update({'BASELINE.json':(json.dumps(dict(sourceZip=baseline_source.name,sha256=sha(baseline_source.read_bytes())),indent=2)+'\n').encode(),
        'HOSTFIX-DIFF.patch':''.join(patch).encode(),'LICENSE':(ROOT/'LICENSE').read_bytes()})
    archive(a.output/'MuoniumPlayerBedrock-0.14.7-levilamina-hostfix2-source-overlay.zip',source)
    archive(a.output/'MuoniumPlayerBedrock-0.14.7-levilamina-hostfix2.zip',
        {PREFIX+'MuoniumPlayerBedrock.dll':dll,'TESTING.md':guide,'VERIFICATION.json':encoded})
    for name,data in {'MuoniumPlayerBedrock.dll':dll,'TESTING.md':guide,'VERIFICATION.json':encoded}.items():
        (a.output/name).write_bytes(data)
    hashes={p.name:sha(p.read_bytes()) for p in a.output.iterdir() if p.is_file()}
    (a.output/'SHA256SUMS.txt').write_text(''.join(f'{v}  {k}\n' for k,v in sorted(hashes.items())),encoding='ascii')
    print(json.dumps(dict(output=str(a.output),dll=report['dll'],checksums=hashes),indent=2))

if __name__=='__main__':main()
