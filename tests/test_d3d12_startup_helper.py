"""Exercise only owned filesystem fixtures; never launch or change Minecraft."""
import hashlib
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / 'tools' / 'Test-GameD3D12.ps1'
GAME = Path('D:/bedrock_Tool/test_1/versions/1.26.52.03')
ARTIFACTS = ROOT / 'artifacts' / 'd3d12-backend-selection'
BACKUP = 'd3d12.dll.muonium-d3d12-test-disabled'


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest().upper()


class StartupHelperTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        ARTIFACTS.mkdir(exist_ok=True)
        cls.temporary = tempfile.TemporaryDirectory(prefix='owned-startup-test-', dir=ARTIFACTS)
        cls.game = Path(cls.temporary.name)
        for name in ['Minecraft.Windows.exe.orig', 'Minecraft.Windows.exe', 'd3d12.dll']:
            shutil.copyfile(GAME / name, cls.game / name)
        cls.original_stub = (cls.game / 'd3d12.dll').read_bytes()
        (cls.game / 'options.txt').write_bytes(b'graphics_api:0\r\nuser_setting:preserve\r\n')
        cls.protected = {n: digest(cls.game / n) for n in
                         ['Minecraft.Windows.exe.orig', 'Minecraft.Windows.exe', 'options.txt']}

    @classmethod
    def tearDownClass(cls):
        assert cls.game.resolve().parent == ARTIFACTS.resolve()
        cls.temporary.cleanup()

    def setUp(self):
        for name in ['d3d12.dll', BACKUP]:
            path = self.game / name
            if path.exists():
                path.unlink()
        (self.game / 'd3d12.dll').write_bytes(self.original_stub)

    def tearDown(self):
        for name, expected in self.protected.items():
            self.assertEqual(digest(self.game / name), expected, name + ' unexpectedly changed')

    def invoke(self, mode='Inspect'):
        if not HELPER.is_file():
            return 1, {'error': 'D3D12 startup helper has not been implemented'}
        run = subprocess.run(['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                              '-File', str(HELPER), '-GameRoot', str(self.game), '-Mode', mode],
                             text=True, capture_output=True, timeout=45)
        try:
            report = json.loads(run.stdout)
        except ValueError:
            report = {'stdout': run.stdout, 'stderr': run.stderr}
        return run.returncode, report

    def test_inspect_is_read_only(self):
        before = sorted(p.name for p in self.game.iterdir())
        code, report = self.invoke()
        self.assertEqual(code, 0, report)
        self.assertEqual(report['state'], 'BlockerPresent')
        self.assertEqual(sorted(p.name for p in self.game.iterdir()), before)
        self.assertEqual((self.game / 'd3d12.dll').read_bytes(), self.original_stub)

    def test_enable_and_restore_exact_bytes(self):
        code, report = self.invoke('EnableTest')
        self.assertEqual(code, 0, report)
        self.assertFalse((self.game / 'd3d12.dll').exists())
        self.assertEqual((self.game / BACKUP).read_bytes(), self.original_stub)
        self.assertEqual(report['state'], 'TestEnabled')
        code, report = self.invoke('Restore')
        self.assertEqual(code, 0, report)
        self.assertEqual(report['state'], 'BlockerPresent')
        self.assertFalse((self.game / BACKUP).exists())
        self.assertEqual((self.game / 'd3d12.dll').read_bytes(), self.original_stub)

    def test_repeat_commands_are_idempotent(self):
        for mode, state in [('EnableTest', 'TestEnabled'), ('EnableTest', 'TestEnabled'),
                            ('Restore', 'BlockerPresent'), ('Restore', 'BlockerPresent')]:
            code, report = self.invoke(mode)
            self.assertEqual(code, 0, report)
            self.assertEqual(report['state'], state)

    def test_unknown_dll_is_preserved(self):
        unknown = b'This is another component. Never rename it.'
        (self.game / 'd3d12.dll').write_bytes(unknown)
        code, _ = self.invoke('EnableTest')
        self.assertNotEqual(code, 0)
        self.assertEqual((self.game / 'd3d12.dll').read_bytes(), unknown)
        self.assertFalse((self.game / BACKUP).exists())

    def test_conflicting_backup_is_not_overwritten(self):
        unknown = b'unrelated backup'
        (self.game / BACKUP).write_bytes(unknown)
        for mode in ['EnableTest', 'Restore']:
            code, _ = self.invoke(mode)
            self.assertNotEqual(code, 0)
            self.assertEqual((self.game / BACKUP).read_bytes(), unknown)
            self.assertEqual((self.game / 'd3d12.dll').read_bytes(), self.original_stub)

    def test_restore_does_not_overwrite_new_runtime(self):
        code, report = self.invoke('EnableTest')
        self.assertEqual(code, 0, report)
        (self.game / 'd3d12.dll').write_bytes(b'new runtime installed while test enabled')
        code, _ = self.invoke('Restore')
        self.assertNotEqual(code, 0)
        self.assertEqual((self.game / 'd3d12.dll').read_bytes(), b'new runtime installed while test enabled')
        self.assertEqual((self.game / BACKUP).read_bytes(), self.original_stub)

    def test_missing_both_files_is_not_guessed(self):
        (self.game / 'd3d12.dll').unlink()
        code, _ = self.invoke('EnableTest')
        self.assertNotEqual(code, 0)
        self.assertFalse((self.game / 'd3d12.dll').exists())
        self.assertFalse((self.game / BACKUP).exists())

    def test_corrupt_disabled_file_is_not_restored(self):
        (self.game / 'd3d12.dll').unlink()
        (self.game / BACKUP).write_bytes(b'corrupt backup')
        code, _ = self.invoke('Restore')
        self.assertNotEqual(code, 0)
        self.assertFalse((self.game / 'd3d12.dll').exists())
        self.assertEqual((self.game / BACKUP).read_bytes(), b'corrupt backup')

    def test_exact_game_mismatch_prevents_mutation(self):
        image = self.game / 'Minecraft.Windows.exe'
        with image.open('r+b') as stream:
            old = stream.read(1)
            stream.seek(0)
            stream.write(b'!')
        try:
            code, _ = self.invoke('EnableTest')
            self.assertNotEqual(code, 0)
            self.assertTrue((self.game / 'd3d12.dll').exists())
            self.assertFalse((self.game / BACKUP).exists())
        finally:
            with image.open('r+b') as stream:
                stream.write(old)

    def test_running_game_guard_without_launching_game(self):
        self.assertTrue(HELPER.is_file(), 'startup helper is missing')
        helper = str(HELPER).replace("'", "''")
        game = str(self.game).replace("'", "''")
        command = (f". '{helper}'; function Get-Process {{ [pscustomobject]@{{Id=123;ProcessName='Minecraft.Windows'}} }}; "
                   f"try {{ Invoke-GameD3D12Test -GameRoot '{game}' -Mode EnableTest; exit 2 }} "
                   "catch { if ($_.Exception.Message -match 'Minecraft is running') { exit 0 }; throw }")
        run = subprocess.run(['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', command],
                             text=True, capture_output=True, timeout=45)
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertTrue((self.game / 'd3d12.dll').exists())
        self.assertFalse((self.game / BACKUP).exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
