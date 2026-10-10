"""Behavioral tests for the user-owned Kunai personal-source CLI."""
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("kunai-personal-source.py")


class PersonalSourceCliTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.file = Path(self.tmp.name) / "personal-sources.json"
        self.env = dict(os.environ, KUNAI_PERSONAL_SOURCES_FILE=str(self.file))

    def run_cli(self, *args):
        return subprocess.run([sys.executable, str(SCRIPT), *args], env=self.env,
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_add_private_deduplicated_and_remove(self):
        url = "https://media.example.test/movie.m3u8?private=token"
        for _ in range(2):
            result = self.run_cli("add", "612654", url, "--title", "Fantastic Fungi")
            self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.file.stat().st_mode & 0o777, 0o600)
        data = json.loads(self.file.read_text())
        self.assertEqual(len(data["titles"]["tmdb:612654"]["sources"]), 1)
        listed = self.run_cli("list")
        self.assertIn("Fantastic Fungi", listed.stdout)
        self.assertNotIn("private=token", listed.stdout)
        removed = self.run_cli("remove", "612654")
        self.assertEqual(removed.returncode, 0)
        self.assertEqual(json.loads(self.file.read_text())["titles"], {})

    def test_invalid_url_does_not_mutate_file(self):
        result = self.run_cli("add", "612654", "file:///etc/passwd")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.file.exists())

    def test_series_requires_coordinates(self):
        missing = self.run_cli("add", "1399", "https://test.example/ep.mp4", "--kind", "series")
        self.assertNotEqual(missing.returncode, 0)
        done = self.run_cli("add", "1399", "https://test.example/ep.mp4", "--kind", "series", "--season", "1", "--episode", "2")
        self.assertEqual(done.returncode, 0, done.stderr)
        data = json.loads(self.file.read_text())
        self.assertIn("tmdb:1399:s1e2", data["titles"])


if __name__ == "__main__":
    unittest.main()
