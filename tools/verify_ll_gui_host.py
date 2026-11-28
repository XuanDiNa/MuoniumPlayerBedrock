"""Prove the reported LL GUI host differs from the reviewed console host by one scalar.

Reads the canonical image without writing or executing it. Reports stay local.
"""
from pathlib import Path
import argparse
import hashlib
import json
import struct
import zipfile

CONSOLE_SHA = 'B554A2DA8B2271A4A164AE544A4A447F3A630B5FC2261AFEFE47C7C206826A53'
GUI_SHA = 'FCBCCAEC4E0EE3AB203146BDF45E41878AED7B42237E6DE2C5053F0730C6E10B'


def sha(data):
    return hashlib.sha256(data).hexdigest().upper()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--canonical', type=Path, required=True)
    parser.add_argument('--reports', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    original = args.canonical.read_bytes()
    assert len(original) == 323855872 and sha(original) == CONSOLE_SHA
    with zipfile.ZipFile(args.reports) as archive:
        reports = [json.loads(archive.read(n)) for n in archive.namelist()
                   if n.rsplit('/', 1)[-1].startswith('Muonium-host-evidence-') and n.endswith('.json')]
    assert len(reports) == 2 and reports[0] == reports[1]
    report = reports[0]
    assert report['file'] == dict(bytes=len(original), sha256=GUI_SHA)
    assert report['diagnosticOnly'] and not report['nativeAuthorized']
    assert report['nativeEquivalenceConfirmed'] and report['importsComplete']
    assert report['loaderDirectoriesMatch'] and report['loaderSectionsMatch']
    assert not report['loaderHeaderMatch'] and report['unsectionedRegions'] == []
    for key, count in [('originalSections', 8), ('loaderSections', 2), ('guards', 61), ('bodies', 48), ('slots', 3)]:
        assert len(report[key]) == count and all(row['match'] for row in report[key]), key
    nt = struct.unpack_from('<I', original, 60)[0]
    optional = nt + 24
    subsystem_offset = optional + 68
    assert struct.unpack_from('<H', original, subsystem_offset)[0] == 3
    assert struct.unpack_from('<I', original, optional + 64)[0] == report['pe']['checksum'] == 0x134DC051
    rebuilt = bytearray(original)
    struct.pack_into('<H', rebuilt, subsystem_offset, 2)
    assert sha(rebuilt) == GUI_SHA
    assert sha(rebuilt[:report['pe']['headerSize']]) == report['headerSha256']
    assert sum(a != b for a, b in zip(original[:1024], rebuilt[:1024])) == 1
    for section in report['sections']:
        start, size = section['rawOffset'], section['rawSize']
        assert start >= 1024 and start + size <= len(rebuilt)
        assert sha(rebuilt[start:start + size]) == section['sha256']
    # The exact reconstructed whole-file digest binds all other header fields,
    # directories, section bytes and import data to the player's report.
    assert sha(args.canonical.read_bytes()) == CONSOLE_SHA
    proof = dict(canonicalSha256=CONSOLE_SHA, playerSha256=GUI_SHA,
                 fileBytes=len(original), identicalReports=len(reports),
                 changedField='IMAGE_OPTIONAL_HEADER64.Subsystem',
                 oldSubsystem=3, newSubsystem=2, checksumChanged=False,
                 playerHeaderSha256=report['headerSha256'],
                 matchedSectionHashes=len(report['sections']),
                 originalSections=8, loaderSections=2, nativeGuards=61,
                 nativeBodies=48, tableSlots=3, originalGameFileUnchanged=True,
                 reconstructedInMemoryOnly=True, diagnosticReportsAuthorizeNothing=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(proof, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(proof, indent=2))


if __name__ == '__main__':
    main()
