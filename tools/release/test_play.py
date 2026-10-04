"""The release script's decisions that run before anything reaches Play.

    python3 -m unittest tools/release/test_play.py

Nothing here talks to Play or to Gemini. What is covered is what those calls are given: the
commit a version code names, the text that goes out as the release notes, and the track body.
"""
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import play  # noqa: E402


class VersionCodeToCommitTest(unittest.TestCase):
    """A repository shaped like main: a line of commits with one merged branch in it."""

    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.TemporaryDirectory()
        cls.root = Path(cls.dir.name)
        cls.saved_root, play.ROOT = play.ROOT, cls.root

        def run(*args):
            return subprocess.run(
                ["git", *args], cwd=cls.root, check=True, capture_output=True, text=True,
            ).stdout.strip()

        def commit(path, message):
            (cls.root / path).parent.mkdir(parents=True, exist_ok=True)
            (cls.root / path).write_text(message)
            run("add", path)
            run("commit", "-q", "-m", message)
            return run("rev-parse", "HEAD")

        run("init", "-q", "-b", "main")
        run("config", "user.email", "test@example.com")
        run("config", "user.name", "test")
        (cls.root / "android" / "app").mkdir(parents=True)
        (cls.root / "android" / "app" / "build.gradle.kts").write_text('versionName = "1.0"\n')
        run("add", "android/app/build.gradle.kts")
        cls.c1 = commit("android/app/a.kt", "one")
        cls.c2 = commit("docs/results/b.md", "two")
        cls.c3 = commit("shared/engine/src/commonMain/c.kt", "three")
        run("checkout", "-q", "-b", "side")
        commit("tools/compat/d.py", "side one")
        commit("tools/compat/e.py", "side two")
        commit("tools/compat/f.py", "side three")
        run("checkout", "-q", "main")
        run("merge", "-q", "--no-ff", "-m", "merge", "side")
        cls.merge = run("rev-parse", "HEAD")
        cls.c4 = commit("android/app/g.kt", "four\n\nWhy it changed.\n\nCo-Authored-By: Someone <someone@example.com>")
        cls.c5 = commit("android/app/h.kt", "five")

    @classmethod
    def tearDownClass(cls):
        play.ROOT = cls.saved_root
        cls.dir.cleanup()

    def test_a_code_on_main_names_its_own_commit(self):
        self.assertEqual(play.commit_count(), 9)
        self.assertEqual(play.commit_for_code(9), (self.c5, True))
        self.assertEqual(play.commit_for_code(8), (self.c4, True))
        self.assertEqual(play.commit_for_code(7), (self.merge, True))
        self.assertEqual(play.commit_for_code(1), (self.c1, True))

    def test_a_code_inside_a_merge_falls_back_to_the_commit_before_it(self):
        # 4 to 6 were only ever counted on the side branch; the notes then start earlier,
        # listing more changes rather than fewer.
        for code in (4, 5, 6):
            self.assertEqual(play.commit_for_code(code), (self.c3, False))

    def test_a_code_older_than_the_repository_is_refused(self):
        with self.assertRaises(play.ReleaseError):
            play.commit_for_code(0)

    def test_the_changes_are_every_commit_after_the_live_one_without_the_merge(self):
        commits = play.commits_between(self.c3)
        self.assertEqual([c.subject for c in commits], ["five", "four", "side three", "side two", "side one"])
        self.assertEqual(commits[0].areas, ["android/app"])
        self.assertEqual(commits[1].body, "Why it changed.")
        self.assertEqual(commits[-1].areas, ["tools/compat"])


class PromoteTest(VersionCodeToCommitTest):
    """Notes for a bundle Play already has: the commits up to it, not up to main."""

    def notes(self, base_code, version_code):
        out = tempfile.mkdtemp()
        args = SimpleNamespace(track="production", out=out, base_code=base_code, version_code=version_code)
        with mock.patch.dict(os.environ, {"WHATS_NEW": "• Something.", "GITHUB_OUTPUT": str(Path(out) / "o")}), \
                mock.patch.object(play, "summary"):
            play.notes_command(args)
        return (Path(out) / "changes.md").read_text(), (Path(out) / "o").read_text()

    def test_the_notes_stop_at_the_promoted_bundle(self):
        changes, outputs = self.notes(3, 8)
        self.assertIn("Promotes the bundle Play already has", changes)
        self.assertIn("four", changes)
        self.assertNotIn("five", changes)
        self.assertIn("release_name=1.0 (8)", outputs)
        self.assertIn("version_code=8", outputs)

    def test_a_code_no_commit_on_main_built_is_refused(self):
        with self.assertRaises(play.ReleaseError):
            self.notes(3, 5)

    def test_a_code_users_already_have_is_refused(self):
        with self.assertRaises(play.ReleaseError):
            self.notes(8, 8)

    def test_publish_needs_exactly_one_source(self):
        with mock.patch("sys.stderr"):
            self.assertEqual(play.main(["publish", "--track", "internal", "--notes", "x"]), 1)
            self.assertEqual(
                play.main(["publish", "--track", "internal", "--notes", "x", "--bundle", "a.aab", "--version-code", "8"]), 1,
            )


