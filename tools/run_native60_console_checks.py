"""Targeted offline admission/Workspace regression. Never starts Minecraft."""
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
              'LoaderPolicyTests', 'OlderVersionTests', 'OlderSceneFixtureTests', 'MenuInteropLL']
    builds = suites + ['MenuLLAbiFixture', 'MenuVanillaAbiFixture', 'MenuVanillaProviderFixture',
                       'MenuLLProviderFixture', 'MenuLiveInterop']
    report = {}
    for mode in ['debug', 'release']:
        print('Building and verifying '+mode, flush=True)
        run(['xmake', 'f', '-y', '-m', mode, '--menu_fixture=y'], mode+'-configure')
        for name in builds:
            run(['xmake', 'build', '-y', name], mode+'-build-'+name)
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
            elif name == 'MenuInteropLL': args += [root]
            report[mode][name] = run(args, mode+'-'+name)
        for edition in ['vanilla', 'LL']:
            mods = b/('menu-vanilla-fixture/mods' if edition == 'vanilla' else 'menu-ll-fixture/mods')
            for menu in ['ModMenuBedrock', 'ModMenuBedrockLL']:
                target = mods/menu/(menu+'.dll');target.parent.mkdir(parents=True, exist_ok=True)
                source = b/'menu-ll-fixture/mods'/menu/(menu+'.dll')
                if source != target: shutil.copy2(source, target)
            plugin = mods/'MuoniumPlayerBedrock'
            shutil.copy2(root/'manifest.json', plugin/'manifest.json')
            menu, other = ('ModMenuBedrock', 'ModMenuBedrockLL') if edition == 'vanilla' else ('ModMenuBedrockLL', 'ModMenuBedrock')
            report[mode]['MenuLiveInterop-'+edition] = run([b/'MenuLiveInterop.exe', plugin/'MuoniumPlayerBedrock.dll',
                mods/menu/(menu+'.dll'), mods/other/(other+'.dll')], mode+'-MenuLiveInterop-'+edition)
        (a.output/'results.json').write_text(json.dumps(report, indent=2)+'\n')
        print(mode+': '+str(len(report[mode]))+' suites passed', flush=True)
    run(['xmake', 'build', '-y', 'MuoniumPlayerBedrock'], 'release-build-DLL')
    smoke = root/'artifacts/modmenu-test11-review/unsupported-host'
    smoke.mkdir(parents=True, exist_ok=True)
    dll = smoke/'MuoniumPlayerBedrock.dll'
    shutil.copy2(root/'build/windows/x64/release/MuoniumPlayerBedrock.dll', dll)
    report['unsupportedHost'] = run(['py', root/'tools/unsupported_host_smoke.py', dll], 'unsupported-host')
    (a.output/'results.json').write_text(json.dumps(report, indent=2)+'\n')
    print('Production Release DLL built; unrelated host refusal passed', flush=True)

if __name__ == '__main__': main()
