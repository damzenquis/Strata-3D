# Contributing to Strata:3D

Thanks for helping! This guide explains how to set up the project and what a good change looks like.

## Ground rules

1. **Never regress.** A change must not break or degrade anything that already works: formats, gestures,
   measurement, theming, or performance. If a trade-off is unavoidable, call it out in the pull request.
2. **Stay lightweight and fast.** Prefer the platform and small focused code over new dependencies. Any new
   library needs a clear reason, a licence compatible with the AGPL-3.0-or-later (for example MIT, BSD, Apache-2.0,
   MPL-2.0, LGPL-3.0, GPL-3.0 or AGPL-3.0), and a note in `NOTICE`. Contributions are accepted under the
   AGPL-3.0-or-later.
   Watch the APK size and parse/render times.
3. **Test it.** New parsing or geometry code comes with unit tests. New formats or format features also get cases
   in `tools/generate_test_models.py`, with expected values computed independently of the app.
4. **Document it.** Every user-visible change gets a line under `[Unreleased]` in `CHANGELOG.md`, and docs are
   updated where behaviour changes.
5. **Discuss UI changes first.** Open an issue with a sketch or screenshot before changing the interface.

## Setup

- JDK 17+ and the Android SDK (platform `android-37.2`, build-tools 36+). Android Studio works, but is not required.
- Create `local.properties` with `sdk.dir=/path/to/Android/sdk` (Android Studio does this for you).
- Optional, for the full test-model suite: Python 3 with `numpy` and `pillow`, and Node.js (see [docs/TESTING.md](docs/TESTING.md)).

```bash
./gradlew assembleDebug testDebugUnitTest -Pstrata.withoutReconTools=true
```

For on-device reconstruction, build its native tools first; see [BUILDING.md](BUILDING.md). Without them, add
`-Pstrata.withoutReconTools=true` as above (everything except Build model works).

## Code style

- Kotlin official style (`kotlin.code.style=official`); 4-space indent; trailing commas in multi-line lists.
- `app.strata3d.model` stays pure JVM: no `android.*` imports.
- Parsers stream and avoid per-element allocations; check bounds and throw a `ModelParseException` subclass with
  a message a user can act on. Never let an `IndexOutOfBoundsException` reach the UI.
- User-facing text lives in `res/values/strings.xml`.
- Comments explain *why*, not *what*.

## Commits and pull requests

- Use [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `perf:`, `refactor:`,
  `test:`, `docs:`, `build:`, `ci:`, `chore:`. Example: `fix(ply): read 16-bit little-endian values correctly`.
- Keep each pull request focused on one thing. Fill in the template checklist.
- Run `./gradlew testDebugUnitTest` (with the test models generated) before opening the pull request.

## Reporting bugs

Use the bug report template. A sample file that reproduces the problem helps the most. Please only share files
you have the right to share.