class FirstReleaseTest(VersionCodeToCommitTest):
    """Notes when Play has nothing to measure from: a track, and production, never released."""

    def notes(self, tracks, version_code=None, whats_new="• First."):
        out = tempfile.mkdtemp()
        args = SimpleNamespace(track="production", out=out, base_code=None, version_code=version_code)
        env = {"WHATS_NEW": whats_new, "GITHUB_OUTPUT": str(Path(out) / "o")}
        with mock.patch.dict(os.environ, env), mock.patch.object(play, "summary"), \
                mock.patch.object(play, "play_service"), mock.patch.object(play, "read_tracks", return_value=tracks):
            play.notes_command(args)
        return (Path(out) / "changes.md").read_text(), (Path(out) / "whats-new.txt").read_text()

    def test_the_first_release_anywhere_takes_the_typed_notes(self):
        changes, text = self.notes({})
        self.assertIn("first release on Play", changes)
        self.assertNotIn("commits since", changes)
        self.assertEqual(text, "• First.\n")

    def test_the_first_release_anywhere_needs_typed_notes(self):
        with self.assertRaises(play.ReleaseError):
            self.notes({}, whats_new="")

    def test_a_promotion_to_an_empty_production_lists_what_is_new_to_testers(self):
        tracks = {
            "alpha": play.TrackState("alpha", [{"status": "completed", "versionCodes": ["3"]}]),
            "internal": play.TrackState("internal", [{"status": "completed", "versionCodes": ["8"]}]),
        }
        changes, _ = self.notes(tracks, version_code=8)
        self.assertIn("The track's users have version 3", changes)
        self.assertIn("four", changes)
        self.assertNotIn("five", changes)


class AreasTest(unittest.TestCase):
    def test_test_and_ios_sources_are_not_the_android_app(self):
        self.assertEqual(play.area("android/app/src/main/kotlin/A.kt"), "android/app")
        self.assertEqual(play.area("android/executorch/src/main/kotlin/R.kt"), "android/executorch")
        self.assertEqual(play.area("shared/catalog/src/commonMain/kotlin/C.kt"), "shared/catalog")
        self.assertEqual(play.area("shared/catalog/src/jvmAndAndroidMain/kotlin/C.kt"), "shared/catalog")
        self.assertEqual(play.area("android/app/src/test/kotlin/T.kt"), "tests:android/app")
        self.assertEqual(play.area("shared/server/src/jvmTest/kotlin/T.kt"), "tests:shared/server")
        self.assertEqual(play.area("android/app/src/debug/AndroidManifest.xml"), "tests:android/app")
        self.assertEqual(play.area("shared/prompt/src/iosMain/kotlin/P.kt"), "ios:shared/prompt")
        self.assertEqual(play.area("jvm/devserver/src/main/kotlin/D.kt"), "jvm/devserver")
        self.assertEqual(play.area("gradle/libs.versions.toml"), "gradle")
        self.assertEqual(play.area("build-logic/convention/src/main/kotlin/BuildConfig.kt"), "build-logic")
        self.assertEqual(play.area("docs/results/x.md"), "docs/results")

    def test_only_a_commit_that_changed_the_android_bundle_ships(self):
        commit = lambda *areas: play.Commit("a", "s", "", list(areas))
        self.assertTrue(commit("android/app", "docs/results").ships)
        self.assertTrue(commit("android/executorch").ships)
        self.assertTrue(commit("shared/catalog").ships)
        # The version catalog moves the ExecuTorch runtime and every other dependency.
        self.assertTrue(commit("gradle").ships)
        # build-logic sets the minimum and target SDK and the packaged ABIs.
        self.assertTrue(commit("build-logic").ships)
        self.assertTrue(commit("gradle.properties").ships)
        for path in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/libs.versions.toml"):
            self.assertTrue(commit(play.area(path)).ships, path)
        self.assertFalse(commit("tests:android/app", "tools/release").ships)
        self.assertFalse(commit("ios:shared/prompt").ships)
        self.assertFalse(commit("jvm/devserver", "jvm/testing").ships)
        self.assertFalse(commit("docs", "README.md").ships)
        self.assertFalse(commit().ships)

    def test_the_app_identity_is_execuserve(self):
        self.assertEqual(play.PACKAGE, "org.experimentalmachines.execuserve")
        self.assertEqual(play.APP_GRADLE, "android/app/build.gradle.kts")
        self.assertIn("ExecuServe", play.SYSTEM)
        self.assertNotIn("OpenWeights", play.SYSTEM)


