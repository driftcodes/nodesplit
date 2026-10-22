# NodeSplit

Android app for multi-seller VPN bookkeeping: bandwidth allocation, a three-way infrastructure/profit split, a Toman sales ledger and graphical PDF statements. Built with Kotlin, Jetpack Compose and Firebase (Auth + Firestore).

## How it works

- **Roles.** One Admin (the workspace owner) and any number of Sellers. Each seller sees only their own book. The Admin has a private book and can open any seller's sent reports.
- **Approval.** The Admin approves a seller's Google email in *Settings*. A seller who signs in with that exact, verified Google email is approved automatically; anyone else waits on the *Pending* screen. *Revoke* blocks a seller on the server the next time their phone talks to Firestore.
- **Money.** For a set of sales: infrastructure cost = total allocated GB × cost per GB (rounded half-up to whole Toman); net profit = gross − infrastructure; admin profit = net × admin % (truncated toward zero); seller profit = net − admin profit; amount due to Admin = infrastructure + admin profit. So gross = infrastructure + seller profit + admin profit, always. The *Paid* checkbox records whether the customer paid the seller and never changes any of these numbers. A discount lowers gross and so lowers both profits; a sale below cost produces a loss that the split shares, and the app warns about it.
- **GB.** `dataGb` on a sale is the **total** allocated (plan GB + new-user bonus). `bonusGb` is the part of it that is a bonus. The bonus is never added a second time.
- **Rates.** Active (unsent) sales always use the current cost per GB and split from Settings. Every sent report and every archive saves the rates it was made with and keeps using them. A saved rate of 0 is kept as 0. Very old archives that never saved rates are labelled as using today's rates.
- **Reports.** *Send report* saves a new, permanent copy of the seller's current list (`ledgers/{uid}/submissions/{id}`). Earlier reports are never overwritten and can't be edited or deleted. The Admin sees the latest one, can open older ones under *Archives*, and can mark a report *Settled*. Starting a new cycle clears the "current report" so the Admin no longer sees last cycle's report as the seller's active book.
- **Archives.** *Start new cycle* can keep the closed cycle as an archive. Archives can't be edited or deleted; one can be *voided* with a reason, which keeps it, marked, and read-only. The only change allowed on an archive or report is the per-sale payment checkbox.
- **Offline.** Firestore's on-device cache keeps working offline once a device has signed in and loaded its data. Changes are saved on the phone and upload when the connection returns; the app shows when something is waiting to sync. The first sign-in on a device needs internet. A report sent offline shows as *Queued* until the server confirms it. Changes that never uploaded are lost if the app is uninstalled or its data cleared, and the app warns before signing out with pending changes.
- **PDF.** Exports the active cycle (all sales, even when a search is active) or any archive/report as a graphical PDF with the financial summary on top. *Save PDF* opens the system save dialog; *Open* and *Share* are separate. PDFs contain no email addresses and no paid/unpaid column.

## First-time setup

Step-by-step instructions (Firebase console, project files, Android Studio, signing, release) are in [`docs/SETUP_GUIDE.md`](docs/SETUP_GUIDE.md). Short version:

1. Create a Firebase project with **Google** sign-in and Firestore enabled.
2. Put your `google-services.json` in `app/` (see `app/google-services.json.example`), and register the SHA-1/SHA-256 fingerprints of the key you sign with.
3. Copy `app/firebase_applet_config.xml.example` to `app/src/main/res/values/firebase_applet_config.xml` and set your Firestore database ID.
4. Deploy the rules: `firebase deploy --only firestore:rules`. If you use a named database, set `"database"` in `firebase.json` to that ID first.
5. **Create the workspace by hand.** The app can no longer create it (otherwise the first stranger to sign in would become Admin). Sign in once with the owner's Google account; the app shows *Workspace not set up yet* with your user ID. In the Firebase console create the document `config/workspace` with:

   | field | type | value |
   | --- | --- | --- |
   | `adminUid` | string | the user ID shown in the app |
   | `adminEmail` | string | leave empty |
   | `businessName` | string | e.g. `NodeSplit VPN` |
   | `costPerGbToman` | number | e.g. `1000` |
   | `adminProfitPercent` | number | e.g. `50` |
   | `settingsRevision` | number | `1` |
   | `createdAt`, `updatedAt` | timestamp | now |

   Tap *Try again*. Workspaces that already exist need nothing.

6. For release builds, set `KEYSTORE_PATH`, `STORE_PASSWORD` and `KEY_PASSWORD`. Debug builds use `debug.keystore` in the project root if it is there (AI Studio builds), otherwise your normal Android debug key. Never commit keystores.

## Upgrading an existing installation

Deploy in this order:

1. **Deploy `firestore.rules` and install this app version on every phone at the same time.** The two depend on each other:
   - new rules + old app: adding and editing sales still works, but the old *Send to Admin* (which overwrote one document) and archive delete/edit are refused until the phone is updated;
   - new app + old rules: *Send report*, voiding and new invitations are refused.

   Recommended: deploy the rules, then immediately send the new APK to sellers. Nothing is lost in between; refused actions can simply be repeated after updating.
2. Nothing in existing data is rewritten:
   - The old `archives/submitted_current` report stays readable and is shown as an *Older report* (read-only) until the seller sends a new one.
   - Existing archives keep their items, totals and rates. Their stored totals are now re-checked against their sales, and a mismatch is flagged.
   - Invitations stored under the old collision-prone keys (`a_at_b_dot_c`) keep working. The first time the Admin opens the app, they are moved to the new key (the email address itself), using only the email stored in each document.
   - Existing user profiles keep their old `emailKey`; it is updated when relevant.
3. Before deploying to production, export the database (`gcloud firestore export`) so you can recover data. Rolling back the app binary does not need a data rollback; rolling back the rules would reopen the holes fixed here.

## Tests

- **Firestore rules** (`firestore.test.js`, 9 tests): run against the emulator.

  ```
  npm i -D firebase-tools @firebase/rules-unit-testing firebase
  npx firebase emulators:exec --project demo-no-project --only firestore "node --test firestore.test.js"
  ```

- **All JVM tests** (money logic, Robolectric, repository against the emulators), 19 tests:

  ```
  npx firebase emulators:exec --project demo-no-project --only auth,firestore "./gradlew testDebugUnitTest"
  ```

  Without the emulators, run only the pure tests: `./gradlew testDebugUnitTest --tests "com.example.data.model.FinancialRulesTest"`.
- **On a device** (real PDF output): `./gradlew connectedDebugAndroidTest` with a phone connected.

See `security_spec.md` for the security model and `CHANGES.md` for what changed and what is still open.
