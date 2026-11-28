"""Combine the frozen 0.20.2 LL full package with the verified compatibility DLL."""
from pathlib import Path
import argparse
import hashlib
import json
import zipfile

PREFIX = 'mods/MuoniumPlayerBedrock/'


def sha(data):
    return hashlib.sha256(data).hexdigest().upper()


def encoded(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + '\n').encode('utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--candidate', type=Path, required=True)
    args = parser.parse_args()
    baseline = args.baseline / 'MuoniumPlayerBedrock-0.20.2-levilamina-mc26.60-full.zip'
    baseline_bytes = baseline.read_bytes()
    checksum_lines = (args.baseline / 'SHA256SUMS.txt').read_text().splitlines()
    digest = next(line.split()[0] for line in checksum_lines if line.split()[-1] == baseline.name)
    assert sha(baseline_bytes) == digest.upper()
    old_hashes = {}
    with zipfile.ZipFile(baseline) as z:
        assert z.testzip() is None
        files = {n: z.read(n) for n in z.namelist()}
    manifest_name = PREFIX + 'PACKAGE-MANIFEST.json'
    old_manifest = json.loads(files[manifest_name])
    assert set(old_manifest['files']) == set(files) - {manifest_name}
    for name, row in old_manifest['files'].items():
        assert len(files[name]) == row['bytes'] and sha(files[name]) == row['sha256']
        old_hashes[name] = row['sha256']
    verification = json.loads((args.candidate / 'VERIFICATION.json').read_text('utf-8'))
    assert verification['version'] == '0.20.2' and verification['candidate'] == 'levilamina-host-compat'
    dll = (args.candidate / 'MuoniumPlayerBedrock.dll').read_bytes()
    assert sha(dll) == verification['dll']['sha256']
    with zipfile.ZipFile(args.candidate / 'MuoniumPlayerBedrock-0.20.2-levilamina-host-compat-update.zip') as z:
        assert z.read(PREFIX + 'MuoniumPlayerBedrock.dll') == dll
    files[PREFIX + 'MuoniumPlayerBedrock.dll'] = dll
    files['BASELINE-0.20.2-VERIFICATION.json'] = files['VERIFICATION.json']
    verification['fullPackage'] = dict(baselineArchive=baseline.name, baselineSha256=sha(baseline_bytes),
                                     java21RuntimeIncluded=True, accountsOrUserSettingsIncluded=False,
                                     unchangedOtherRuntimeFiles=True, manualGameplayAcceptance='pending')
    files['VERIFICATION.json'] = encoded(verification)
    candidate = json.loads(files['CANDIDATE.json'])
    candidate.update(candidate='levilamina-host-compat', minecraft='1.26.60.05',
                     loader='LeviLamina 26.60.7', githubPublication=False,
                     compatibilityCandidateGameplayAcceptance='pending', java21RuntimeIncluded=True)
    files['CANDIDATE.json'] = encoded(candidate)
    guide = (args.candidate / 'TESTING.md').read_bytes()
    files['HOST-COMPAT.md'] = guide
    files[PREFIX + 'docs/LL-GUI-HOST-0.20.2.md'] = guide
    files['INSTALL.md'] = '''# 0.20.2 LeviLamina 完整相容修正版

適用 Minecraft 1.26.60.05 / LeviLamina 26.60.7 / Windows x64。
已包含修正 DLL、Java 播放器、依賴及 Java 21 runtime，可直接安裝。

1. 完全退出 Minecraft，保留原 DLL 以便回復。
2. 將整包解壓到遊戲實例根目錄，合併 mods 資料夾並替換套件檔案。
3. 保留原有設定、帳號、歌單及快取，無需刪除舊插件資料夾。
4. 啟動後確認能開啟播放器、HUD 編輯，並播放一首歌曲。

ModMenuBE 另行安裝，本包不更動它或遊戲 EXE。
本候選尚待玩家遊戲內驗收，細節見 HOST-COMPAT.md / VERIFICATION.json。

Exit Minecraft and extract into the instance root, merging the mods directory.
Preserve settings, accounts and playlists. The verified compatibility DLL,
Java player, dependencies and Java 21 runtime are included. ModMenuBE is separate.
In-game acceptance of this DLL remains pending.
'''.encode('utf-8')
    note = '''本完整包已整合 2026-11-28 載入相容修正；0.20.2 既有功能、Java 播放器與 runtime 沿用原發行包。
只差 GUI／控制台模式及校驗值的已驗證內容，現在可自動辨識，免逐位玩家收錄雜湊。
詳見 HOST-COMPAT.md。此 DLL 尚待遊戲內驗收，原 GitHub 發行包保持原樣。

This full candidate integrates the verified host metadata compatibility fix.
All other runtime binaries are byte-identical to the 0.20.2 public full package.
See HOST-COMPAT.md. In-game acceptance remains pending.

---

'''.encode('utf-8')
    for name in ['RELEASE-NOTES.md', PREFIX + 'docs/RELEASE-0.20.2.md']:
        files[name] = note + files[name]
    files['TESTING.md'] = b'See HOST-COMPAT.md for this candidate; the original cumulative test guide follows.\n\n' + files['TESTING.md']
    binaries = [name for name in old_hashes if name.endswith(('.dll', '.exe', '.jar')) and name != PREFIX + 'MuoniumPlayerBedrock.dll']
    assert len(binaries) > 60
    for name in binaries:
        assert sha(files[name]) == old_hashes[name]
    assert PREFIX + 'runtime/bin/java.exe' in files and PREFIX + 'jvm/lib/MuoniumBedrockHost.jar' in files
    assert not any(name.lower().endswith(('muoniumplayerbedrock.json', 'cookies.txt', 'latest.log')) for name in files)
    files[manifest_name] = encoded(dict(version='0.20.2', edition='levilamina', package='full',
                                        candidate='levilamina-host-compat',
                                        files={n: dict(bytes=len(data), sha256=sha(data)) for n, data in sorted(files.items()) if n != manifest_name}))
    output = args.candidate / 'MuoniumPlayerBedrock-0.20.2-levilamina-host-compat-full.zip'
    with zipfile.ZipFile(output, 'x', zipfile.ZIP_DEFLATED) as z:
        for name, data in sorted(files.items()):
            z.writestr(name, data)
    with zipfile.ZipFile(output) as z:
        assert z.testzip() is None and set(z.namelist()) == set(files)
        manifest = json.loads(z.read(manifest_name))
        for name, row in manifest['files'].items():
            data = z.read(name)
            assert len(data) == row['bytes'] and sha(data) == row['sha256']
        assert z.read(PREFIX + 'MuoniumPlayerBedrock.dll') == dll
    assert sha(baseline.read_bytes()) == sha(baseline_bytes)
    audit = dict(fullPackage=output.name, bytes=output.stat().st_size, sha256=sha(output.read_bytes()),
                 entries=len(files), unchangedOtherRuntimeBinaries=len(binaries),
                 dllSha256=sha(dll), baselinePreserved=True, allManifestHashesMatch=True,
                 java21RuntimeIncluded=True, manualGameplayAcceptance='pending')
    (args.candidate / 'FULL-PACKAGE-AUDIT.json').write_bytes(encoded(audit))
    # Existing candidate files are preserved; append checksums for the new pair.
    checksums = args.candidate / 'SHA256SUMS.txt'
    lines = checksums.read_text(encoding='ascii').splitlines()
    for path in [output, args.candidate / 'FULL-PACKAGE-AUDIT.json']:
        lines.append(f'{sha(path.read_bytes())}  {path.name}')
    checksums.write_text('\n'.join(lines) + '\n', encoding='ascii')
    print(json.dumps(audit, indent=2))


if __name__ == '__main__':
    main()
