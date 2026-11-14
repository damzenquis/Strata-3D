# Releasing

## Versioning

Strata:3D follows [Semantic Versioning](https://semver.org/): `MAJOR.MINOR.PATCH`. `versionCode` increases by
one for every release that reaches users.

## Checklist

1. All work for the release is merged to `main`, and `./gradlew testDebugUnitTest` passes with the generated test
   models present (see [TESTING.md](TESTING.md)).
2. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
3. In `CHANGELOG.md`, move the `[Unreleased]` entries into a new dated section and update the compare links.
4. Write the store summary in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (500 characters or fewer).
5. Build and verify:
   ```bash
   ./gradlew clean testDebugUnitTest assembleRelease
   apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
   ```
   The certificate SHA-256 must be `d3be4f5085b90752ad0a190005b92f48b65544679a870ac5b333c4cabecea07a`.
6. Smoke-test the release APK on a device (see the checklist in [TESTING.md](TESTING.md)).
7. Commit: `Strata:3D vX.Y.Z`. Tag: `git tag -a vX.Y.Z -m "Strata:3D vX.Y.Z"`. Push the branch and the tag.
8. Publish the release on GitHub with the APK attached, named `Strata-3D-vX.Y.Z.apk`, and its SHA-256 in the notes:
   ```bash
   cp app/build/outputs/apk/release/app-release.apk Strata-3D-vX.Y.Z.apk
   sha256sum Strata-3D-vX.Y.Z.apk
   gh release create vX.Y.Z Strata-3D-vX.Y.Z.apk --title "Strata:3D vX.Y.Z" --notes-file release-notes.md
   ```

Releases are built on a computer that has the reconstruction tools (see [BUILDING.md](../BUILDING.md)); the CI
workflow only tests and builds without them, so it never publishes anything. The README's download link points to
the latest release, so it never needs updating.

## Signing key

Release builds read `keystore.properties` from the project root:

```properties
storeFile=keystore/strata3d-release.jks
storePassword=...
keyAlias=strata3d
keyPassword=...
```

Neither file is ever committed (both are listed in `.gitignore`). Without them, local release builds fall back to
the debug key, so the project still builds for contributors.

> **Keep two offline backups of `keystore/strata3d-release.jks` and `keystore.properties`.** If the key is lost,
> installed copies of Strata:3D can never be updated: Android refuses an update signed with a different key.

