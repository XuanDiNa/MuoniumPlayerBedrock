"""Collect distribution notices; never read player state or credentials."""
import hashlib
import json
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'licenses/dependencies'
URLS = {
    'LWJGL-LICENSE.txt': 'https://raw.githubusercontent.com/LWJGL/lwjgl3/3.3.6/LICENSE.md',
    'Skija-LICENSE.txt': 'https://raw.githubusercontent.com/HumbleUI/Skija/master/LICENSE',
    'Types-LICENSE.txt': 'https://raw.githubusercontent.com/HumbleUI/Types/master/LICENSE',
    'JOML-LICENSE.txt': 'https://raw.githubusercontent.com/JOML-CI/JOML/1.10.8/LICENSE',
    'Kotlin-LICENSE.txt': 'https://raw.githubusercontent.com/JetBrains/kotlin/v1.8.20/license/LICENSE.txt',
    'zstd-jni-LICENSE.txt': 'https://raw.githubusercontent.com/luben/zstd-jni/v1.5.6-9/LICENSE',
    'GLFW-LICENSE.txt': 'https://raw.githubusercontent.com/glfw/glfw/3.4/LICENSE.md',
    'STB-LICENSE.txt': 'https://raw.githubusercontent.com/nothings/stb/master/LICENSE',
    'OpenAL-Soft-COPYING.txt': 'https://raw.githubusercontent.com/kcat/openal-soft/master/COPYING',
    # jaad 0.9.4's Maven POM declares Apache-2.0; its SCM URL no longer resolves.
    'JAAD-LICENSE.txt': 'https://www.apache.org/licenses/LICENSE-2.0.txt',
}

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    records = []
    for name, url in URLS.items():
        data = urllib.request.urlopen(url, timeout=30).read()
        if len(data) < 200 or b'<html' in data.lower():
            raise RuntimeError('Invalid license: ' + name)
        (OUT / name).write_bytes(data)
        records.append({'file': name, 'url': url, 'sha256': hashlib.sha256(data).hexdigest()})
    for jar in sorted((ROOT / 'jvm/build/install/MuoniumBedrockHost/lib').glob('*.jar')):
        if jar.name == 'MuoniumBedrockHost.jar':
            continue
        with zipfile.ZipFile(jar) as archive:
            for member in archive.namelist():
                base = member.rsplit('/', 1)[-1]
                if not base.lower().startswith(('license', 'notice', 'copying')):
                    continue
                if not member.startswith('META-INF/') or member.endswith('/'):
                    continue
                data = archive.read(member)
                name = jar.stem + '-' + base
                (OUT / name).write_bytes(data)
                records.append({'file': name, 'jar': jar.name, 'member': member,
                                'sha256': hashlib.sha256(data).hexdigest()})
    (OUT / 'provenance.json').write_text(json.dumps(records, indent=2) + '\n', encoding='utf-8')
    print('Dependency notices collected:', len(records))

if __name__ == '__main__':
    main()
