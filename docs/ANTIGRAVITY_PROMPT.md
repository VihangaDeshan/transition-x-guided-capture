# Prompt for Google Antigravity

Open the cloned repository folder in Antigravity, start an Agent session (Planning mode), and paste
the block below. Replace the last section with what you want done.

---

You are working on `transition-x-guided-capture`, an Android guided-capture app for Rysera
Innovations (Transition X program). It follows "Guided Capture Technical Design Document v1.0".

Stack (pinned, do not upgrade): Expo SDK 54, React Native 0.81.5 (New Architecture), TypeScript,
and a local Expo module `modules/guided-capture` written in Kotlin that uses ARCore 1.50.0.

Read `AGENTS.md`, `README.md` and `docs/CONTRACTS.md` first and obey every rule in `AGENTS.md`.
Key rules: ARCore + per-frame analysis + decision engine stay in Kotlin; the pure packages
(core/signals/guidance/replay/export) must not import android/ARCore/Expo; the engine takes time
as a parameter; UI text only in `src/i18n/strings.ts` (English and Sinhala); thresholds only in
`config/thresholds.v1.json`; never commit `android/` or `ios/`; no new navigation libraries;
permissive licences only.

I do not have Android Studio. I test on a Samsung Galaxy A55 5G by installing the APK that GitHub
Actions publishes under Releases after each push to `main`.

How to work:
1. Make a short plan and list the files you will touch.
2. Make the change with the smallest diff that solves it. Add or update tests:
   Jest tests in `__tests__/` for TypeScript, JUnit tests in
   `modules/guided-capture/android/src/test/java/...` for the pure Kotlin packages.
3. Run `npm run check`. If a JDK 17 is installed, also run `gradle -p tools/engine-tests test`.
   Fix everything until both pass. Show me the output.
4. Commit with a clear conventional message (feat:, fix:, test:, docs:), one logical change per
   commit, then `git push`.
5. Tell me to open GitHub → Actions and wait for the green build, then install the new APK from
   Releases. If the Android job is red, fetch the log (`gh run view --log-failed`), fix and push again.

Task:
<describe the bug you saw on the phone, with the Debug overlay values if relevant,
 or the feature you want — e.g. "G2 'Hold steady' appears too often while standing still;
 the debug overlay shows jit 7–9. Raise jitterEnter and jitterExit and bump the thresholds version.">
