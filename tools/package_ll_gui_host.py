"""Package a 0.20.2 LL metadata-family compatibility candidate, preserving the release."""
from pathlib import Path
import argparse
import difflib
import hashlib
import json
import re
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PREFIX = 'mods/MuoniumPlayerBedrock/'
GUI_SHA = 'FCBCCAEC4E0EE3AB203146BDF45E41878AED7B42237E6DE2C5053F0730C6E10B'


def sha(data):
    return hashlib.sha256(data).hexdigest().upper()


def archive(path, files):
    with zipfile.ZipFile(path, 'x', zipfile.ZIP_DEFLATED) as z:
        for name, data in sorted(files.items()):
            z.writestr(name, data)
    with zipfile.ZipFile(path) as z:
        assert z.testzip() is None and set(z.namelist()) == set(files)
        for name, data in files.items():
            assert z.read(name) == data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['baseline', 'evidence', 'output', 'audit-tools']:
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    previous = json.loads((args.evidence / 'baseline.json').read_text('utf-8'))
    for name, digest in previous['publishedFileHashes'].items():
        assert sha((args.baseline / name).read_bytes()) == digest, name
    for line in (args.baseline / 'SHA256SUMS.txt').read_text().splitlines():
        digest, name = line.split(None, 1)
        assert sha((args.baseline / name.strip()).read_bytes()) == digest.upper(), name
    native = json.loads((args.evidence / 'native-header-release/results.json').read_text('utf-8'))
    assert len(native['suites']) == 34 and all(v['exit'] == 0 for v in native['suites'].values())
    supplementary = json.loads((args.evidence / 'supplementary-header-release.json').read_text('utf-8'))
    assert len(supplementary) == 5 and all(v['exit'] == 0 for v in supplementary.values())
    families = json.loads((args.evidence / 'header-families-release/results.json').read_text('utf-8'))
    assert len(families) == 9 and all(v['exit'] == 0 and v['originalUnchanged'] for v in families.values())
    proof = json.loads((args.evidence / 'image-proof.json').read_text('utf-8'))
    assert proof['playerSha256'] == GUI_SHA and proof['originalGameFileUnchanged']
    checks = {}
    for name in ['regression-debug.log', 'loader-debug.log', 'header-regression-debug.log']:
        text = (args.evidence / name).read_text('utf-8-sig').strip()
        assert 'passed' in text and 'Traceback' not in text
        checks[name] = text
    assert 'missing exact reported GUI identity' in (args.evidence / 'regression-red.log').read_text('utf-8-sig')
    assert 'unseen GUI/console and checksum variant must be admitted' in (args.evidence / 'header-regression-red.log').read_text('utf-8-sig')
    source_zip = args.baseline / 'MuoniumPlayerBedrock-0.20.2-source.zip'
    changed = {'include/NativeProfile.h', 'include/LoaderPolicy.h', 'include/Runtime.h', 'src/Host.cpp'}
    overlay_names = sorted(changed | {'tests/HostPlayerGuiTests.cpp', 'tests/LoaderPolicyTests.cpp',
                                     'include/HostHeaderFamilies.h', 'tests/HostHeaderVariantTests.cpp',
                                     'tests/HostEvidenceTests.cpp', 'tools/verify_host_header_families.py',
                                     'xmake.lua', 'tools/verify_ll_gui_host.py',
                                     'tools/package_ll_gui_host.py', 'docs/LL-GUI-HOST-0.20.2.md'})
    patch = []
    with zipfile.ZipFile(source_zip) as z:
        assert z.testzip() is None
        actual_changes = {n for n in previous['nativeSourceHashes'] if (ROOT / n).read_bytes() != z.read(n)}
        assert actual_changes == changed, actual_changes
        for n, digest in previous['nativeSourceHashes'].items():
            assert sha(z.read(n)) == digest
        rows = lambda text: re.findall(r'\{GameVersion::[^\n]+\}', text)
        old = rows(z.read('include/NativeProfile.h').decode('utf-8-sig'))
        new = rows((ROOT / 'include/NativeProfile.h').read_text('utf-8-sig'))
        assert new[:-1] == old and len(old) == 20 and GUI_SHA in new[-1]
        for n in overlay_names:
            old_text = z.read(n).decode('utf-8-sig') if n in z.namelist() else ''
            new_text = (ROOT / n).read_text('utf-8-sig')
            patch += difflib.unified_diff(old_text.splitlines(True), new_text.splitlines(True),
                                          fromfile='a/' + n if old_text else '/dev/null', tofile='b/' + n)
    dll = (ROOT / 'build/windows/x64/release/MuoniumPlayerBedrockLL.dll').read_bytes()
    probe = (args.evidence / 'header-dll-probe/MuoniumPlayerBedrock.dll').read_bytes()
    assert dll == probe, 'candidate must be the DLL tested in owned processes'
    sys.path.insert(0, str(args.audit_tools.resolve()))
    from pe_audit import PeImage, audit_runtime_dependencies
    pe = PeImage(dll)
    imports = audit_runtime_dependencies(dll)
    exports = sorted(v for v in pe.exports().values() if v)
    assert {'MPB_GetVersion', 'MPB_GetStatus', 'll_mod_load', 'll_mod_enable', 'll_mod_disable'} <= set(exports)
    assert 'levilamina.dll' in imports and not {'modmenube.dll', 'modmenubedrock.dll'} & set(imports)
    with zipfile.ZipFile(args.baseline / 'MuoniumPlayerBedrock-0.20.2-levilamina-mc26.60-update.zip') as z:
        old_dll = z.read(PREFIX + 'MuoniumPlayerBedrock.dll')
        assert PeImage(old_dll).exports() == pe.exports()
        assert PeImage(old_dll).imports() == pe.imports()
        jar_sha = sha(z.read(PREFIX + 'jvm/lib/MuoniumBedrockHost.jar'))
    report = dict(version='0.20.2', candidate='levilamina-host-compat',
                  minecraft='1.26.60.05', loader='LeviLamina 26.60.7',
                  dll=dict(bytes=len(dll), sha256=sha(dll), exports=exports), imports=imports,
                  previousReleaseDllSha256=sha(old_dll), unchangedHostJarSha256=jar_sha,
                  imageProof=proof, regressionRedConfirmed=True, checks=checks,
                  headerVariantRegressionRedConfirmed=True, verifiedHeaderFamilies=families,
                  variantPolicy=dict(maskedFields={'CheckSum': 4, 'Subsystem': 2},
                                     allowedSubsystems=[2, 3], allOtherFileBytesHashed=True,
                                     rawDigestRetained=True, exactRoutePriority=True,
                                     perPlayerAllowlistingRequiredForMetadataVariants=False),
                  nativeRelease=dict(suites={k: v['exit'] for k, v in native['suites'].items()}, passed=34),
                  supplementaryRelease=supplementary,
                  unchangedNativeFiles=len(previous['nativeSourceHashes']) - len(changed),
                  changedNativeFiles=sorted(changed), priorExactIdentitiesPreserved=20,
                  nativeContractsAndHooksChanged=False, unreviewedCodeOrLoaderFallbackAdmission=False,
                  baselineSource=dict(name=source_zip.name, sha256=sha(source_zip.read_bytes())),
                  baselineFilesPreserved=previous['publishedFileHashes'],
                  manualGameplayAcceptance='pending', autoInstalled=False, published=False)
    # Do not ship developer paths, player logs, game bytes or account material.
    encoded = (json.dumps(report, indent=2) + '\n').encode('utf-8')
    assert b'Users\\' not in encoded and b'C:\\' not in encoded and b'D:\\' not in encoded
    args.output.mkdir(parents=True, exist_ok=False)
    guide = (ROOT / 'docs/LL-GUI-HOST-0.20.2.md').read_bytes()
    archive(args.output / 'MuoniumPlayerBedrock-0.20.2-levilamina-host-compat-update.zip',
            {PREFIX + 'MuoniumPlayerBedrock.dll': dll, 'TESTING.md': guide, 'VERIFICATION.json': encoded})
    overlay = {n: (ROOT / n).read_bytes() for n in overlay_names}
    overlay.update({'BASELINE.json': (json.dumps(report['baselineSource'], indent=2) + '\n').encode(),
                    'HOSTFIX-DIFF.patch': ''.join(patch).encode(), 'LICENSE': (ROOT / 'LICENSE').read_bytes()})
    archive(args.output / 'MuoniumPlayerBedrock-0.20.2-levilamina-host-compat-source-overlay.zip', overlay)
    for name, data in {'MuoniumPlayerBedrock.dll': dll, 'TESTING.md': guide, 'VERIFICATION.json': encoded}.items():
        (args.output / name).write_bytes(data)
    hashes = {p.name: sha(p.read_bytes()) for p in args.output.iterdir() if p.is_file()}
    (args.output / 'SHA256SUMS.txt').write_text(''.join(f'{v}  {k}\n' for k, v in sorted(hashes.items())), encoding='ascii')
    print(json.dumps(dict(output=str(args.output), dll=report['dll'], hashes=hashes), indent=2))


if __name__ == '__main__':
    main()
