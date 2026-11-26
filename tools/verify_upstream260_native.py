"""Run owned native fixtures; never starts or changes Minecraft."""
from pathlib import Path
import argparse,json,subprocess

ROOT=Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--instances',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    binaries=ROOT/'build/windows/x64/release'
    names=['NativeContractTests','HostAdmissionTests','LoaderPolicyTests','PlayerConfigFileTests',
        'OlderSceneFixtureTests','WorldCaptureFixtureTests','JvmPolicyTests','Local3RouteTests',
        'CinematicTests','CinematicInputTests','CinematicTraceTests','CinematicBridgeTests','CinematicRegistryTests',
        'EnvironmentTests','EnvironmentMaterialFixtureTests','EntityTests','DepthTests','DepthHookTests',
        'MetricsTests','NetworkFixtureTests','PlayerTests','Phase2Tests','NativeTests','WorldBridgeTests',
        'CompositionTests','CompositionGpuTests','CompositionHookTests','D3D12AdmissionTests',
        'D3D12RuntimeTests','D3D12TransferTests','D3D12NativeTests',
        'OlderVersionTests','Native51VanillaAdmissionTests','Native60ConsoleAdmissionTests']
    report={'mode':'release','suites':{}}
    for name in names:
        args=[binaries/(name+'.exe')]
        if name=='Local3RouteTests':args+=[ROOT]
        if name=='HostAdmissionTests':args+=[
            a.instances/'1.26.52.03/Minecraft.Windows.exe',
            a.instances/'1.26.60.05/Minecraft.Windows.exe.orig',
            a.instances/'1.26.60.05/Minecraft.Windows.exe',
            a.instances/'1.26.60.05 levilamina/Minecraft.Windows.exe']
        if name=='OlderVersionTests':args+=[a.instances/v/'Minecraft.Windows.exe' for v in
            ['1.26.45.01','1.26.51.01','1.26.52.03','1.26.60.05']]
        if name in ['Native51VanillaAdmissionTests','Native60ConsoleAdmissionTests']:
            v='1.26.51.01' if '51Vanilla' in name else '1.26.60.05'
            args+=[a.instances/v/n for n in ['Minecraft.Windows.exe.orig','Minecraft.Windows.exe']]
        print(name,flush=True)
        try:
            r=subprocess.run([str(v) for v in args],cwd=a.output,capture_output=True,timeout=150)
            output=r.stdout.decode('utf-8','replace')+r.stderr.decode('utf-8','replace')
            (a.output/(name+'.log')).write_text(output,encoding='utf-8')
            report['suites'][name]={'exit':r.returncode,'output':output}
        except subprocess.TimeoutExpired:
            report['suites'][name]={'exit':-1,'output':'Timed out'}
        (a.output/'results.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    failures=[k for k,v in report['suites'].items() if v['exit']]
    print(json.dumps({'passed':len(names)-len(failures),'failures':failures}))
    if failures:raise SystemExit(1)

if __name__=='__main__':main()
