"""Prevent private timetable fixtures and API keys from entering the distributable tree."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]

# The key lives only in Keystore-encrypted SharedPreferences on the device, so no source file,
# resource or test fixture should ever carry one.
KEY_PATTERNS = (
    re.compile(r'sk-[A-Za-z0-9_\-]{16,}'),          # OpenAI, DeepSeek, Moonshot and similar
    re.compile(r'AIza[0-9A-Za-z_\-]{30,}'),          # Google
    re.compile(r'(?i)\bapi[_-]?key\b\s*[:=]\s*["\'][^"\']{12,}["\']'),
)
SOURCE_SUFFIXES = ('.java', '.kt', '.xml', '.gradle', '.properties', '.json', '.py', '.md', '.cmd', '.ps1', '.txt', '.csv')
SKIP_DIRS = {'build', '.git', '.gradle', '.idea'}


class CleanReleaseTests(unittest.TestCase):
    def test_no_auto_seed(self):
        source = (ROOT / 'app/src/main/java/com/kejian/app/MainActivity.java').read_text(encoding='utf-8')
        self.assertNotIn('seedProvidedTimetable', source)

    def test_no_private_fixtures(self):
        for name in ('app/src/main/assets/sample-result.json', 'app/src/androidTest/assets/sample.xls',
                     'server/fixtures/sample.xls', 'server/fixtures/sample-result.json'):
            with self.subTest(path=name):
                self.assertFalse((ROOT / name).exists(), 'Remove personal sample: ' + name)

    def test_no_old_screenshots(self):
        self.assertEqual(list((ROOT / 'screenshots').glob('*.png')), [])

    def test_no_api_key_literals(self):
        for path in sorted(ROOT.rglob('*')):
            if not path.is_file() or path.suffix.lower() not in SOURCE_SUFFIXES:
                continue
            if SKIP_DIRS & set(path.relative_to(ROOT).parts):
                continue
            try:
                text = path.read_text(encoding='utf-8')
            except (UnicodeDecodeError, OSError):
                continue
            for pattern in KEY_PATTERNS:
                match = pattern.search(text)
                with self.subTest(path=str(path.relative_to(ROOT)), pattern=pattern.pattern):
                    self.assertIsNone(
                        match, 'Possible API key in %s: %s' % (path.relative_to(ROOT), match and match.group(0)[:12]))

    def test_no_desktop_service_in_app(self):
        """The phone must not fall back to the retired local service."""
        for path in (ROOT / 'app/src/main').rglob('*.java'):
            text = path.read_text(encoding='utf-8')
            for token in ('10.0.2.2:8765', '/parse', 'contentBase64'):
                with self.subTest(path=path.name, token=token):
                    self.assertNotIn(token, text)


if __name__ == '__main__':
    unittest.main()

