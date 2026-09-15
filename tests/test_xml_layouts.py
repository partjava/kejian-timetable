"""Contract for the teaching-friendly XML migration (no Android runtime needed)."""
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LAYOUT = ROOT / 'app/src/main/res/layout'
A = '{http://schemas.android.com/apk/res/android}'
T = '{http://schemas.android.com/tools}'

class XmlLayoutsTest(unittest.TestCase):
    def test_theme_uses_supported_system_font(self):
        root = ET.parse(LAYOUT.parent / 'values/styles.xml').getroot()
        family = root.find("style[@name='AppTheme']/item[@name='android:fontFamily']")
        self.assertEqual('sans-serif', family.text)

    def test_weighted_pages_have_standalone_preview_height(self):
        for name in ['page_settings', 'page_ai_settings']:
            root = self.layout(name)
            self.assertEqual('0dp', root.get(A + 'layout_height'))
            self.assertEqual('1', root.get(A + 'layout_weight'))
            self.assertEqual('match_parent', root.get(T + 'layout_height'), name)

    def test_dynamic_setting_labels_have_preview_only_samples(self):
        for name in ['item_setting', 'item_setting_toggle']:
            root = self.layout(name)
            for label in ['setting_title', 'setting_detail']:
                element = next(e for e in root.iter() if e.get(A + 'id') == '@+id/' + label)
                self.assertTrue(element.get(T + 'text'), name + '/' + label)
                self.assertIsNone(element.get(A + 'text'))

    def layout(self, name):
        path = LAYOUT / (name + '.xml')
        self.assertTrue(path.exists(), 'Missing XML layout: ' + name)
        return ET.parse(path).getroot()

    def test_ai_real_controls_and_private_key(self):
        root = self.layout('page_ai_settings')
        inputs = list(root.iter('EditText'))
        self.assertEqual(3, len(inputs))
        keys = [e for e in inputs if 'textPassword' in e.get(A + 'inputType', '')]
        self.assertEqual(1, len(keys))
        self.assertEqual('false', keys[0].get(A + 'saveEnabled'))
        ids = {e.get(A + 'id', '').split('/')[-1] for e in root.iter()}
        self.assertTrue({'ai_endpoint', 'ai_model', 'ai_key', 'ai_status', 'ai_test', 'ai_save'}.issubset(ids))

    def test_ai_return_preserves_full_width_alignment(self):
        root = self.layout('page_ai_settings')
        back = next(e for e in root.iter() if e.get(A + 'id') == '@+id/ai_return')
        self.assertEqual('match_parent', back.get(A + 'layout_width'))

    def test_editor_real_controls(self):
        root = self.layout('dialog_course_editor')
        self.assertGreaterEqual(len(list(root.iter('EditText'))), 4)
        self.assertEqual(3, len(list(root.iter('Spinner'))))
        ids = {e.get(A + 'id', '').split('/')[-1] for e in root.iter()}
        self.assertTrue({'course_name', 'course_teacher', 'course_room', 'course_notes', 'course_save', 'course_close'}.issubset(ids))

    def test_settings_has_reusable_rows(self):
        root = self.layout('page_settings')
        self.assertGreaterEqual(len(list(root.iter('include'))), 9)
        self.layout('item_setting')
        toggle = self.layout('item_setting_toggle')
        self.assertEqual(1, len(list(toggle.iter('Switch'))))

    def test_unique_ids_per_layout(self):
        for name in ['page_settings', 'page_ai_settings', 'dialog_course_editor']:
            root = self.layout(name)
            ids = [e.get(A + 'id') for e in root.iter() if e.get(A + 'id')]
            self.assertEqual(len(ids), len(set(ids)), name)

if __name__ == '__main__':
    unittest.main()
