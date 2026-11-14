"""Read-only audit of public source trees or ZIP/JAR distributions."""
from pathlib import Path
import hashlib, io, json, re, sys, zipfile

FONTS = {
    'sfregular.otf': 'b85c38ecea8a7cfb39c24e395a4007474fa5a4fc864f6ee33309eb4948d232d5',
    'sfbold.otf': 'c976e4b1b99edc88775377fcc21692ca4bfa46b6d6ca6522bfda505b28ff9d6a',
    'pf_normal.ttf': '2c76254f6fc379fddfce0a7e84fb5385bb135d3e399294f6eeb6680d0365b74b',
    'pf_middleblack.ttf': 'b5f0d1a190a7f9b43c310a8850630af12553df32c4c050543f9059732d9b4c0a',
}
SKIP = {'.git', '.gradle', '.xmake', 'build', 'artifacts', 'dist', 'releases', '__pycache__'}

def inspect(name, data, counters, depth=0):
    assert depth < 6, ('archive nesting', name)
    base = name.replace('\\', '/').rsplit('/', 1)[-1]
    if Path(base).suffix.lower() in {'.json','.md','.txt','.py','.cpp','.h','.java','.gradle','.lua','.ps1'}:
        assert not re.search(rb'(?:C:[/\\]+Users[/\\]+Lenovo|D:[/\\]+LeviLauncher_Construct|gh[pousr]_[A-Za-z0-9]{25,})', data), ('private path or credential', name)
    if base in FONTS:
        assert hashlib.sha256(data).hexdigest() == FONTS[base], ('not a public Noto font', name)
        counters['notoFonts'] += 1
    if name.lower().endswith(('.jar', '.zip')):
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            assert archive.testzip() is None, ('CRC failure', name)
            assert len(archive.namelist()) == len(set(archive.namelist())), ('duplicate entries', name)
            for entry in archive.infolist():
                if not entry.is_dir():
                    inspect(name + '!' + entry.filename, archive.read(entry), counters, depth + 1)
        counters['archives'] += 1
    counters['files'] += 1

def audit(target):
    target = Path(target)
    counters = {'files': 0, 'archives': 0, 'notoFonts': 0}
    if target.is_file():
        inspect(target.name, target.read_bytes(), counters)
    else:
        for p in target.rglob('*'):
            if p.is_file() and not SKIP.intersection(p.relative_to(target).parts):
                inspect(p.relative_to(target).as_posix(), p.read_bytes(), counters)
    assert counters['notoFonts'] >= 4, counters
    return counters

if __name__ == '__main__':
    print(json.dumps(audit(sys.argv[1] if len(sys.argv) > 1 else '.'), indent=2))
