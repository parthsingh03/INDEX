# Wiring INDEX to Firebase — the owner's checklist

INDEX ships in **local mode**: everything works on one phone, stored in its
own database. That is deliberate. The shared class version needs a Firebase
project, and that project must belong to the class owner — created under the
owner's own Google account, with the owner's own login. Nobody else should
create it, because whoever owns the Firebase project owns the class's data.

Do these steps when you are ready to go from "works on my phone" to "works
for the whole class". No app code changes are needed until step 5.

## 1. Create the project (about 10 minutes, free)

1. Go to https://console.firebase.google.com and sign in with the Google
   account that should own the class data.
2. "Add project" → name it `index-class` (any name is fine) → continue.
   Google Analytics is optional; skipping it keeps things simple.
3. Inside the project: **Build → Authentication → Get started → Google**
   → Enable. This is the sign-in: students will log in with Google, so
   there are no new passwords to forget, and account recovery is Google's
   problem, not yours.
4. **Build → Firestore Database → Create database** (production mode).
5. **Build → Storage → Get started** (default rules for now; step 4 below
   replaces them).

## 2. Connect this app

1. In the Firebase project: ⚙ **Project settings → Your apps → Android**
   → register the app with package name **`com.parth.index`** (it must
   match exactly).
2. Download the `google-services.json` it gives you and put it at
   **`app/google-services.json`** in this project. It is already in
   `.gitignore` — never commit it from a shared machine.
3. Add the SHA-1 fingerprint of the release key (and the debug key) in the
   same settings page, or Google sign-in will refuse to work.

## 3. Turn on the plugin + libraries (build.gradle.kts)

In the **root** `build.gradle.kts` add (versions known to work with the
AGP/Kotlin versions this project already uses):

```kotlin
id("com.google.gms.google-services") version "4.4.2" apply false
id("com.google.firebase.firebase-bom") version "33.3.0" apply false
```

In **`app/build.gradle.kts`**: apply `id("com.google.gms.google-services")`
and add:

```kotlin
implementation(platform("com.google.firebase:firebase-bom:33.3.0"))
implementation("com.google.firebase:firebase-auth-ktx")
implementation("com.google.firebase:firebase-firestore-ktx")
implementation("com.google.firebase:firebase-storage-ktx")
implementation("com.google.android.gms:play-services-auth:21.2.0")
```

## 4. Security rules (do this before inviting anyone)

Data layout (one class per document tree):

```
classes/{classId}                      <- { name, ownerUid }
classes/{classId}/members/{uid}        <- { name, role: OWNER|ADMIN|MEMBER }
classes/{classId}/subjects/{id}
classes/{classId}/files/{id}
classes/{classId}/deadlines/{id}
classes/{classId}/chat/{id}
classes/{classId}/notices/{id}
classes/{classId}/requests/{id}
```

Rules that must hold (write them in Firestore Rules / Storage Rules):

- Only signed-in users who are **members of that class** can read anything.
- Only members can create files, chat messages and requests.
- **Deleting a subject is owner-only.** Admins can add subjects, never
  delete one. (This is a product rule, not a suggestion.)
- Notices can be created only by OWNER/ADMIN members.
- Deletes inside the app are soft (a `deleted` flag + Trash for 30 days),
  so rules only need hard delete for the permanent-empty-Trash action,
  restricted to the file's uploader or the owner.

## 5. Switch the app over

`FirebaseRepository` (app/src/main/java/com/parth/index/firebase/) already
implements the same interface as the local repository. Fill in its methods
with Firestore/Storage calls (Firestore document ids can ride in the entity
`id` field as a hash, or extend the entities with a `remoteId` — decide
once, at wiring time), then in `IndexApp.onCreate()` change one line:

```kotlin
repository = FirebaseRepository()
```

The demo "role preview" switcher in Profile is then deleted: role comes
from the signed-in member record. The first-run setup screen becomes
"create or join a class": creating makes the signed-in user the OWNER;
joining uses an invite code the owner shares, and the owner/admins approve
joins (a `joinRequests` collection under the class).

## 6. Push notifications (optional, same project)

Firebase Cloud Messaging is already part of the same project. Wire it when
the class asks for pings on: new material in a subject, a new notice, and
"your request was fulfilled". Keep them mute-able per subject from day one.
