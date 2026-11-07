import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('publisher', Path(__file__).parents[1] / 'publish-release.py')
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class PublicationGuardsTest(unittest.TestCase):
    def setUp(self):
        self.repo = 'owner/WebShortcuts'
        self.sha = 'a' * 40
        self.run = {'repository': {'full_name': self.repo}, 'head_repository': {'full_name': self.repo},
                    'status': 'completed', 'conclusion': 'success', 'head_sha': self.sha,
                    'path': '.github/workflows/android.yml', 'event': 'workflow_dispatch'}

    def test_successful_exact_commit_build_is_accepted(self):
        publisher.validate_run(self.run, self.repo, self.sha)

    def test_another_commit_cannot_be_released_under_this_tag(self):
        with self.assertRaisesRegex(ValueError, 'does not match'):
            publisher.validate_run(self.run, self.repo, 'b' * 40)

    def test_failed_or_incomplete_build_cannot_publish(self):
        for state, conclusion in [('in_progress', None), ('completed', 'failure'), ('completed', 'cancelled')]:
            run = dict(self.run, status=state, conclusion=conclusion)
            with self.assertRaisesRegex(ValueError, 'finish successfully'):
                publisher.validate_run(run, self.repo, self.sha)

    def test_artifacts_from_a_fork_cannot_publish(self):
        run = dict(self.run, head_repository={'full_name': 'fork/WebShortcuts'})
        with self.assertRaisesRegex(ValueError, 'this repository'):
            publisher.validate_run(run, self.repo, self.sha)

    def test_pull_request_merge_build_is_not_a_tag_build(self):
        run = dict(self.run, event='pull_request')
        with self.assertRaisesRegex(ValueError, 'merge build'):
            publisher.validate_run(run, self.repo, self.sha)

    def test_unrelated_workflow_cannot_publish(self):
        run = dict(self.run, path='.github/workflows/other.yml')
        with self.assertRaisesRegex(ValueError, 'Android CI'):
            publisher.validate_run(run, self.repo, self.sha)

    def test_conflicting_manual_modes_fail_instead_of_reporting_false_success(self):
        with patch.dict('os.environ', {'INSPECT_RUN_ID': '42'}):
            with self.assertRaisesRegex(ValueError, 'not both'):
                publisher.inputs()

    def test_junit_suite_and_skip_counts_are_retained(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'TEST-jvm.xml').write_text('<testsuite tests="4" failures="0" errors="0" skipped="1"/>')
            (root / 'TEST-device.xml').write_text('<testsuites><testsuite tests="3" failures="0" errors="0"/></testsuites>')
            self.assertEqual({'tests': 7, 'failures': 0, 'errors': 0, 'skipped': 1}, publisher.test_counts(root))

    def test_empty_or_failing_junit_reports_block_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                publisher.test_counts(directory)
            (Path(directory) / 'TEST-failed.xml').write_text('<testsuite tests="3" failures="1" errors="0"/>')
            with self.assertRaises(ValueError):
                publisher.test_counts(directory)


if __name__ == '__main__':
    unittest.main()
