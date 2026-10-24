# Security and privacy

A capture is a video of the inside of someone's home plus a metric record of where the camera
went. The app is designed so that this data only leaves the phone when the user sends it.

## Controls in the app
| Area | Control |
|---|---|
| Permissions | Only CAMERA, VIBRATE (and INTERNET, required by the React Native runtime). READ/WRITE_EXTERNAL_STORAGE and SYSTEM_ALERT_WINDOW pulled in by Expo defaults are removed (`app.json` → `blockedPermissions`). |
| Storage | App-specific storage (`Android/data/lk.transitionx.guidedcapture`), not shared media. |
| Backup | `allowBackup=false`: captures are never copied by Android Auto Backup. |
| Sharing | FileProvider exposes **only** `exports/` (finished zips), never raw session folders; `exported=false`; read grant per share intent only. |
| Network | No network code and no analytics/third-party SDKs. Transfer is manual (share sheet) until Rysera provides an endpoint (TDD §16). |
| Deletion | "Record again" deletes the capture; "Delete from this phone" after sharing removes video and logs. |
| Untrusted input | Session IDs from JavaScript must be canonical UUIDs and resolve inside `sessions/` (path-traversal check). Threshold files are clamped to sane ranges and the version string is sanitised. Zip entry names are restricted to plain file names. |
| Integrity | Every accepted package contains `checksums.sha256` (SHA-256 of every file); verify with `sha256sum -c checksums.sha256`. Zips are written to a temporary file and renamed, so a partial zip is never shared. |

## Build and supply chain
- No secrets in the repository. Release signing reads `GC_RELEASE_*` environment variables (see below).
- CI runs with a read-only token; only the release job may write.
- Each APK is published with a `.sha256` file.
- Licence audit in CI (`npm run licenses`): permissive licences only.
- Dependabot opens weekly update PRs for npm and GitHub Actions (Expo/RN pinned to the SDK).

### Release signing
Until a release key is configured, CI signs with the Android debug key, which is fine for testing
but not for distribution. To use a real key, add these repository secrets:
`GC_RELEASE_KEYSTORE_BASE64` (base64 of the `.jks`), `GC_RELEASE_STORE_PASSWORD`,
`GC_RELEASE_KEY_ALIAS`, `GC_RELEASE_KEY_PASSWORD`. Create the key once with
`keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias guidedcapture`
and keep it outside the repo. Phones with a debug-signed build must uninstall it once before
installing the first release-signed build.

## Reporting
Open a private security advisory on GitHub (Security → Advisories) rather than a public issue.
