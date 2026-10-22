# NodeSplit

Android app for multi-seller VPN bookkeeping: bandwidth allocation, 3-way infrastructure/profit split, a Toman sales ledger and graphical PDF statement export. Built with Kotlin, Jetpack Compose and Firebase (Auth + Firestore).

## Setup

1. Create a Firebase project with Google sign-in and Firestore enabled.
2. Download your `google-services.json` into `app/` (see `app/google-services.json.example`).
3. Copy `app/src/main/res/values/firebase_applet_config.xml.example` to `firebase_applet_config.xml` and set your Firestore database ID.
4. Deploy `firestore.rules` and open the project in Android Studio.
5. For release builds, set the `KEYSTORE_PATH`, `STORE_PASSWORD` and `KEY_PASSWORD` environment variables.

## Tests

Firestore rules tests live in `firestore.test.js` (run against the Firebase emulator, see `firebase.json`).

See `security_spec.md` for the security model.
