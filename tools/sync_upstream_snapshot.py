"""Refresh the curated snapshot using a pinned Git tree and verified blob IDs.

The original source is kept intact; public font substitutions are retained.
Downloads are staged before any snapshot file is replaced.
"""
from pathlib import Path
import concurrent.futures
import hashlib
import json
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
COMMIT = '4011d03b535cc40fa54b2cb8324cd5405eb9d6e0'
VERSION = '2.6.1'
ART = ROOT / 'artifacts/upstream261-20261122'
VENDOR = ROOT / 'vendor/MuoniumPlayer-2.5.2'  # Stable historical build input path.

def blob_hash(data):
    return hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()

def selected(path):
    return path in ('LICENSE', 'NOTICE') or any(path.startswith(prefix) for prefix in (
        'muonium-common/src/main/', 'muonium-common/src/test/',
        '26.3/src/main/', '26.3/src/test/',
        'mpparty-common/src/main/java/', 'mpserver-core/src/main/java/',
        '1.21.1/src/main/java/com/muoniumplayer/compat/lwjgl2/'))

def main():
    tree = json.loads((ART / 'tree.json').read_text(encoding='utf-8-sig'))
    assert not tree.get('truncated'), 'Incomplete Git tree'
    assert tree['sha'] == COMMIT
    before = json.loads((VENDOR / 'SOURCE-MANIFEST.json').read_text(encoding='utf-8'))
    backup = ART / 'before-snapshot'
    backup.mkdir(parents=True, exist_ok=True)
    manifest_backup = backup / 'SOURCE-MANIFEST.json'
    if not manifest_backup.exists():
        manifest_backup.write_bytes((VENDOR / 'SOURCE-MANIFEST.json').read_bytes())
    overrides = {item['path'] for item in before['publicationOverrides']}
    preserved = overrides | {item['path'] for item in before['files']
                            if '/META-INF/licenses/public-fonts/' in item['path']}
    entries = {item['path']: item for item in tree['tree']
               if item['type'] == 'blob' and selected(item['path'])}
    pending = []
    unchanged = []
    for rel, item in entries.items():
        if rel in preserved:
            continue
        target = VENDOR / rel
        if target.is_file() and blob_hash(target.read_bytes()) == item['sha']:
            unchanged.append(rel)
        else:
            pending.append((rel, item))
    print(f'Pinned tree: {len(entries)} files; {len(unchanged)} identical; {len(pending)} downloads', flush=True)
    staged = ART / 'snapshot-files'
    def fetch(pair):
        rel, item = pair
        target = staged / rel
        if target.is_file() and blob_hash(target.read_bytes()) == item['sha']:
            return rel
        for attempt in range(4):
            try:
                url = f'https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-multiversion/{COMMIT}/{urllib.parse.quote(rel)}'
                with urllib.request.urlopen(url, timeout=35) as response:
                    data = response.read()
                assert blob_hash(data) == item['sha'], rel
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                return rel
            except Exception:
                if attempt == 3:
                    raise
                time.sleep(attempt + 1)
    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
        for index, rel in enumerate(pool.map(fetch, pending), 1):
            if index % 20 == 0:
                print(f'Verified {index}/{len(pending)} downloads', flush=True)
    changed = []
    for rel, item in pending:
        target = VENDOR / rel
        saved = backup / rel
        if target.is_file() and not saved.exists():
            saved.parent.mkdir(parents=True, exist_ok=True)
            saved.write_bytes(target.read_bytes())
        target.parent.mkdir(parents=True, exist_ok=True)
        data = (staged / rel).read_bytes()
        assert blob_hash(data) == item['sha']
        target.write_bytes(data)
        changed.append(rel)
    removed = []
    for item in before['files']:
        rel = item['path']
        if rel not in entries and rel not in preserved:
            target = (VENDOR / rel).resolve()
            assert target.is_relative_to(VENDOR.resolve()), rel
            if not target.exists():
                removed.append(rel)
                continue
            assert hashlib.sha256(target.read_bytes()).hexdigest() == item['sha256']
            target.unlink()
            removed.append(rel)
    files = []
    for rel in sorted(set(entries) | preserved):
        data = (VENDOR / rel).read_bytes()
        record = {'path': rel, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
        if rel in entries:
            record['upstreamBlob'] = entries[rel]['sha']
            if rel not in overrides:
                assert blob_hash(data) == entries[rel]['sha'], rel
        files.append(record)
    manifest = {'commit': COMMIT, 'version': VERSION, 'branch': 'main',
                'files': files, 'publicationOverrides': before['publicationOverrides']}
    (VENDOR / 'SOURCE-MANIFEST.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    report = {'commit': COMMIT, 'version': VERSION, 'changed': changed, 'removed': removed,
              'identical': len(unchanged), 'files': len(files), 'fontOverrides': sorted(overrides)}
    (ART / 'snapshot-refresh.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(f'Refreshed {len(files)} curated files; {len(changed)} changed; {len(removed)} removed', flush=True)

if __name__ == '__main__':
    main()
