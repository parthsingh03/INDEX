package com.parth.index.firebase

/** Every cloud-mode constant in ONE obvious place.
 *
 *  INDEX is a single-class app: there is exactly one class document in
 *  Firestore, [CLASS_ID], and exactly one person who can bootstrap it —
 *  the Google account matching [OWNER_EMAIL]. Whoever signs in with that
 *  email first creates the class and becomes its OWNER; everyone else
 *  asks to join and waits for an approve tap.
 *
 *  File bytes live on Cloudinary (Firebase Storage needs a billing plan).
 *  Uploads use the UNSIGNED preset [CLOUDINARY_PRESET] — no API key and no
 *  secret exist anywhere in this app, by design. */
object CloudConfig {
    /** Firestore document id of the one class: classes/index-class. */
    const val CLASS_ID = "index-class"

    /** The class owner's Google account. Bootstrap checks this email. */
    const val OWNER_EMAIL = "psingh.up85@gmail.com"

    /** Cloudinary cloud name (the account that holds the file bytes). */
    const val CLOUDINARY_CLOUD_NAME = "bbbaxlfa"

    /** Unsigned upload preset created in the Cloudinary console. */
    const val CLOUDINARY_UPLOAD_PRESET = "index_upload"

    /** Upload endpoint; "auto" lets Cloudinary accept PDFs, images and
     *  other documents through the one preset. NOTE: Cloudinary blocks
     *  DELIVERY of PDF/ZIP files (HTTP 401) until the account's
     *  Settings → Security → "Allow PDF and ZIP files delivery" switch is
     *  on — that applies to every resource type, raw included (proven
     *  2026-10-15). The switch is the fix; there is no code-side route. */
    const val CLOUDINARY_UPLOAD_URL =
        "https://api.cloudinary.com/v1_1/$CLOUDINARY_CLOUD_NAME/auto/upload"

    /** Member document status values (members/{uid}.status). */
    const val STATUS_APPROVED = "APPROVED"
    const val STATUS_PENDING = "PENDING"
}
