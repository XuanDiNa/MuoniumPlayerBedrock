"""Assemble the verified LL camera candidate with the complete existing runtime."""
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import json
import zipfile


PREFIX = 'mods/MuoniumPlayerBedrock/'
ROOT = Path(__file__).resolve().parents[1]


def sha(data):
    return hashlib.sha256(data).hexdigest().upper()


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def checked_file(folder, name):
    sums = dict((line.split('  ', 1)[1], line.split('  ', 1)[0].upper())
                for line in (folder / 'SHA256SUMS.txt').read_text().splitlines())
    data = (folder / name).read_bytes()
    assert sha(data) == sums[name], name
    return data


def read_archive(path):
    with zipfile.ZipFile(path) as z:
        assert z.testzip() is None, path
        names = z.namelist()
        assert len(names) == len(set(n.casefold() for n in names))
        for name in names:
            p = PurePosixPath(name)
            assert not p.is_absolute() and '..' not in p.parts
            assert '\\' not in name and ':' not in name, name
        return {name: z.read(name) for name in names}


def check_manifest(files):
    name = PREFIX + 'PACKAGE-MANIFEST.json'
    entries = json.loads(files[name])['files']
    assert set(entries) == set(files) - {name}
    for n, item in entries.items():
        assert item['bytes'] == len(files[n]) and item['sha256'] == sha(files[n]), n


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--releases', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    a = parser.parse_args()
    assert not a.output.exists(), 'Use a new output directory; preserve accepted artifacts'
    base_dir = a.releases / '0.14.7-20261121'
    update_dir = a.releases / '0.14.8-20261122'
    camera_dir = a.releases / '0.14.8-ll-camera2-20261122'
    base_name = 'MuoniumPlayerBedrock-0.14.7-levilamina-mc26.60-full.zip'
    update_name = 'MuoniumPlayerBedrock-0.14.8-levilamina-mc26.60-update.zip'
    camera_name = 'MuoniumPlayerBedrock-0.14.8-levilamina-camera2.zip'
    inputs = {}
    for folder, name in [(base_dir, base_name), (update_dir, update_name), (camera_dir, camera_name)]:
        inputs[name] = sha(checked_file(folder, name))
    base = read_archive(base_dir / base_name)
    update = read_archive(update_dir / update_name)
    camera = read_archive(camera_dir / camera_name)
    check_manifest(base)
    check_manifest(update)
    assert set(camera) == {PREFIX + 'MuoniumPlayerBedrock.dll', 'TESTING.md', 'VERIFICATION.json'}
    camera_report = json.loads(camera['VERIFICATION.json'])
    dll = camera[PREFIX + 'MuoniumPlayerBedrock.dll']
    assert sha(dll) == camera_report['dll']['sha256']
    assert dll == checked_file(camera_dir, 'MuoniumPlayerBedrock.dll')
    assert dll == (ROOT / 'build/windows/x64/release/MuoniumPlayerBedrockLL.dll').read_bytes()
    files = dict(base)
    files.update(update)
    files[PREFIX + 'MuoniumPlayerBedrock.dll'] = dll
    jar = files[PREFIX + 'jvm/lib/MuoniumBedrockHost.jar']
    assert sha(jar) == camera_report['unchangedHostJarSha256']
    assert jar == (ROOT / 'jvm/build/libs/MuoniumBedrockHost.jar').read_bytes()
    assert len([n for n in files if n.startswith(PREFIX + 'jvm/lib/') and n.endswith('.jar')]) == 46
    assert PREFIX + 'runtime/bin/java.exe' in files and PREFIX + 'runtime/lib/modules' in files
    immutable = []
    for n, data in base.items():
        if n.startswith(tuple(PREFIX + p for p in ['runtime/', 'licenses/', 'tunnel/'])) or (
                n.startswith(PREFIX + 'jvm/lib/') and not n.endswith('/MuoniumBedrockHost.jar')):
            assert files[n] == data, n
            immutable.append(n)
    manifest = json.loads(files[PREFIX + 'manifest.json'])
    mod = json.loads(files[PREFIX + 'mod.json'])
    assert manifest['version'] == mod['version'] == '0.14.8'
    assert manifest['type'] == 'native' and mod['minecraft'] == ['1.26.60.05']
    descriptor = files[PREFIX + 'modmenu.settings.json']
    assert descriptor == files[PREFIX + 'interface/modmenu.settings.json']
    assert json.loads(descriptor)['plugin']['versions'] == ['0.14.8']
    source_inputs = [(update_dir, 'MuoniumPlayerBedrock-0.14.8-source-overlay.zip'),
                     (camera_dir, 'MuoniumPlayerBedrock-0.14.8-levilamina-camera2-source-overlay.zip')]
    for folder, name in source_inputs:
        data = checked_file(folder, name)
        inputs[name] = sha(data)
        files[PREFIX + 'sources/' + name] = data
    source_name = 'MuoniumPlayerBedrock-0.14.7-source.zip'
    assert files[PREFIX + 'sources/' + source_name] == checked_file(base_dir, source_name)
    guide = '''# MuoniumPlayerBedrock 0.14.8 完整整合測試包

適用 Minecraft 1.26.60.05 / LeviLamina Client 26.60.7 / Windows x64。
包含新修正 DLL（revision=ll-coexist2）、完整 0.14.8 Java 播放器、46 個 JAR、
Java 21 執行環境及原有必要元件；無需先安裝舊版或逐一覆蓋補丁。

完全退出 Minecraft，再將 ZIP 解壓到實例根目錄，讓檔案位於
mods/MuoniumPlayerBedrock/。既有設定、帳號和歌單應保留；本包不含使用者設定檔。
ModMenuBE、LeviLamina、Lamium、Arke 仍使用已安裝的版本，本包不包含這些模組。

本次整合相機共存修正，只接受已審核的 Lamium 0.7.0 / LeviLamina 26.60.7
及 PreLoader 組合；未知掛鉤仍拒絕接入。不是原版載入器的 DLL。
開啟一般 Lamium 即可，無需同時啟用它的自由鏡頭。進世界播放歌曲，關閉 UI，
靜止等待設定延遲後確認運鏡啟動，操作後確認能正常退出。

關閉 Lamium 後的運鏡恢復已由使用者確認。新 DLL 與 Lamium 同時啟用的
遊戲內驗收仍待完成；離線測試及打包核驗不能代替此項驗收。
日誌應顯示 CINEMATIC_HOOK revision=ll-coexist2。

原 0.14.8 更新內容及本次掛鉤修正證據位於 docs/ 與 VERIFICATION.json。
本包未安裝到遊戲、未發布到 GitHub，所有舊套件保留。
'''.encode('utf-8')
    install = '''# 安裝

完全退出遊戲，將本完整包解壓到 26.60 LeviLamina 實例根目錄。
正確路徑為 mods/MuoniumPlayerBedrock/MuoniumPlayerBedrock.dll。
已有設定、帳號及歌單保留；Java 21 和完整 jvm/lib 已包含。
需要另行安裝相容的 ModMenuBE；本包不替換 ModMenuBE 或其他模組。
詳見 TESTING.md。本包是本機測試候選版，仍待 Lamium 共存實機驗收。
'''.encode('utf-8')
    files['INSTALL.md'] = install
    files['TESTING.md'] = guide
    files[PREFIX + 'README.md'] = guide
    files[PREFIX + 'TESTING.md'] = guide
    files[PREFIX + 'docs/LL-CAMERA-COEXIST2.md'] = camera['TESTING.md']
    files[PREFIX + 'docs/VERIFICATION-0.14.8.json'] = update['VERIFICATION.json']
    files[PREFIX + 'docs/VERIFICATION-LL-CAMERA2.json'] = camera['VERIFICATION.json']
    files[PREFIX + 'sources/RECONSTRUCT-0.14.8.md'] = '''# Source reconstruction

Extract MuoniumPlayerBedrock-0.14.7-source.zip into an empty source folder.
Apply MuoniumPlayerBedrock-0.14.8-source-overlay.zip by copying its source files,
then apply MuoniumPlayerBedrock-0.14.8-levilamina-camera2-source-overlay.zip.
BASELINE.json and the diff files document each transition; the earlier source
is deliberately retained as the reconstruction baseline. BUILDING.md describes
the existing native and Java build tools. OpenAL source and all license notices
are included unchanged. package_integrated_0148.py assembles this full package.
'''.encode('utf-8')
    files[PREFIX + 'sources/package_integrated_0148.py'] = Path(__file__).read_bytes()
    files['CANDIDATE.json'] = encoded(dict(version='0.14.8', revision='ll-coexist2', edition='levilamina',
        package='full', minecraft=['1.26.60.05'], leviLamina='26.60.7',
        lamiumCoexistenceAcceptance='pending', menuIncluded=False, userConfigIncluded=False))
    report = dict(version='0.14.8', revision='ll-coexist2', edition='levilamina', package='full',
        inputs=inputs, dllSha256=sha(dll), hostJarSha256=sha(jar), jars=46,
        runtime='Bundled original Java 21 runtime, byte-identical to verified 0.14.7 full package',
        immutableRuntimeAndLicenseFiles=len(immutable),
        sourceReconstruction='0.14.7 full source + 0.14.8 overlay + ll-camera2 overlay, all included',
        nativeChecks=camera_report['checks'], nativeRelease=camera_report['nativeRelease'],
        manualAcceptance=camera_report['manualAcceptance'], limits=camera_report['limits'],
        gameModified=False, userConfigIncluded=False, otherModsIncluded=False, published=False)
    files['VERIFICATION.json'] = encoded(report)
    pm = PREFIX + 'PACKAGE-MANIFEST.json'
    files.pop(pm)
    files[pm] = encoded(dict(version='0.14.8', revision='ll-coexist2', edition='levilamina', package='full',
        files={n: dict(bytes=len(d), sha256=sha(d)) for n, d in sorted(files.items())}))
    check_manifest(files)
    assert all(n.startswith(PREFIX) for n in files if n.startswith('mods/'))
    assert not any(n.casefold().endswith(('/muoniumplayerbedrock.json', '/config.json')) for n in files)
    assert not any('/logs/' in n or '/cache/' in n for n in files)
    a.output.mkdir(parents=True, exist_ok=False)
    output = a.output / 'MuoniumPlayerBedrock-0.14.8-levilamina-mc26.60-full.zip'
    with zipfile.ZipFile(output, 'x', zipfile.ZIP_DEFLATED) as z:
        for name, data in sorted(files.items()):
            z.writestr(name, data)
    actual = read_archive(output)
    assert actual == files
    check_manifest(actual)
    (a.output / 'INSTALL.md').write_bytes(install)
    (a.output / 'TESTING.md').write_bytes(guide)
    (a.output / 'VERIFICATION.json').write_bytes(encoded(report))
    sums = {f.name: sha(f.read_bytes()) for f in a.output.iterdir() if f.is_file()}
    (a.output / 'SHA256SUMS.txt').write_text(''.join(f'{v}  {n}\n' for n, v in sorted(sums.items())), encoding='ascii')
    print(json.dumps(dict(output=str(output), bytes=output.stat().st_size, sha256=sums[output.name],
                         files=len(files), jars=46, immutableFiles=len(immutable)), indent=2))


if __name__ == '__main__':
    main()
