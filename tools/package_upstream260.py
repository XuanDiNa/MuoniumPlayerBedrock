"""Assemble a new local candidate from fresh builds and a verified runtime ZIP.

No game installation, network publication, initial user config or menu DLL.
Run after the complete Gradle verification campaign. Paths are CLI arguments.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = '0.14.7'
PIN = 'cda0c7e9db4069844699d14c9fcfb43729bd4b2f'
PREFIX = 'mods/MuoniumPlayerBedrock/'
TASKS = ['test', 'acousticsRegressionTest', 'stageRenderTest',
         'nativeD3D12RenderTest', 'playbackRecoveryTest', 'worldSpeakerTest', 'cinematicHostTest', 'cinematicMotionTest', 'roomProxyTest', 'roomProxyUiTest']


def sha(data):
    return hashlib.sha256(data).hexdigest().upper()


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def archive(path, files):
    # Exclusive creation prevents silent replacement of an accepted artifact.
    with zipfile.ZipFile(path, 'x', zipfile.ZIP_DEFLATED) as z:
        for name, data in sorted(files.items()):
            assert not name.startswith(('/', '\\')) and '..' not in Path(name).parts
            z.writestr(name, data)
    with zipfile.ZipFile(path) as z:
        assert z.testzip() is None
        assert len(z.namelist()) == len(set(z.namelist())) == len(files)
        for name, data in files.items():
            assert sha(z.read(name)) == sha(data), name


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', required=True, type=Path)
    p.add_argument('--baseline-full', required=True, type=Path)
    p.add_argument('--baseline-checksums', required=True, type=Path)
    p.add_argument('--audit-tools', required=True, type=Path)
    p.add_argument('--evidence', required=True, type=Path)
    a = p.parse_args()
    evidence = a.evidence.resolve()
    expected = {line.split(None, 1)[1].strip(): line.split()[0].upper()
                for line in a.baseline_checksums.read_text().splitlines() if line.strip()}
    baseline_hash = sha(a.baseline_full.read_bytes())
    assert baseline_hash == expected[a.baseline_full.name], 'Runtime source ZIP mismatch'
    native = json.loads((evidence / 'native-final/results.json').read_text())
    assert native['mode'] == 'release' and len(native['suites']) == 34
    assert all(v['exit'] == 0 for v in native['suites'].values())
    assert 'BUILD SUCCESSFUL' in (evidence / 'verification-final.log').read_text(encoding='utf-8')

    results = {}
    for task in TASKS:
        suites = []
        for f in sorted((ROOT / 'jvm/build/test-results' / task).glob('TEST-*.xml')):
            t = ET.parse(f).getroot()
            assert int(t.attrib['failures']) == int(t.attrib['errors']) == 0, f
            assert int(t.attrib['skipped']) == 0, f
            suites.append({'name': t.attrib['name'], 'tests': int(t.attrib['tests'])})
        assert suites, task
        results[task] = {'tests': sum(s['tests'] for s in suites), 'suites': suites}
    all_names = {s['name'].rsplit('.', 1)[-1] for r in results.values() for s in r['suites']}
    assert {'EnvironmentContentionTest', 'CinematicScheduleIntegrationTest',
            'CinematicHostIntegrationTest', 'CinematicMotionRegressionTest', 'CinematicCollisionTest', 'CinematicRoutePlannerTest', 'HostWorkspaceTest', 'BedrockChatCardSettingsTest',
            'Upstream260RegressionTest', 'UpstreamSnapshotRenderTest', 'CapsuleIconRenderTest',
            'StageRenderTest', 'NativeD3D12RenderTest', 'PlaybackRetryTest',
            'RoomProxySettingsTest', 'WindowsRoomProxyTest', 'RoomProxyTransportTest', 'RoomProxyScreenTest',
            'TunnelInviteTest', 'PartyTransportTest', 'RoomLatencyTest', 'HostWatchdogTest',
            'AllSourceStreamingTest', 'StreamingTransportCoverageTest'} <= all_names
    assert any(s['name'].endswith('.AllSourceStreamingTest') and s['tests'] >= 30
               for s in results['test']['suites']), 'Missing held-tail codec/provider and authorized sample regressions'
    assert any(s['name'].endswith('.StreamingTransportCoverageTest') and s['tests'] >= 13
               for s in results['test']['suites']), 'Missing cancellation, format rejection and no-range regressions'
    assert any(s['name'].endswith('.RoomLatencyTest') and s['tests'] >= 4
               for s in results['test']['suites']), 'Missing RTT expiry and connection ownership regressions'
    assert any(s['name'].endswith('.HostWatchdogTest') and s['tests'] >= 3
               for s in results['test']['suites']), 'Missing production short-stall capture regression'
    assert any(s['name'].endswith('.TunnelInviteTest') and s['tests'] >= 4
               for s in results['roomProxyTest']['suites']), 'Missing incomplete/complete invitation regressions'

    # Exact game/Arke code and layouts remain unchanged except the documented
    # camera input-source adapter; record its diff/hash separately.
    diff = subprocess.check_output(['git', 'diff', '--name-only', 'HEAD', '--', 'src', 'include'], cwd=ROOT).decode().splitlines()
    assert set(diff) == {'include/Local3Descriptions.h', 'include/Version.h', 'src/CinematicBridge.cpp'}, diff
    guards = {}
    for folder in ['src', 'include']:
        names = subprocess.check_output(['git', 'ls-files', folder], cwd=ROOT).decode().splitlines()
        for name in names:
            if name in diff:
                continue
            old = subprocess.check_output(['git', 'show', 'HEAD:' + name], cwd=ROOT)
            current = (ROOT / name).read_bytes()
            # Git may materialize CRLF on checkout; compare code, not EOL policy.
            assert old.replace(b'\r\n', b'\n') == current.replace(b'\r\n', b'\n'), name
            guards[name] = sha(current)
    descriptor = (ROOT / 'modmenu.settings.json').read_bytes()
    assert json.loads(descriptor)['plugin']['versions'] == [VERSION]
    assert sha(descriptor) in (ROOT / 'include/Local3Descriptions.h').read_text()
    provenance = json.loads((ROOT / 'vendor/MuoniumPlayer-2.5.2/SOURCE-MANIFEST.json').read_text(encoding='utf-8'))
    assert provenance['commit'] == PIN
    for f in provenance['files']:
        data = (ROOT / 'vendor/MuoniumPlayer-2.5.2' / f['path']).read_bytes()
        assert sha(data).lower() == f['sha256'].lower(), f['path']

    sys.path.insert(0, str(a.audit_tools.resolve()))
    from pe_audit import audit_runtime_dependencies
    from package_local import pe_exports
    binary_reports = {}
    binaries = {}
    for edition, target in [('vanilla', 'MuoniumPlayerBedrock'), ('levilamina', 'MuoniumPlayerBedrockLL')]:
        data = (ROOT / f'build/windows/x64/release/{target}.dll').read_bytes()
        imports = audit_runtime_dependencies(data)
        assert not {'modmenube.dll', 'modmenubedrock.dll'} & set(imports)
        exports = pe_exports(data)
        assert {'MPB_GetVersion', 'MPB_GetStatus'} <= set(exports)
        assert not any(n.startswith(('Fixture', 'Owner', 'WorkspaceBegin', 'WorkspaceMode', 'Frozen_')) for n in exports)
        assert ('ll_mod_load' in exports) == (edition == 'levilamina')
        binaries[edition] = data
        binary_reports[edition] = {'bytes': len(data), 'sha256': sha(data), 'exports': exports, 'runtimeImports': imports}
    for script in ['unsupported_host_smoke.py', 'unsupported_levi_smoke.py']:
        args = [sys.executable, str(ROOT / 'tools' / script)]
        if script == 'unsupported_host_smoke.py':
            args += [str(ROOT / 'build/windows/x64/release/MuoniumPlayerBedrock.dll'), VERSION]
        r = subprocess.run(args, cwd=evidence, capture_output=True, text=True, timeout=30)
        assert r.returncode == 0, r.stdout + r.stderr

    lib = ROOT / 'jvm/build/install/MuoniumBedrockHost/lib'
    jars = {f.name: f.read_bytes() for f in lib.glob('*.jar')}
    assert len(jars) == 46 and jars['MuoniumBedrockHost.jar'] == (ROOT / 'jvm/build/libs/MuoniumBedrockHost.jar').read_bytes()
    verification = {
        'version': VERSION, 'generatedUtc': datetime.now(timezone.utc).isoformat(),
        'upstream': {'commit': PIN, 'branch': 'dev', 'version': '2.6.0-dev', 'stableRelease': False},
        'nativeRelease': native, 'jvmTasks': results, 'binaries': binary_reports,
        'hostJarSha256': sha(jars['MuoniumBedrockHost.jar']),
        'nativeUnchangedFiles': guards,
        'nativeCameraInputAdapterChanged': {n: sha((ROOT / n).read_bytes()) for n in ['src/CinematicBridge.cpp', 'include/CinematicInput.h']},
        'nativeCameraInputAdapterDiff': subprocess.check_output(['git', 'diff', '--', 'src/CinematicBridge.cpp'], cwd=ROOT).decode(),
        'local3ExactDescriptorSha256': sha(descriptor),
        'unsupportedHostRefusal': {'vanilla': True, 'levilamina': True},
        'graphicsTests': 'Actual Skija GPU and D3D12 pixels in owned windows; not Minecraft gameplay',
        'manualMinecraftAcceptance': 'pending; audio verified with owned held-tail HTTP, real AudioPlayer, non-silent PCM, Qishui authorized encryption and YouTube window fixtures; live source accounts/CDN, gameplay smoothness and cross-network rooms require user testing',
        'progressiveAudioScope': 'Provider-independent byte-detected clear HTTP MP3/FLAC/AAC/MP4/fMP4/Vorbis/WAV; original Bilibili headers and YouTube window/proxy transport; original authorized Qishui whole-sample CTR layout incrementally. One-second prebuffer, session PCM, bounded lookahead, default-off prefetch and retention. Unsupported encryption/metadata/range and Apple native playback retain original fallback. No guarantee of zero startup wait, zero disk writes or fixing prior UI stalls/driver crashes. See docs/STREAMING-AUDIO.md.',
        'roomProxyScope': 'Joining existing WSS internet room invitations only. DIRECT, Windows manual/PAC/WPAD, HTTP CONNECT or SOCKS5 without authentication. No automatic fallback after proxy failure. LAN/address direct, cloudflared host helper and music downloads retain their original paths. No deployed central room service or changes to system proxy/accelerator settings.',
        'roomInvitationFixScope': 'Complete invite action prefers the ready public invitation. An empty direct host produces no full invitation or clipboard write. Port/password fragments remain a separately labelled advanced action; joining a fragment reports the missing host before background work. No guessed host address, new relay or automatic tunnel startup.',
        'roomLatencyScope': 'Rightmost member row RTT to the transport host, including tunnels/proxies; separate from Minecraft server RTT. Monotonic matched Ping/Pong on the socket reader every ~2s, room-scoped additive capability tables, bounded/sanitized immutable client cache, stale/old-host/disconnect shown as unknown. Real loopback WebSocket room test covers delayed echo, continued refresh, room separation, disconnect cleanup and 1000/620/440px UI layout; not cross-network gameplay acceptance.',
        'bilibiliStallScope': 'Existing 0.14.5 incident logs confirm a 6.073s draw and a 1.844s draw around Bilibili start, followed by recovery. No blocking stack was available under the previous 8s threshold. Production diagnostics now capture at 2s, maximum six reports, without interrupting playback. Root cause and fix remain unconfirmed; no speculative playback changes.',
        'previous0144VerificationRechecks': '0.14.4 had an initial native Present probe failure and three stage desktop-sampling/input failures; subsequent isolated and complete campaigns passed without renderer changes. Evidence remains in artifacts/room-proxy-20261119. This campaign records fresh results separately.',
        'cameraFixScope': 'Whole-shot bounded path preflight with rotated/reduced alternatives and stable hold fallback, conservative AABB clearance, bounded replanning, endpoint and post-slide radius guards; original camera/game guards retained',
        'userConfigurationIncluded': False, 'settingsPreservedByPackage': True,
        'modmenuDllIncludedOrModified': False, 'installedGameModified': False, 'githubPublication': False,
        'runtimeSource': {'archive': a.baseline_full.name, 'sha256': baseline_hash},
    }
    # Replace the stale public campaign record before collecting current sources.
    (ROOT / 'docs/PUBLIC-VERIFICATION.json').write_bytes(encoded(verification))
    a.output.mkdir(parents=True, exist_ok=False)
    names = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT).decode().split('\0')
    sources = {n: (ROOT / n).read_bytes() for n in names if n and (ROOT / n).is_file()}
    assert not any(Path(n).parts[0] in {'build', 'artifacts', 'releases', '.git'} for n in sources)
    source_name = f'MuoniumPlayerBedrock-{VERSION}-source.zip'
    archive(a.output / source_name, sources)
    source_data = (a.output / source_name).read_bytes()

    with zipfile.ZipFile(a.baseline_full) as z:
        assert z.testzip() is None
        # Only unchanged redistributable runtime/helper/source material is reused.
        retained = {n: z.read(n) for n in z.namelist() if n.startswith((
            PREFIX + 'runtime/', PREFIX + 'tunnel/', PREFIX + 'maintenance/',
            PREFIX + 'sources/openal-soft-')) and not n.endswith('/')}
    assert PREFIX + 'runtime/bin/javaw.exe' in retained
    assert PREFIX + 'tunnel/cloudflared.exe' in retained
    common = {PREFIX + 'jvm/lib/' + n: data for n, data in jars.items()}
    for name in ['LICENSE', 'NOTICE', 'README.md', 'BUILDING.md', 'TESTING.md', 'modmenu.json', 'modmenu.settings.json']:
        common[PREFIX + name] = (ROOT / name).read_bytes()
    common[PREFIX + 'interface/modmenu.settings.json'] = descriptor
    for f in (ROOT / 'licenses').rglob('*'):
        if f.is_file():
            common[PREFIX + f.relative_to(ROOT).as_posix()] = f.read_bytes()
    font_notices = ROOT / 'vendor/MuoniumPlayer-2.5.2/muonium-common/src/main/resources/META-INF/licenses/public-fonts'
    for f in font_notices.iterdir():
        if f.is_file():
            common[PREFIX + 'licenses/public-fonts/' + f.name] = f.read_bytes()
    for name in ['INSTALL.md', 'DEPENDENCIES.md', 'modmenube-migration.md', 'UPSTREAM-260.md', 'ROOM-PROXY.md', 'STREAMING-AUDIO.md']:
        data = (ROOT / 'docs' / name).read_bytes()
        common['docs/' + name] = common[PREFIX + 'docs/' + name] = data
    for f in (ROOT / 'docs/images').glob('*.png'):
        common[PREFIX + 'docs/images/' + f.name] = f.read_bytes()
    common['TESTING.md'] = common[PREFIX + 'TESTING.md']
    common['VERIFICATION.json'] = encoded(verification)
    for edition in binaries:
        manifest = json.loads((ROOT / 'manifest.json').read_text())
        metadata = json.loads((ROOT / 'mod.json').read_text())
        if edition == 'levilamina':
            manifest['type'] = 'native'
            manifest['optionalDependencies'] = [{'name': 'arke'}]
            metadata['minecraft'] = ['1.26.60.05']
        assert manifest['version'] == metadata['version'] == VERSION
        files = dict(common)
        files[PREFIX + 'MuoniumPlayerBedrock.dll'] = binaries[edition]
        files[PREFIX + 'manifest.json'] = encoded(manifest)
        files[PREFIX + 'mod.json'] = encoded(metadata)
        suffix = 'mc26.45-26.51-26.52-26.60' if edition == 'vanilla' else 'mc26.60'
        for kind in ['update', 'full']:
            packed = dict(files)
            if kind == 'full':
                packed.update(retained)
                packed[PREFIX + 'sources/' + source_name] = source_data
            install = ('Exit Minecraft. Extract into the instance root. Preserve settings/accounts/playlists.\n'
                       f'Edition: {edition}. Version: {VERSION}. Package: {kind}.\n'
                       + ('Java21 is included.\n' if kind == 'full' else 'An existing complete Java21 player runtime is required.\n')
                       + 'Install ModMenuBE separately; this package contains no menu DLL. See TESTING.md.\n')
            packed['INSTALL.md'] = install.encode()
            candidate = {'version': VERSION, 'edition': edition, 'package': kind,
                         'manualMinecraftAcceptance': 'pending', 'upstreamDevelopmentSnapshot': PIN,
                         'menuIncluded': False, 'userConfigIncluded': False}
            packed['CANDIDATE.json'] = encoded(candidate)
            assert not any(n.endswith('MuoniumPlayerBedrock.json') for n in packed)
            assert not any(n.lower().endswith(('modmenube.dll', 'modmenubedrock.dll')) for n in packed)
            hashes = {n: {'bytes': len(data), 'sha256': sha(data)} for n, data in sorted(packed.items())}
            packed[PREFIX + 'PACKAGE-MANIFEST.json'] = encoded({'version': VERSION, 'edition': edition, 'package': kind, 'files': hashes})
            archive(a.output / f'MuoniumPlayerBedrock-{VERSION}-{edition}-{suffix}-{kind}.zip', packed)
    (a.output / 'VERIFICATION.json').write_bytes(encoded(verification))
    (a.output / 'TESTING.md').write_bytes(common['TESTING.md'])
    checks = {f.name: {'bytes': f.stat().st_size, 'sha256': sha(f.read_bytes())} for f in a.output.glob('*.zip')}
    (a.output / 'SHA256SUMS.txt').write_text(''.join(f'{v["sha256"]}  {n}\n' for n, v in sorted(checks.items())), encoding='ascii')
    print(json.dumps({'packages': checks, 'jvmTests': {t: v['tests'] for t, v in results.items()},
                      'nativeReleaseSuites': len(native['suites'])}, indent=2), flush=True)


if __name__ == '__main__':
    main()