class TracksTest(unittest.TestCase):
    TRACKS = {
        "production": play.TrackState("production", [
            {"status": "completed", "versionCodes": ["615"]},
            {"status": "inProgress", "versionCodes": ["620"], "userFraction": 0.2},
        ]),
        "internal": play.TrackState("internal", [{"status": "completed", "versionCodes": ["630"]}]),
        "alpha": play.TrackState("alpha", [{"status": "draft", "versionCodes": ["633"]}]),
    }

    def test_the_notes_start_from_what_everyone_on_the_track_has(self):
        self.assertEqual(play.live_code(self.TRACKS, "production"), 615)
        self.assertEqual(play.live_code(self.TRACKS, "internal"), 630)

    def test_testers_have_production_when_it_is_newer(self):
        # The shape Play returned on 2026-09-29: internal testing left at 201 long ago.
        tracks = {
            "production": play.TrackState("production", [{"status": "completed", "versionCodes": ["615"]}]),
            "internal": play.TrackState("internal", [{"status": "completed", "versionCodes": ["201"]}]),
            "beta": play.TrackState("beta", [{"status": "draft"}]),
        }
        self.assertEqual(play.live_code(tracks, "internal"), 615)
        self.assertEqual(play.live_code(tracks, "beta"), 615)
        self.assertEqual(play.highest_code(tracks), 615)

    def test_a_track_with_nothing_live_is_measured_from_production(self):
        self.assertEqual(play.live_code(self.TRACKS, "alpha"), 615)
        self.assertEqual(play.live_code(self.TRACKS, "beta"), 615)

    def test_a_staged_rollout_counts_only_when_nothing_completed(self):
        tracks = {"production": play.TrackState("production", [{"status": "inProgress", "versionCodes": ["620"]}])}
        self.assertEqual(play.live_code(tracks, "production"), 620)
        self.assertIsNone(play.live_code({}, "production"))

    def test_an_empty_track_is_measured_from_what_testers_have(self):
        # ExecuServe's shape: nothing on production yet, a closed test on alpha.
        tracks = {
            "alpha": play.TrackState("alpha", [{"status": "completed", "versionCodes": ["20"]}]),
            "internal": play.TrackState("internal", [{"status": "completed", "versionCodes": ["31"]}]),
        }
        self.assertIsNone(play.live_code(tracks, "production"))
        self.assertEqual(play.newest_code_below(tracks, 40), 31)
        # Promoting internal's 31 to production measures from the older 20, not from itself.
        self.assertEqual(play.newest_code_below(tracks, 31), 20)
        self.assertIsNone(play.newest_code_below(tracks, 20))
        self.assertIsNone(play.newest_code_below({}, 40))
        # A draft is not something anyone has.
        drafts = {"alpha": play.TrackState("alpha", [{"status": "draft", "versionCodes": ["25"]}])}
        self.assertIsNone(play.newest_code_below(drafts, 40))

    def test_a_new_bundle_must_beat_every_code_drafts_included(self):
        self.assertEqual(play.highest_code(self.TRACKS), 633)
        self.assertEqual(play.highest_code({}), 0)


class ReleaseBodyTest(unittest.TestCase):
    def test_a_full_release_completes(self):
        body = play.release_body("production", 639, "0.1.0 (639)", "• Faster.", 100)
        release = body["releases"][0]
        self.assertEqual(body["track"], "production")
        self.assertEqual(release["status"], "completed")
        self.assertEqual(release["versionCodes"], ["639"])
        self.assertEqual(release["releaseNotes"], [{"language": "en-US", "text": "• Faster."}])
        self.assertNotIn("userFraction", release)

    def test_a_partial_release_is_a_staged_rollout(self):
        release = play.release_body("production", 639, "n", "t", 20)["releases"][0]
        self.assertEqual(release["status"], "inProgress")
        self.assertEqual(release["userFraction"], 0.2)

    def test_impossible_rollouts_are_refused(self):
        for track, rollout in (("internal", 50), ("production", 0), ("production", 101)):
            with self.assertRaises(play.ReleaseError):
                play.release_body(track, 639, "n", "t", rollout)


class NotesTest(unittest.TestCase):
    def test_dashes_become_the_house_style(self):
        self.assertEqual(play.clean_notes("• Faster replies \u2014 up to 3\u20135 times"), "• Faster replies, up to 3 to 5 times")
        self.assertEqual(play.clean_notes("  • One  \r\n•   Two \n"), "• One\n• Two")

    def test_play_limit_is_enforced(self):
        self.assertEqual(play.check_notes("x" * 500), "x" * 500)
        with self.assertRaises(play.ReleaseError):
            play.check_notes("x" * 501)
        with self.assertRaises(play.ReleaseError):
            play.check_notes("  \n ")

    def test_the_prompt_carries_each_commit_and_what_it_touched(self):
        commits = [play.Commit("a" * 40, "server: a fix", "Measured on the POCO." * 200, ["android/app", "shared/server"])]
        prompt = play.commits_prompt(commits, 615, 639)
        self.assertIn("Users have version 615. This release is version 639.", prompt)
        self.assertIn("## server: a fix\nareas: android/app, shared/server", prompt)
        self.assertTrue(prompt.rstrip().endswith("[...]"))


if __name__ == "__main__":
    unittest.main()
