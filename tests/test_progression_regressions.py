"""Focused real-CPG oracle; outputs can be retained for baseline/delta inspection."""
import json
import os
from pathlib import Path
import subprocess
import time
import unittest
from collections import Counter

from sajaniemi_extractor.variable_aware import resolve_variable_aware_output, legacy_annotations_from_roles
from tests.scope_validation import validate_output_invariants, canonical_json_digest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'tests/fixtures/progression_code'
EXPECTED = {
    'C++': {
        'walker': (['direct', 'producer', 'pre', 'u', 'element', 'unbraced_it', 'rpit', 'typed', 'nested'],
                   ['index', 'row', 'object_index', 'ptr_index', 'cross']),
        'stepper': (['index', 'row', 'object_index', 'ptr_index', 'h', 'ind', 'two', 'braced', 'braced_h'],
                    ['direct', 'producer', 'pre', 'unbraced_it', 'rpit', 'typed', 'nested', 'u', 'after', 'lambda_only', 'score', 'dynamic', 'guarded']),
        'gatherer': (['total'], ['u']),
    },
    'Python': {
        'stepper': (['i', 'fixed_step', 'multi'],
                    ['reset', 'dynamic', 'mutated', 'guarded', 'unread', 'constructor_only', 'ambiguous', 'score', 'results']),
    },
}

@unittest.skipUnless(os.environ.get('RUN_JOERN_PROGRESSION_FIXTURES') == '1', 'opt-in focused Joern fixtures')
class ProgressionRegressionTests(unittest.TestCase):
    def test_selected_frontend(self):
        language = os.environ.get('PROGRESSION_LANGUAGE', 'C++')
        output = Path(os.environ['PROGRESSION_OUTPUT']).resolve()
        output.mkdir(parents=True, exist_ok=True)
        graph = output / 'graph.bin'
        if not graph.exists():
            with (output / 'parse.log').open('w') as log:
                subprocess.run(['joern-parse', str(SOURCE / language), '--language',
                                'c' if language == 'C++' else 'pythonsrc', '--output', str(graph)],
                               cwd=output, check=True, stdout=log, stderr=subprocess.STDOUT)
        pre = output / 'pre.json'
        started = time.monotonic()
        if os.environ.get('PROGRESSION_REUSE_OUTPUT') != '1':
            with (output / 'extract.log').open('w') as log:
                subprocess.run(['joern', str(graph), '--script', str(ROOT / 'src/scala/extract_dynamic_variables.sc'),
                                '--param', f'output={pre}', '--param', f'sourceRoot={SOURCE}', '--nocolors'],
                               cwd=output, check=True, stdout=log, stderr=subprocess.STDOUT,
                               env={**os.environ, 'SAJANIEMI_PROFILE': '1'}, timeout=600)
        resolved = resolve_variable_aware_output(pre, SOURCE)
        validate_output_invariants(resolved)
        (output / 'resolved.json').write_text(json.dumps(resolved, indent=2))
        records = resolved['variable_facts']
        frozen_views = json.loads((ROOT / 'tests/fixtures/progression_view_digests.json').read_text())[language]
        by_id = {r['subject']['id']: r for r in records}
        for subject_id, digest in frozen_views.items():
            record = by_id[subject_id]
            invariant = {'subject': {k: v for k, v in record['subject'].items() if k != 'joern_id'},
                         'views': record['views'], 'is_collection': record['is_collection']}
            self.assertEqual(canonical_json_digest(invariant), digest, subject_id)
        defects = []
        for role, (present, absent) in EXPECTED[language].items():
            for names, required in [(present, True), (absent, False)]:
                for name in names:
                    matched = [r for r in records if r['subject']['name'] == name]
                    if not matched or any((role in r['roles']) != required for r in matched):
                        defects.append((name, role, required, [r['roles'] for r in matched]))
        report = dict(seconds=time.monotonic() - started, variables=len(records),
                      roles=Counter(role for r in records for role in r['roles']),
                      role_records=len(resolved['role_annotations']),
                      legacy=len(legacy_annotations_from_roles(resolved['role_annotations'])), defects=defects)
        (output / 'report.json').write_text(json.dumps(report, indent=2))
        print(json.dumps(report))
        self.assertFalse(defects, defects)
