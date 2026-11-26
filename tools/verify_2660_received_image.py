"""Read-only review of the exact received 409C host; never executes an input."""
from pathlib import Path
import argparse, hashlib, json, struct
from generate_host_evidence_reference import pe, region, sha, ORIGINAL, LOADER

ROOT=Path(__file__).resolve().parents[1]
PLAYER='409C9597C5C973B3FA9B11204612850F4BAE8F83270C7692DCC783C491FCF04C'

def imports(b, sections, dirs):
    def read(rva,n):return region(b,sections,rva,n)
    def string(rva):
        result=bytearray()
        for i in range(512):
            c=read(rva+i,1)
            if c==b'\0':return result.decode('ascii')
            result.extend(c)
        raise ValueError('unterminated import name')
    start=dirs[1]['rva'];result=[]
    for i in range(256):
        oft,stamp,chain,name,iat=struct.unpack('<IIIII',read(start+i*20,20))
        if not any((oft,stamp,chain,name,iat)):return result
        symbols=[]
        for j in range(16384):
            value=struct.unpack('<Q',read((oft or iat)+8*j,8))[0]
            if not value:break
            symbols.append('#'+str(value&0xffff) if value>>63 else string(value+2))
        else:raise ValueError('unterminated import thunk')
        result.append(dict(dll=string(name),iatRva=iat,symbols=symbols))
    raise ValueError('unterminated import directory')

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['original','loader','player','output']:p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();original=a.original.read_bytes();loader=a.loader.read_bytes();player=a.player.read_bytes()
    assert (sha(original),sha(loader),sha(player))==(ORIGINAL,LOADER,PLAYER)
    sections,dirs=pe(player);rs,rd=pe(original);ls,ld=pe(loader)
    assert sections[:8]==rs==ls[:8] and len(sections)==10
    for s in rs:
        start,size=s['rawOffset'],s['rawSize']
        assert player[start:start+size]==original[start:start+size]==loader[start:start+size]
    nt=struct.unpack_from('<I',player,60)[0];opt=nt+24
    assert len(player)==323855872 and struct.unpack_from('<H',player,nt+4)[0]==0x8664
    assert struct.unpack_from('<I',player,nt+8)[0]==0x6ad79ee4
    assert struct.unpack_from('<I',player,opt+56)[0]==0x13847000
    assert struct.unpack_from('<Q',player,opt+24)[0]==0x140000000
    assert struct.unpack_from('<I',player,opt+16)[0]==0xeea5290
    assert struct.unpack_from('<H',player,opt+68)[0]==3
    # The generated reference contains only fingerprints from the reviewed
    # original and loader; this tool independently reads the received file.
    text=(ROOT/'include/HostEvidenceReference.h').read_text()
    ref=json.loads(text.split('R"MPBREF(',1)[1].split(')MPBREF"',1)[0])
    pd=region(player,sections,next(s['rva'] for s in sections if s['name']=='.pdata'),next(s['rawSize'] for s in sections if s['name']=='.pdata'))
    functions={start:end for start,end,_ in struct.iter_unpack('<III',pd[:len(pd)//12*12]) if start and end>start}
    for key in ['guards','bodies','slots']:
        for row in ref[key]:
            assert sha(region(player,sections,row['rva'],row['bytes']))==row['sha256'],(key,row['rva'])
            if key=='bodies':assert functions[row['rva']]==row['rva']+row['bytes']
    current,known,vanilla=imports(player,sections,dirs),imports(loader,ls,ld),imports(original,rs,rd)
    # Compare the complete import name/ordinal graph, and retain every original
    # game's IAT address. Loader-only thunk addresses may move with its sections.
    graph=lambda rows: sorted((r['dll'].lower(),r['symbols']) for r in rows)
    assert graph(current)==graph(known)
    for row in vanilla:assert row in current,(row['dll'],'original import/IAT changed')
    added=[r for r in current if r not in vanilla]
    assert graph(added)==[('levilauncher.dll',['LeviLauncherEntry']),('preloader.dll',['pl_resolve_symbol'])]
    names=[s['name'] for s in sections[8:]]
    assert names==['.levildr','.nimp']
    loader_section=next(s for s in sections if s['name']=='.levildr')
    assert b'LeviLauncherEntry' in region(player,sections,loader_section['rva'],loader_section['rawSize'])
    r=dict(playerSha256=PLAYER,bytes=len(player),originalSha256=ORIGINAL,reviewedLoaderSha256=LOADER,
        originalSectionsByteIdentical=True,sectionHeadersAndHashes=sections,
        nativeGuards=len(ref['guards']),nativeBodiesWithPdataBoundaries=len(ref['bodies']),tableSlots=len(ref['slots']),
        originalImportAndIatRowsUnchanged=len(vanilla),completeImportSymbolGraphMatches=True,
        importedDlls=[r['dll'] for r in current],loaderSectionOrder=names,
        difference='Launcher section inserted before rebuilt import section; original eight sections and all reviewed native paths are identical.',
        gameExecuted=False,inputModified=False,manualGameplayAcceptance='pending')
    a.output.write_text(json.dumps(r,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in r.items() if k not in ['sectionHeadersAndHashes','importedDlls']},indent=2))

if __name__=='__main__':main()
