"""Generate hash-only diagnostic references from the reviewed 26.60 images."""
from pathlib import Path
import argparse, hashlib, json, re, struct

ROOT = Path(__file__).resolve().parents[1]
ORIGINAL = '988F6A61403B4381AA41E6C66D64CCE441B14E561A9B10F647C2758EF3F2672A'
LOADER = 'B554A2DA8B2271A4A164AE544A4A447F3A630B5FC2261AFEFE47C7C206826A53'

def sha(b):
    return hashlib.sha256(b).hexdigest().upper()

def pe(b):
    nt = struct.unpack_from('<I', b, 60)[0]
    count, optional = struct.unpack_from('<H', b, nt + 6)[0], nt + 24
    section_start = optional + struct.unpack_from('<H', b, nt + 20)[0]
    sections = []
    for i in range(count):
        p = section_start + 40 * i
        vs, rva, size, offset = struct.unpack_from('<IIII', b, p + 8)
        sections.append(dict(name=b[p:p+8].split(b'\0')[0].decode('ascii'),
            rva=rva, virtualSize=vs, rawOffset=offset, rawSize=size,
            characteristics=struct.unpack_from('<I', b, p + 36)[0], sha256=sha(b[offset:offset+size])))
    directories = [dict(index=i, rva=struct.unpack_from('<I', b, optional+112+8*i)[0],
        size=struct.unpack_from('<I', b, optional+116+8*i)[0]) for i in range(16)]
    return sections, directories

def region(b, sections, rva, length):
    for s in sections:
        delta = rva-s['rva']
        if 0 <= delta and delta+length <= s['rawSize']:
            return b[s['rawOffset']+delta:s['rawOffset']+delta+length]
    raise ValueError(hex(rva))

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--original', type=Path, required=True)
    p.add_argument('--loader', type=Path, required=True)
    p.add_argument('--bodies', type=Path, required=True)
    p.add_argument('--output', type=Path, default=ROOT/'include/HostEvidenceReference.h')
    a=p.parse_args(); original=a.original.read_bytes(); loader=a.loader.read_bytes()
    assert sha(original)==ORIGINAL and sha(loader)==LOADER
    sections, _=pe(original); loaded, directories=pe(loader)
    assert len(sections)==8 and loaded[:8]==sections
    guards=[]
    for name in ['World','Environment','Entity','Network','Cinematic']:
        text=(ROOT/f'src/{name}Guards2660.inl').read_text()
        for rva, data in re.findall(r'\{(0x[0-9A-Fa-f]+),\{([^}]+)\}\}',text):
            code=bytes(int(x.strip(),16) for x in data.split(','))
            rva=int(rva,16)
            assert len(code)==32 and region(original,sections,rva,32)==code
            guards.append(dict(group=name,rva=rva,bytes=32,sha256=sha(code)))
    manifest=json.loads(a.bodies.read_text());assert manifest['image']==ORIGINAL
    bodies=[]
    for start,row in manifest['records'].items():
        rva,end=int(start,16),int(row['end'],16)
        digest=sha(region(original,sections,rva,end-rva))
        assert digest==row['sha256'].upper()
        bodies.append(dict(rva=rva,bytes=end-rva,sha256=digest))
    slots=[]
    for rva,target in [(0xF20ECF8,0x4CCF520),(0xF188A20+31*8,0x32D3E30),(0xF2CE790+132*8,0x232BA70)]:
        value=struct.unpack('<Q',region(original,sections,rva,8))[0]
        assert value==0x140000000+target
        slots.append(dict(rva=rva,bytes=8,sha256=sha(struct.pack('<Q',value))))
    assert (len(guards),len(bodies),len(slots))==(61,48,3)
    nt=struct.unpack_from('<I',loader,60)[0]
    header_size=struct.unpack_from('<I',loader,nt+24+60)[0]
    reference=dict(schema=1,originalSha256=ORIGINAL,loaderSha256=LOADER,loaderHeaderSha256=sha(loader[:header_size]),
        machine=0x8664,timestamp=0x6AD79EE4,imageBase=0x140000000,
        sections=sections,loaderSections=loaded[8:],loaderDirectories=directories,
        guards=guards,bodies=bodies,slots=slots)
    encoded=json.dumps(reference,separators=(',',':'),ensure_ascii=True)
    a.output.write_text('// Generated hash-only evidence; not a host admission table.\n#pragma once\n'
        'namespace mp { inline constexpr char HostEvidenceReference[]=R"MPBREF('+encoded+')MPBREF"; }\n',encoding='utf-8')
    print('Verified 8 sections, 61 guards, 48 native bodies and 3 table slots; hash-only reference generated')

if __name__=='__main__':main()
