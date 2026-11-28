"""Verify reviewed whole-file metadata families using owned non-game fixtures."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def sha(data):
    return hashlib.sha256(data).hexdigest().upper()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--instances', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--mode', choices=['debug', 'release'], default='release')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    rows = re.findall(r'\{GameVersion::[^\n]+\}', (ROOT / 'include/NativeProfile.h').read_text())
    identities = [re.search(r'"([^"]+)","([A-F0-9]{64})",(\d+),', row).groups() for row in rows]
    known = {digest: (i, name) for i, (name, digest, size) in enumerate(identities)}
    families = re.findall(r'\{(\d+),"([A-F0-9]{64})"\}', (ROOT / 'include/HostHeaderFamilies.h').read_text())
    declared = {digest: int(i) for i, digest in families}
    found = {}
    for directory in sorted(args.instances.iterdir()):
        if not directory.is_dir() or not directory.name.startswith('1.26.'):
            continue
        for path in sorted(directory.glob('Minecraft.Windows.exe*')):
            if not path.is_file():
                continue
            data = bytearray(path.read_bytes())
            raw = sha(data)
            if raw not in known:
                continue  # Unknown files never generate trusted reference hashes.
            nt = struct.unpack_from('<I', data, 60)[0]
            data[nt + 24 + 64:nt + 24 + 70] = b'\0' * 6
            digest = sha(data)
            if digest in declared and known[raw][0] == declared[digest]:
                found[digest] = (path, raw, known[raw][1])
    assert set(found) == set(declared), 'Every family requires its exact reviewed anchor file'
    results = {}
    binary = ROOT / f'build/windows/x64/{args.mode}/HostHeaderVariantTests.exe'
    for fingerprint, (path, raw, name) in sorted(found.items()):
        print(name, flush=True)
        run = subprocess.run([str(binary), str(path)], cwd=args.output, capture_output=True, timeout=150)
        output = run.stdout.decode('utf-8', 'replace') + run.stderr.decode('utf-8', 'replace')
        (args.output / (name + '.log')).write_text(output, encoding='utf-8')
        assert sha(path.read_bytes()) == raw, 'Original game image must remain unchanged'
        results[name] = dict(exit=run.returncode, output=output, referenceSha256=raw,
                             headerFamilySha256=fingerprint, originalUnchanged=True)
        (args.output / 'results.json').write_text(json.dumps(results, indent=2) + '\n', encoding='utf-8')
    failures = [name for name, row in results.items() if row['exit']]
    print(json.dumps(dict(passed=len(results) - len(failures), failures=failures)))
    assert not failures


if __name__ == '__main__':
    main()
