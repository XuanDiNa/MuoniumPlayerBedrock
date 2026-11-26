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
    assert cfg['commit']=='4011d03b535cc40fa54b2cb8324cd5405eb9d6e0'
    manifest=json.loads((DELTA/'SOURCE-MANIFEST.json').read_text(encoding='utf-8'))
    assert manifest['commit']==cfg['commit']
    assert manifest['version']==cfg['version']=='2.6.1' and cfg['branch']=='main'
    records={v['path']:v for v in manifest['files']}
    for rel in cfg['java']+cfg['resources']:
        p=DELTA/rel
        assert hashlib.sha256(p.read_bytes()).hexdigest()==records[rel]['sha256'],rel
    return cfg
