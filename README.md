# INDEX

Every class has the same problem. Study material lives in a WhatsApp group,
and WhatsApp is a river — everything flows away. A PDF sent last month takes
half an hour of scrolling to find again, if it is found at all.

INDEX is the index of the class. Like the index at the back of a book, it
exists so you can find anything in seconds.

Material lives in subject folders — Mathematics, Physics, Mechanical, and
whatever subjects your class actually has. Anyone in the class can upload a
PDF, photo or document into the right folder, with a title and a short note.
It stays there, organised, and one search bar finds it across every subject:
by title, by tag, by the note, by who uploaded it.

## What is inside

- **Subject folders** — one folder per subject, created once by the class
  owner. Files inside show the uploader's name, the date, the size, and the
  uploader's own note (like "Math homework — Unit 3"). There are no comments
  on files, on purpose: folders stay a clean library, talking happens in the
  group chat.
- **Share straight from WhatsApp** — see a document in WhatsApp, Files or
  Gallery? Tap Share → INDEX. The file lands in the upload screen already
  attached, with its name filled in as the title. No saving it to the phone
  first and hunting for it afterwards.
- **Deadlines on Home** — admins create deadline cards ("Mechanical
  assignment — due Friday"), and Home shows the days left, worked out fresh
  every time you open the app.
- **Group chat + Notices** — one class chat, where every upload also posts
  itself ("Aman added Laplace Notes to Mathematics" — tap it to open the
  file). Beside it, a Notices section only admins can post in, so an
  important notice never gets buried under chat again. Chat messages can be
  deleted for everyone, WhatsApp-style: the message becomes
  "This message was deleted" for the whole class.
- **Request board** — missing something? Post a request. Whoever has the
  material uploads it against your request, and the request closes.
- **PDF maker** — photos of your assignment pages in, one clean PDF out.
  No ads, no watermark. The PDF can go straight into a subject folder.
- **Roles** — the Owner runs the class. Admins (picked by the Owner) can add
  subjects, post notices and create deadlines. Only the Owner can delete a
  subject. Members upload, chat and request.
- **Trash** — nothing is deleted instantly. Deleted files and subjects sit
  in Trash for 30 days and can be restored.
- **Dark mode** — one moon button, on Home beside your profile picture.
  Same app, dark colours.
- **Updates** — Profile has a Check for Updates button. New builds install
  over the old one and everything stays, because the app is always signed
  with the same key and the class data is not stored inside the APK.

## How it runs today

This build runs in **local mode**: the whole app works on one phone, with
its data in a small database on the phone itself. It starts with a short
setup (your name, your class name, your subjects) and some starter content
so every screen can be tried properly. In Profile there is a **demo role
preview** switcher — flip between Owner, Admin and Member and watch which
buttons appear and disappear across the app. That is exactly how the real
roles will behave.

The shared version — every classmate's phone seeing the same folders and
chat — plugs into Firebase behind the same screens. All data access already
goes through one interface (`IndexRepository`), so the cloud version is a
swap underneath, not a rebuild. The step-by-step lives in
[FIREBASE_SETUP.md](FIREBASE_SETUP.md), including why the Firebase project
must be created under the class owner's own Google account.

## Building the APK

Push this project to GitHub and the **Build APK** workflow
(`.github/workflows/build-apk.yml`) builds a debug APK with JDK 17 and
Gradle 8.7 — download it from the Actions run.

Release builds are minified and resource-shrunk to keep the APK small, and
are signed with the owner's fixed release key when its location and
passwords are provided through the `INDEX_STORE_FILE`,
`INDEX_STORE_PASSWORD`, `INDEX_KEY_ALIAS` and `INDEX_KEY_PASSWORD`
environment variables (or the matching Gradle properties). The key itself is
never committed to this repo.

## A note on size and speed

INDEX is built to be used on whatever phone a student already has. Lists
load straight from the database, file copies and PDF building run in the
background so the screen never freezes, the PDF maker processes photos one
at a time at a sensible size instead of loading full camera photos into
memory, and PDF preview renders a single page at a time for the same
reason.

## Tech, in plain words

Kotlin + regular Android screens (XML layouts, no heavyweight UI kit), a
Room database (SQLite, built into Android) for local mode, and Android's own
PdfDocument / PdfRenderer for making and previewing PDFs. Firebase (Auth
with Google sign-in, Firestore, Storage) is the planned shared backend —
see FIREBASE_SETUP.md.
