"""Curated, content-addressed upstream inputs; both vendor trees stay read-only."""
from pathlib import Path
import hashlib,json
ROOT=Path(__file__).resolve().parents[1]
DELTA=ROOT/'vendor/MuoniumPlayer-2.5.2'
CONFIG=ROOT/'tools/upstream25.json'
def selection():
    return json.loads(CONFIG.read_text(encoding='utf-8'))
def verify():
    cfg=selection()
    assert cfg['commit']=='9b4cc7e7bd143315da716075a6d024a0d4c0c81b'
    manifest=json.loads((DELTA/'SOURCE-MANIFEST.json').read_text(encoding='utf-8'))
    records={v['path']:v for v in manifest['files']}
    for rel in cfg['java']+cfg['resources']:
        p=DELTA/rel
        assert hashlib.sha256(p.read_bytes()).hexdigest()==records[rel]['sha256'],rel
    return cfg
