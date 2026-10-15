# Wiring notes — cloud mode (for the owner)

The app code is fully wired to Firebase + Cloudinary. What remains is
accounts, secrets and console work that only the project owner can do.
Nothing in this file contains a secret value — names and purposes only.

## 1. GitHub Secrets the owner must create

Repo → Settings → Secrets and variables → Actions → New repository secret.
The build and the release flow read exactly these:

| Secret name | What it holds |
|---|---|
| `GOOGLE_SERVICES_JSON_BASE64` | The whole `app/google-services.json` file, base64-encoded. The file is gitignored and must never be committed, so CI receives it this way. |
| `INDEX_KEYSTORE_BASE64` | The ONE fixed release keystore file, base64-encoded. Every release for the life of the app is signed with this key — lose it and updates become impossible, so keep an offline backup in a second place. |
| `INDEX_STORE_PASSWORD` | The keystore's store password (matches `INDEX_STORE_PASSWORD` in `app/build.gradle.kts`). |
| `INDEX_KEY_ALIAS` | The key alias inside that keystore. |
| `INDEX_KEY_PASSWORD` | That key's password. |

## 2. What CI (the workflow) must do — requirements, not the file itself

- **Materialise `google-services.json` before building.** Decode the
  `GOOGLE_SERVICES_JSON_BASE64` secret to `app/google-services.json`.
  Without it the google-services Gradle plugin fails the build outright.
- **Materialise the fixed keystore before building.** Decode
  `INDEX_KEYSTORE_BASE64` to a file, then expose it to Gradle as the env
  vars `app/build.gradle.kts` already reads: `INDEX_STORE_FILE` (path to
  the decoded file), `INDEX_STORE_PASSWORD`, `INDEX_KEY_ALIAS`,
  `INDEX_KEY_PASSWORD`.
- **Build the release variant** (`gradle assembleRelease`) in addition to
  the debug build. With the keystore env present, the signed APK lands at
  **`app/build/outputs/apk/release/app-release.apk`**. If the output is
  named `app-release-unsigned.apk` instead, the keystore secrets were
  missing — an unsigned APK must never be published as a release.
- **Publish the release APK as a GitHub Release asset named exactly
  `app-release.apk`**, on a tag matching the version (e.g. `v1.1` for
  versionName 1.1 / versionCode 2). This name is not cosmetic: the in-app
  updater in Profile downloads
  `https://github.com/parthsingh03/INDEX/releases/latest/download/app-release.apk`,
  so the asset name and the "latest" release decide what the whole class
  updates to. The first fixed-key release is **v1.1 (versionCode 2)** —
  the code was bumped above the local build's 1 on purpose.
- **Keep `latest-version.txt` current.** It sits at the repo root, holds
  just the latest versionCode (`2` today), and the Profile update check
  reads it from
  `https://raw.githubusercontent.com/parthsingh03/INDEX/main/latest-version.txt`.
  Bump it in the same commit that bumps versionCode/versionName.
- The existing debug artifact upload (`INDEX-debug-apk`) can stay as is.

## 3. Firebase console steps still owed

Project: the one in `app/google-services.json` (package `com.parth.index`).

1. **Authentication → Sign-in method → Google → Enable** (with a support
   email). Without this, sign-in has no provider to talk to.
2. **Register SHA-1 fingerprints** — Project settings → Your apps → the
   Android app → Add fingerprint. Register the **fixed release key's**
   SHA-1 (the key in `INDEX_KEYSTORE_BASE64`); Google sign-in refuses any
   APK whose signing certificate is not registered. If CI debug artifacts
   are used for sign-in testing, the CI debug key's SHA-1 needs adding
   too — but the release key is the one the class will run.
3. **Re-download `google-services.json`** after the steps above and
   replace `app/google-services.json`. The copy placed in the project
   during setup contains **no OAuth clients at all** — until the web
   client (client_type 3) exists in that file, the generated
   `default_web_client_id` resource is missing and the sign-in screen
   honestly reports "not configured". (The code looks the id up by name
   precisely so this state degrades gracefully instead of breaking the
   build.) The refreshed file also refreshes the CI secret in §1.
4. **Firestore Database** must exist (production mode).
5. **Deploy the security rules BEFORE inviting anyone**: paste
   `firestore.rules` (project root) into Firestore Database → Rules →
   Publish, or run `firebase deploy --only firestore:rules`. Until then
   the database is either wide open or fully closed depending on the
   starter rules — neither is shippable.
6. **Cloudinary**: the unsigned upload preset **`index_upload`** must
   exist in the `bbbaxlfa` cloud and stay set to *Unsigned*. No API key
   or secret is used by the app, ever — do not "fix" that.

## 4. Design decisions baked into the code

- **Entity id scheme.** An entity's `Long` id IS its Firestore document
  id, rendered as a decimal string (`doc "1728661234567890123"` ↔
  `id 1728661234567890123L`). Ids are minted by `FirebaseRepository` at
  insert time as `currentTimeMillis * 1024 + random(0..1023)`. Foreign
  keys (file→subject, request→fulfilled file, chat→uploaded file) are
  stored as plain numbers, exactly as Room stored them, so no screen
  needed changes. No `remoteId` field was added: with the doc id being
  the number itself, a second id would only be a place to disagree.
- **File bytes.** `addFile` uploads the local copy to Cloudinary first
  (unsigned preset, plain HTTPS multipart — no SDK), then writes the
  Firestore record with the returned `secure_url` stored in the entity's
  `localPath` field (the same field local mode used for the disk path).
  Previews/downloads resolve the URL through `RemoteFiles`, which
  downloads once into `cacheDir/downloads/` and reuses the copy; the
  Profile "Clear cache" button wipes those too.
- **Cloudinary delete limitation.** "Delete forever" (emptying Trash)
  deletes only the Firestore record. An unsigned preset cannot delete
  Cloudinary objects, and no API secret may live in the app, so the bytes
  stay on Cloudinary, **unlinked and unreachable from the app** — nothing
  in the class can find or open them. If storage ever needs a true purge,
  it is a manual pass in the Cloudinary console.
- **Per-user state on shared documents.** A notice's `seenByMe` /
  `seenByCount` and a chat message's `hiddenForMe` are per-person, so the
  documents carry uid arrays (`seenByUids`, `hiddenForUids`) and the
  repository translates them for whoever is signed in. The rules let a
  member touch ONLY their own uid in those arrays.
- **Owner bootstrap.** The first sign-in whose Google email equals
  `OWNER_EMAIL` in `firebase/CloudConfig.kt` creates the class document,
  the APPROVED OWNER member record, and the default subjects — no sample
  files, no fake classmates; a real class starts empty and real.
- **APK size.** Auth + Firestore come via the Firebase BoM; there is no
  Firebase Storage SDK, no Cloudinary SDK and no coroutines-play-services
  (a two-dozen-line `awaitTask` helper covers Task awaiting). Expect the
  APK to grow over the local build's ~5 MB, but by megabytes, not tens.
