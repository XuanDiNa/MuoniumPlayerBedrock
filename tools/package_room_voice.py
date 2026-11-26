"""Package the independent client-room voice candidate. Never install or publish."""
from pathlib import Path
from datetime import datetime, timezone
import argparse, io, json, subprocess, sys, zipfile
import xml.etree.ElementTree as ET
from package_upstream261 import ROOT, PREFIX, sha, encoded, read_archive, archive, manifest_check

VERSION='0.19.0'
TASKS=['roomVoiceTest','voiceBroadcastTest','voiceBroadcastRenderTest','settingsAppearanceTest',
       'overlayInputTest','progressiveRegressionTest','playbackRecoveryTest','roomProxyTest','roomProxyUiTest']

def main():
    global VERSION
    p=argparse.ArgumentParser(description=__doc__)
    for key in ['baseline','evidence','audit-tools','opus-source','output']:p.add_argument('--'+key,type=Path,required=True)
    p.add_argument('--version',choices=['0.19.0','0.20.0','0.20.1','0.20.2'],default='0.19.0')
    a=p.parse_args();e=a.evidence;VERSION=a.version;spatial=VERSION in ['0.20.0','0.20.1','0.20.2'];pose_fix=VERSION=='0.20.1';gain_update=VERSION=='0.20.2'
    baseline_version=json.loads((a.baseline/'VERIFICATION.json').read_text('utf-8'))['version']
    assert not a.output.exists(),'Preserve existing candidates'
    frozen=json.loads((e/'frozen-releases.json').read_text('utf-8'))
    assert all(sha(Path(n).read_bytes())==h for n,h in frozen.items()),'Frozen release changed'
    before=json.loads((e/'native-before.json').read_text('utf-8'))
    now={p.relative_to(ROOT).as_posix():sha(p.read_bytes()) for d in ['src','include'] for p in (ROOT/d).rglob('*') if p.is_file()}
    assert before.keys()==now.keys()
    native_delta=sorted(n for n in now if before[n]!=now[n])
    expected_native=['include/Local3Descriptions.h','include/Version.h'] if spatial else ['include/DefaultPlayerConfig.h','include/Local3Descriptions.h','include/Player.h','include/Version.h','src/Core.cpp','src/JvmHost.cpp']
    if pose_fix:expected_native+=['include/WorldBridge.h','src/WorldBridge.cpp']
    assert native_delta==sorted(expected_native),native_delta
    vendor={p.relative_to(ROOT/'vendor').as_posix():sha(p.read_bytes()) for p in (ROOT/'vendor').rglob('*') if p.is_file()}
    assert vendor==json.loads((e/'vendor-before.json').read_text('utf-8')),'Vendor changed'
    logs=['verification-final.log','verification-signal-final.log','native-build-final.log'] if spatial else ['verification-final.log','verification-audio-final.log','native-build-final.log','codec-build-final.log']
    for n in logs:
        assert ('BUILD SUCCESSFUL' if n.startswith('verification') else 'build ok') in (e/n).read_text('utf-8'),n
    assert 'ten bindings including mic/deafen' in (e/'menu-interop-final.log').read_text('utf-8')
    assert 'bundled JAR codec resolution and audible Opus roundtrip passed' in (e/'bundled-codec-final.log').read_text('utf-8')
    suites={}
    tasks=TASKS+(['cinematicHostTest','cinematicMotionTest','worldSpeakerTest','acousticsRegressionTest'] if spatial else [])
    for task in tasks:
        results=[]
        for f in sorted((ROOT/'jvm/build/test-results'/task).glob('TEST-*.xml')):
            s=ET.parse(f).getroot();assert all(int(s.get(k))==0 for k in ['failures','errors','skipped']),f
            assert f.stat().st_mtime>(e/'native-before.json').stat().st_mtime,('stale',f)
            results.append(dict(name=s.get('name'),tests=int(s.get('tests'))))
        assert results,task;suites[task]=dict(tests=sum(r['tests'] for r in results),suites=results)
    native=json.loads((e/'native-release/results.json').read_text('utf-8'))
    assert len(native['suites'])==34 and all(v['exit']==0 for v in native['suites'].values())
    descriptor=(ROOT/'modmenu.settings.json').read_bytes()
    assert json.loads(descriptor)['plugin']['versions']==[VERSION]
    assert sha(descriptor) in (ROOT/'include/Local3Descriptions.h').read_text('utf-8')
    for n in ['manifest.json','mod.json']:assert json.loads((ROOT/n).read_text('utf-8'))['version']==VERSION
    sys.path.insert(0,str(a.audit_tools.resolve()))
    from pe_audit import PeImage,audit_runtime_dependencies
    def audit_binary(data,codec=False):
        imports=audit_runtime_dependencies(data);exports=sorted(v for v in PeImage(data).exports().values() if v)
        if codec:
            assert set(imports)=={'kernel32.dll'},imports
            assert exports==['MV_Create','MV_Decode','MV_Destroy','MV_Encode'],exports
        else:
            assert not set(imports)&{'modmenube.dll','modmenubedrock.dll','lamium.dll'}
        return dict(bytes=len(data),sha256=sha(data),imports=imports,exports=exports)
    dlls={edition:(ROOT/'build/windows/x64/release'/name).read_bytes() for edition,name in
          [('vanilla','MuoniumPlayerBedrock.dll'),('levilamina','MuoniumPlayerBedrockLL.dll')]}
    codec=(ROOT/'native/room-voice/build/windows/x64/release/MuoniumVoice.dll').read_bytes()
    binaries={k:audit_binary(d) for k,d in dlls.items()};binaries['codec']=audit_binary(codec,True)
    host=(ROOT/'jvm/build/libs/MuoniumBedrockHost.jar').read_bytes()
    assert host==(ROOT/'jvm/build/install/MuoniumBedrockHost/lib/MuoniumBedrockHost.jar').read_bytes()
    assert host==(e/'bundled-codec/jvm/lib/MuoniumBedrockHost.jar').read_bytes(),'Bundled codec probe used a different host'
    assert codec==(e/'bundled-codec/jvm/voice/MuoniumVoice.dll').read_bytes(),'Bundled codec probe used a different codec'
    # Isolate this task's bytecode delta against the frozen previous candidate.
    prior_version=baseline_version if spatial else '0.18.0'
    prior_path=a.baseline/f'MuoniumPlayerBedrock-{baseline_version}-levilamina-mc26.60-update.zip' if spatial else a.baseline.parent/'0.18.0-20261125/MuoniumPlayerBedrock-0.18.0-levilamina-mc26.60-update.zip'
    with zipfile.ZipFile(prior_path) as z:
        prior=z.read(PREFIX+'jvm/lib/MuoniumBedrockHost.jar')
        if spatial:assert z.read(PREFIX+'jvm/voice/MuoniumVoice.dll')==codec,'Codec unexpectedly changed'
    with zipfile.ZipFile(io.BytesIO(prior)) as old,zipfile.ZipFile(io.BytesIO(host)) as new:
        old_classes={n:old.read(n) for n in old.namelist() if n.endswith('.class')}
        new_classes={n:new.read(n) for n in new.namelist() if n.endswith('.class')}
        assert not old_classes.keys()-new_classes.keys()
        added=sorted(new_classes.keys()-old_classes.keys());assert all(n.startswith('com/muoniumplayer/bedrock/RoomVoice') for n in added),added
        changed=sorted(n for n in old_classes if old_classes[n].replace(prior_version.encode(),VERSION.encode())!=new_classes[n])
        allowed=['com/muoniumplayer/bedrock/BedrockOverlay','com/muoniumplayer/bedrock/HostHudEditor',
            'com/muoniumplayer/core/ncm/music/AudioPlayer','com/muoniumplayer/core/screens/hud/HudSettingsModel',
            'com/muoniumplayer/party/direct/DirectServer','com/muoniumplayer/party/direct/DirectClient',
            'com/muoniumplayer/party/direct/MpDirect','com/muoniumplayer/party/ui/MpPartyOverlay']
        if spatial:allowed=['com/muoniumplayer/bedrock/BedrockOverlay','com/muoniumplayer/bedrock/CinematicRuntime',
            'com/muoniumplayer/core/screens/hud/HudSettingsModel','com/muoniumplayer/party/direct/DirectServer','com/muoniumplayer/party/direct/DirectClient']
        if spatial:allowed+=['com/muoniumplayer/bedrock/'+n for n in ['RoomVoice','RoomVoiceDevices','RoomVoiceRouter']]
        if pose_fix:
            allowed=['com/muoniumplayer/bedrock/'+n for n in ['RoomVoice','RoomVoiceSpatial','EnvironmentReader','BedrockOverlay']]
            assert added==sorted('com/muoniumplayer/bedrock/'+n+'.class' for n in ['RoomVoiceWorld','RoomVoiceWorldReader','RoomVoiceWorldReader$Api']),added
        if gain_update:
            allowed=['com/muoniumplayer/bedrock/RoomVoice','com/muoniumplayer/core/screens/hud/HudSettingsModel']
            assert not added,added
        assert all(any(n==base+'.class' or n.startswith(base+'$') for base in allowed) for n in changed),changed
        resources=lambda z:{n:z.read(n) for n in z.namelist() if not n.endswith(('.class','/'))}
        assert resources(old)==resources(new),'Unexpected resource delta'
        assert {int.from_bytes(v[6:8],'big') for v in new_classes.values()}=={65}
    opus=a.opus_source.read_bytes();assert sha(opus)=='9480E329E989F70D69886DED470C7F8CFE6C0667CC4196D4837AC9E668FB7404'
    report=dict(version=VERSION,generatedUtc=datetime.now(timezone.utc).isoformat(),
        architecture='client room host relay over existing TCP / binary WebSocket tunnel; no Minecraft server addon',
        platforms=['vanilla 1.26.45.01','vanilla 1.26.51.01','vanilla 1.26.52.03','vanilla 1.26.60.05','LeviLamina 26.60.7 / 1.26.60.05'],
        jvmTasks=suites,freshJvmTests=sum(v['tests'] for v in suites.values()),nativeRelease=native,
        binaries=binaries,hostJarSha256=sha(host),nativeProductionChanged=native_delta,nativeHookAndLayoutChanges=False,
        unchangedVendorFiles=len(vendor),changedProductionClasses=changed,addedProductionClasses=added,unchangedMediaResources=True,
        local3ExactDescriptorSha256=sha(descriptor),opus=dict(version='1.5.2',archiveSha256=sha(opus),license='BSD-3-Clause',runtime='static MSVC',
            source='https://github.com/xiph/opus/archive/refs/tags/v1.5.2.tar.gz',recipeCommit='635956946b104aa1108d43267dd8878e13d3891a'),
        defaults=dict(roomVoice=False,microphone=False,musicBroadcast=False,deafened=False),
        bounds=dict(roomPeers=8,globalPeers=64,voicePacketsPerSecond=55,opusBytes=400,queuedFrames=8,freshnessMs=200,writeTimeoutMs=2000),
        physicalAudioTested=False,manualMinecraftAcceptance='pending',twoComputerInternetTested=False,
        shortcuts=dict(microphone='unbound',deafen='unbound',nativeEvents='appended 14 and 15',settingsInterop='real ModMenuBE passive parser, ten chords, Apply/Cancel preserved'),
        devices='background enumeration and explicit input/output choice; selection never enables microphone; missing selected device fails visibly',
        bundledCodecResolution='fresh Java process, deployed JAR/lib/voice layout, unrelated working directory, no path override; audible Opus roundtrip passed',
        voiceEvidence='real Opus / original room engine / TCP / binary WebSocket tunnel / synthetic audio and injected endpoints; no physical microphone',
        installedGameModified=False,modMenuIncluded=False,bschatIncluded=False,userConfigurationIncluded=False,githubPublication=False,
        preservedReleases=['0.17.1','0.18.0']+(['0.19.0'] if spatial else [])+(['0.20.0'] if pose_fix or gain_update else [])+(['0.20.1'] if gain_update else []),knownLimitations=['Host must stay online','Tunnel/proxy/TCP quality affects latency',
            'No echo cancellation or automatic game-server discovery','Input endpoints need48kHz mono; output needs48kHz stereo' if spatial else 'Selected Java Sound endpoints must support 48kHz mono',
            'No claim to repair existing YouTube playback'])
    if spatial:report['spatial']=dict(default=False,rangeOptions=[8,16,32,64],defaultRange=32,output='48kHz stereo',
        sender='existing independent cinematic player eye when available, ordinary player camera otherwise',listener='current camera',
        occlusion='native33-cube collision shapes; unknown paths conservatively muffled; background voice worker only',
        contexts='members must be in the same world; no automatic server/world verification; explicit rearm after local world loss/change',
        legacy='capability negotiated; spatial frames never downgrade to full-room audio for old hosts/clients',
        coordinates='ephemeral and only sent with distance consent; never persisted or logged',codecUnchangedFrom=baseline_version)
    if pose_fix or gain_update:
        report['spatial'].update(sender='verified local player AABB head and rotation; independent of HUD and cinematic eligibility',
            listener='same local player head and rotation',
            contexts='stable private player/world/dimension identity; UI/camera eligibility changes preserve consent; world loss/change or stale pose revokes',
            transport='new96-byte private owned pose mapping, at most50Hz; existing hook/addresses/layout guards retained')
        report['regression']='UI-open control and camera epoch changes, actual private mapping without camera mapping, four exact native owned player fixtures, disconnect/dimension/respawn and stale data'
    if gain_update:
        report['microphoneGain']=dict(defaultPercent=100,minimumPercent=0,maximumPercent=400,stepPercent=50,
            scope='microphone PCM before music mix; common send volume retained',
            peakProtection='whole microphone frame attenuation above full scale; no hard clipping in gain stage',
            live='no device reopen, room reconnect or implicit microphone activation',
            persistence='dedicated bounded preference; old settings default to100percent',
            evidence='real Opus decoded amplitude, zero gain, waveform distortion, music isolation, settings restore, actual HUD control and offscreen rendering')
        report['previousCandidateSpatialAcceptance']='User reported0.20.1 distance voice working; no new live game test in this candidate'
        report['regression']='Microphone gain and audio isolation plus retained room, spatial, focus, proxy, camera, playback and acoustic regressions'
    (ROOT/'docs/PUBLIC-VERIFICATION.json').write_bytes(encoded(report))
    names=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=ROOT).decode().split('\0')
    sources={n:(ROOT/n).read_bytes() for n in names if n and (ROOT/n).is_file() and not n.startswith('integrations/bschat/')}
    assert not any(n.split('/')[0] in {'build','artifacts','releases','.git'} for n in sources)
    sources['native/room-voice/sources/opus-1.5.2.tar.gz']=opus
    from verify_public_assets import inspect,audit
    source_audit=dict(files=0,archives=0,notoFonts=0)
    for n,data in sources.items():inspect(n,data,source_audit)
    assert source_audit['notoFonts']>=4
    a.output.mkdir(parents=True,exist_ok=False)
    source_name=f'MuoniumPlayerBedrock-{VERSION}-source.zip';archive(a.output/source_name,sources)
    for edition,dll in dlls.items():
        suffix='mc26.45-26.51-26.52-26.60' if edition=='vanilla' else 'mc26.60'
        for kind in ['full','update']:
            files=read_archive(a.baseline/f'MuoniumPlayerBedrock-{baseline_version}-{edition}-{suffix}-{kind}.zip');manifest_check(files)
            del files[PREFIX+'PACKAGE-MANIFEST.json']
            for n in list(files):
                if n.startswith(PREFIX+'sources/MuoniumPlayerBedrock-'):del files[n]
            for n in ['README.md','TESTING.md','NOTICE','BUILDING.md','modmenu.settings.json']:
                files[PREFIX+n]=(ROOT/n).read_bytes()
            files[PREFIX+'interface/modmenu.settings.json']=descriptor
            for n in ['manifest.json','mod.json']:
                meta=json.loads(files[PREFIX+n]);meta['version']=VERSION;files[PREFIX+n]=encoded(meta)
            for n in ['INSTALL.md','DEPENDENCIES.md','ROOM-VOICE.md',f'UPDATE-{VERSION}.md']:
                files[PREFIX+'docs/'+n]=(ROOT/'docs'/n).read_bytes()
            files[PREFIX+'licenses/Opus-COPYING.txt']=(ROOT/'licenses/Opus-COPYING.txt').read_bytes()
            files[PREFIX+'MuoniumPlayerBedrock.dll']=dll
            files[PREFIX+'jvm/lib/MuoniumBedrockHost.jar']=host
            files[PREFIX+'jvm/voice/MuoniumVoice.dll']=codec
            if kind=='full':files[PREFIX+'sources/'+source_name]=(a.output/source_name).read_bytes()
            files['TESTING.md']=(ROOT/'docs/ROOM-VOICE.md').read_bytes();files['VERIFICATION.json']=encoded(report)
            files['INSTALL.md']=('Exit Minecraft, extract into the instance root, preserve settings/accounts/playlists.\n'
                f'Edition: {edition}; version: {VERSION}; package: {kind}.\n'
                +('Java21 runtime is included.\n' if kind=='full' else 'An existing full Java21 player environment is required.\n')
                +'ModMenuBE is separate; no Minecraft server addon required for room voice. See TESTING.md.\n').encode()
            files['CANDIDATE.json']=encoded(dict(version=VERSION,edition=edition,package=kind,clientOnlyRoomVoice=True,bschatIncluded=False,manualMinecraftAcceptance='pending'))
            assert not any(n.startswith('mods/') and not n.startswith(PREFIX) for n in files)
            assert not any(n.lower().endswith(('modmenube.dll','modmenubedrock.dll','/muoniumplayerbedrock.json','/config.json','/room-voice.json')) for n in files)
            assert not any('/logs/' in n or '/cache/' in n for n in files)
            files[PREFIX+'PACKAGE-MANIFEST.json']=encoded(dict(version=VERSION,edition=edition,package=kind,
                files={n:dict(bytes=len(d),sha256=sha(d)) for n,d in sorted(files.items())}))
            archive(a.output/f'MuoniumPlayerBedrock-{VERSION}-{edition}-{suffix}-{kind}.zip',files,True)
    audits={p.name:audit(p) for p in sorted(a.output.glob('*.zip'))}
    assert all(sha(Path(n).read_bytes())==h for n,h in frozen.items())
    for n,data in [('VERIFICATION.json',encoded(report)),('PUBLIC-ASSET-AUDIT.json',encoded(audits)),
        ('TESTING.md',(ROOT/'docs/ROOM-VOICE.md').read_bytes()),(f'UPDATE-{VERSION}.md',(ROOT/f'docs/UPDATE-{VERSION}.md').read_bytes())]:
        (a.output/n).write_bytes(data)
    sums={p.name:sha(p.read_bytes()) for p in a.output.iterdir() if p.is_file()}
    (a.output/'SHA256SUMS.txt').write_text(''.join(f'{h}  {n}\n' for n,h in sorted(sums.items())),encoding='ascii')
    print(json.dumps(dict(output=str(a.output),jvmTests=report['freshJvmTests'],nativeSuites=34,audits=audits),indent=2))

if __name__=='__main__':main()
