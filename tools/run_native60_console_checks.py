"""Targeted offline admission/ModMenuBE external-screen regression. Never starts Minecraft."""
from pathlib import Path
import argparse, json, shutil, subprocess

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--instances', required=True, type=Path)
    p.add_argument('--output', required=True, type=Path)
    a = p.parse_args()
    root = Path(__file__).resolve().parents[1]
    a.output.mkdir(parents=True, exist_ok=True)
    def run(args, name, timeout=600):
        result = subprocess.run([str(v) for v in args], cwd=root, capture_output=True, text=True,
                                encoding='utf-8', errors='replace', timeout=timeout)
        (a.output/(name+'.log')).write_text(result.stdout+result.stderr, encoding='utf-8')
        if result.returncode: raise RuntimeError((name, result.returncode, result.stdout[-2500:], result.stderr[-2500:]))
        return {'exit': result.returncode, 'output': result.stdout, 'stderr': result.stderr}
    suites = ['Native60ConsoleAdmissionTests', 'Native51VanillaAdmissionTests', 'NativeContractTests',
              'LoaderPolicyTests', 'OlderVersionTests', 'OlderSceneFixtureTests', 'MenuSettingsInterop', 'Local3RouteTests','WorkspaceTransportTests']
    builds = suites + ['MenuBEApiFixture', 'MenuBEInterop','MenuBEWorkspaceTests']
    report = {}
    for mode in ['debug', 'release']:
        print('Building and verifying '+mode, flush=True)
        run(['xmake', 'f', '-y', '-m', mode, '--menu_fixture=y', '--cxflags=/Z7 /FS'], mode+'-configure')
        for name in builds:
            run(['xmake', 'build', '-y', '-j', '2', name], mode+'-build-'+name)
        b = root/f'build/windows/x64/{mode}'
        report[mode] = {}
        for name in suites:
            args = [b/(name+'.exe')]
            if name == 'Native60ConsoleAdmissionTests':
                args += [a.instances/'1.26.60.05'/n for n in ['Minecraft.Windows.exe.orig', 'Minecraft.Windows.exe']]
            elif name == 'Native51VanillaAdmissionTests':
                args += [a.instances/'1.26.51.01'/n for n in ['Minecraft.Windows.exe.orig', 'Minecraft.Windows.exe']]
            elif name == 'OlderVersionTests':
                args += [a.instances/v/'Minecraft.Windows.exe' for v in ['1.26.45.01', '1.26.51.01', '1.26.52.03', '1.26.60.05']]
            elif name in ['MenuSettingsInterop','Local3RouteTests']: args += [root]
            report[mode][name] = run(args, mode+'-'+name)
        for api_mode in range(10):
            for order in ['late','early']:
                args=[b/'MenuBEInterop.exe',b/'menube-fixture/ModMenuBE.dll',str(api_mode)]
                if order=='early':args+=['early']
                key=f'MenuBEInterop-{api_mode}-{order}'
                report[mode][key]=run(args,mode+'-'+key)
        report[mode]['MenuBELocal3Apply']=run([b/'MenuBEInterop.exe',b/'menube-fixture/ModMenuBE.dll','0','late',root],mode+'-MenuBELocal3Apply')
        for workspace_mode in range(5):
            for order in ['early','late']:
                key=f'MenuBEWorkspace-{workspace_mode}-{order}'
                args=[b/'MenuBEWorkspaceTests.exe',b/'workspace-mods',root,str(workspace_mode)]
                if order=='late':args+=['late']
                report[mode][key]=run(args,mode+'-'+key)
        (a.output/'results.json').write_text(json.dumps(report, indent=2)+'\n')
        print(mode+': '+str(len(report[mode]))+' suites passed', flush=True)
    for target in ['MuoniumPlayerBedrock','MuoniumPlayerBedrockLL','LeviAllocatorStub']:
        run(['xmake', 'build', '-y','-j','2',target], 'release-build-'+target)
    smoke = a.output/'unsupported-host'
    smoke.mkdir(parents=True, exist_ok=True)
    dll = smoke/'MuoniumPlayerBedrock.dll'
    shutil.copy2(root/'build/windows/x64/release/MuoniumPlayerBedrock.dll', dll)
    report['unsupportedHost'] = run(['py', root/'tools/unsupported_host_smoke.py', dll], 'unsupported-host')
    report['unsupportedLeviHost'] = run(['py', root/'tools/unsupported_levi_smoke.py'], 'unsupported-levi-host')
    (a.output/'results.json').write_text(json.dumps(report, indent=2)+'\n')
    print('Production Release DLL built; unrelated host refusal passed', flush=True)

if __name__ == '__main__': main()
