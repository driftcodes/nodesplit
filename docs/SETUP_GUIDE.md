# NodeSplit setup guide: Firebase, project files, and building the APK

This guide walks through everything from an empty computer to an APK you can send to sellers. Do the parts in order. If your Firebase project already works with the AI Studio build, most Firebase steps are just "check that this is already set"; the steps that are **new in this version** are marked **NEW**.

- [Part 1: Install the tools](#part-1-install-the-tools)
- [Part 2: Get the code](#part-2-get-the-code)
- [Part 3: Firebase console](#part-3-firebase-console)
- [Part 4: Put the Firebase info into the project](#part-4-put-the-firebase-info-into-the-project)
- [Part 5: Open, test and build in Android Studio](#part-5-open-test-and-build-in-android-studio)
- [Part 6: Signing keys and SHA fingerprints](#part-6-signing-keys-and-sha-fingerprints)
- [Part 7: Release to your sellers](#part-7-release-to-your-sellers)
- [Part 8: Check that everything works](#part-8-check-that-everything-works)
- [Troubleshooting](#troubleshooting)

---

## Part 1: Install the tools

1. **Android Studio** (latest stable) from <https://developer.android.com/studio>. Install with the default options. It brings its own Java (JDK) and installs the Android SDK on first start.
   The project uses Android Gradle Plugin 9.1 and compiles against Android 16 (API 36.1), so an older Android Studio won't open it. If sync later says the plugin is too new, update Android Studio (*Help → Check for Updates*).
2. **Git** (only if you want to clone from Android Studio; downloading a ZIP works too): <https://git-scm.com/downloads>.
3. **Node.js 20 or newer** (only for testing or deploying the Firestore rules from the command line): <https://nodejs.org>.

---

## Part 2: Get the code

The fixes are on the GitHub branch `claude/affectionate-shannon-aqdzqx`.

**Option A: clone in Android Studio**
1. Start Android Studio → *Clone Repository* (or *File → New → Project from Version Control*).
2. URL: `https://github.com/driftcodes/nodesplit.git`, choose a folder, *Clone*. Sign in to GitHub if asked.
3. Switch to the branch: menu *Git → Branches…* → *Remote branches* → `origin/claude/affectionate-shannon-aqdzqx` → *Checkout*.

**Option B: ZIP**
On GitHub, open the repository, switch the branch selector to `claude/affectionate-shannon-aqdzqx`, then *Code → Download ZIP*. Unzip it somewhere without spaces or non-English characters in the path (for example `C:\dev\nodesplit`).

When you're happy with it, merge the branch into `main` on GitHub (open a pull request and merge it).

---

## Part 3: Firebase console

Open <https://console.firebase.google.com> and sign in with the Google account that owns the project.

### 3.1 Pick (or create) the project
- If AI Studio already created a project for NodeSplit, click it. **Use that one**, because your existing sales are in it.
- Only for a brand-new setup: *Add project*, give it a name, Google Analytics can be off, *Create project*.

### 3.2 The Android app inside the project
1. Click the **gear icon ⚙ → Project settings → General** tab. Scroll to **Your apps**.
2. Look for an Android app with package name **`com.aistudio.veloledger.vpnbook`**. This must match `applicationId` in `app/build.gradle.kts`; don't change either one, or existing installs won't update.
3. If it's missing: *Add app → Android icon*, package name `com.aistudio.veloledger.vpnbook`, nickname "NodeSplit", leave the SHA-1 empty for now, *Register app*, skip the remaining wizard steps.
4. Leave this page open; you come back here in Part 6 to add SHA fingerprints and download `google-services.json`.

### 3.3 Turn on Google sign-in
1. Left menu: **Build → Authentication** (click *Get started* the first time).
2. **Sign-in method** tab → **Google** → switch **Enable** on → choose a *Project support email* → **Save**.
3. Make sure no other provider you don't use (e.g. Email/Password) is enabled. The app only uses Google.

### 3.4 Firestore database and its ID
1. Left menu: **Build → Firestore Database**.
2. If you see *Create database*: choose a location close to your users (it can't be changed later), choose **Production mode**, *Create*.
3. **Find your database ID.** You need it for `firebase_applet_config.xml`:
   - If there is a database selector at the top of the Firestore page, the name shown there is the ID (AI Studio projects often use a name like `ai-studio-…`).
   - If there is no selector, the ID is `(default)` (with the brackets).
   - You can also read it in the browser address bar: `…/firestore/databases/<ID>/data`.

### 3.5 Publish the new security rules **(NEW)**
Do this at the same time you send the new APK to sellers (see Part 7 for why).

**Easiest way: console**
1. *Firestore Database → Rules* tab. If there's a database selector, pick the same database as in 3.4.
2. Select everything in the editor and delete it.
3. Open `firestore.rules` from the project, copy the whole file, paste it in.
4. Click **Publish**. If the editor shows a red error, the paste was incomplete; copy the file again.

**Alternative: command line** (from the project folder)
```
npm install -g firebase-tools
firebase login
firebase use --add            # pick your project
firebase deploy --only firestore:rules
```
If your database ID is not `(default)`, first edit `firebase.json` and change `"database": "(default)"` to your ID.

### 3.6 The workspace document **(NEW, but probably already there)**
The app no longer creates the workspace itself (otherwise any stranger who signed in first would become admin).

1. *Firestore Database → Data* tab. Look for a collection **`config`** with a document **`workspace`**.
2. **If it exists**, click it and check that `adminUid` is your own user ID (see step 3 below). If it is, you are done; skip the rest of this section.
3. **Find your user ID:** *Build → Authentication → Users* tab → the row with your Gmail → copy the **User UID** column. (The new app also shows it on the *Workspace not set up yet* screen.)
4. **If `config/workspace` doesn't exist**, create it:
   - *+ Start collection* → Collection ID `config` → *Next*.
   - Document ID: `workspace` (type it, don't use Auto-ID).
   - Add these fields (field name, type, value):

     | Field | Type | Value |
     | --- | --- | --- |
     | `adminUid` | string | your User UID from step 3 |
     | `adminEmail` | string | (leave empty) |
     | `businessName` | string | `NodeSplit VPN` |
     | `costPerGbToman` | number | e.g. `1000` |
     | `adminProfitPercent` | number | `50` |
     | `settingsRevision` | number | `1` |
     | `createdAt` | timestamp | today's date and time |
     | `updatedAt` | timestamp | today's date and time |

   - *Save*. Your own profile (`users/<your uid>`) is created by the app the first time you sign in.

### 3.7 Optional: back up before switching rules
A Firestore export needs the Blaze (pay-as-you-go) plan and a Cloud Storage bucket: *Firestore Database → Import/Export* (or `gcloud firestore export gs://<bucket>`). On the free plan, skip this; the new version doesn't rewrite or delete existing data.

---

## Part 4: Put the Firebase info into the project

These two files are private to your copy and are excluded by `.gitignore`, so they are never uploaded to GitHub.

### 4.1 `app/google-services.json`
1. Do Part 6.2 first (add SHA fingerprints), because the file's sign-in section depends on them.
2. Firebase console → ⚙ **Project settings → General → Your apps** → the NodeSplit Android app → **google-services.json** download button.
3. Put the file in the **`app`** folder, next to `app/build.gradle.kts`, named exactly `google-services.json` (not `google-services (1).json`).
4. Quick check: open it in a text editor. Under `"oauth_client"` there must be at least one entry with `"client_type": 3`. If `oauth_client` is empty (`[]`), Google sign-in isn't enabled yet (Part 3.3) or no SHA was added (Part 6.2); fix that and download again.

### 4.2 `app/src/main/res/values/firebase_applet_config.xml`
1. In `app/src/main/res/values/` copy `firebase_applet_config.xml.example` to `firebase_applet_config.xml` in the same folder. (In Android Studio switch the project panel to *Project* view, right-click the file → *Copy*, then right-click the folder → *Paste*, and name it `firebase_applet_config.xml`.)
2. Set the database ID from Part 3.4:
   ```xml
   <resources>
       <string name="firestore_database_id" translatable="false">(default)</string>
   </resources>
   ```
   Replace `(default)` with your ID if it's different, for example `ai-studio-1234abcd`.

### 4.3 Things you don't need to touch
- `.env` / `.env.example`: only for a Gemini key, which the app doesn't use.
- `firebase.json`: only used by the command-line deploy and the emulator.
- `metadata.json`: AI Studio metadata.

---

## Part 5: Open, test and build in Android Studio

### 5.1 Open and sync
1. *File → Open* → select the project folder (the one containing `settings.gradle.kts`) → *Trust Project*.
2. Android Studio runs **Gradle sync** (progress bar at the bottom). The first sync downloads Gradle 9.3.1 and the libraries, which can take 5–15 minutes.
3. If it asks to install a missing **Android SDK Platform 36.1** or build tools, click the install link in the error. Or: *Tools → SDK Manager → SDK Platforms* → tick *Android 16 (API 36.1)* (enable *Show Package Details* if needed) → *Apply*.
4. If sync complains about the JDK: *Settings (File → Settings on Windows, Android Studio → Settings on Mac) → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK* → choose the bundled **jbr-21**.
5. Sync succeeded when the bottom *Build* panel says *BUILD SUCCESSFUL* and the green ▶ Run button is enabled.

### 5.2 Where to run `./gradlew …`
Open the **Terminal** tab at the bottom of Android Studio (or *View → Tool Windows → Terminal*). It opens in the project folder, which is where the commands go.

| Your computer | Command form |
| --- | --- |
| macOS / Linux | `./gradlew <task>` |
| Windows (PowerShell, the default in Android Studio) | `.\gradlew <task>` |
| Windows (cmd) | `gradlew <task>` |

Useful commands:

```
./gradlew assembleDebug                      # build a debug APK
./gradlew testDebugUnitTest --tests "com.example.data.model.FinancialRulesTest"   # money/validation tests, no setup needed
./gradlew testDebugUnitTest --tests "com.example.ExampleRobolectricTest"          # formatting + PDF test
./gradlew signingReport                      # prints SHA-1/SHA-256 of your keys (Part 6)
```

`./gradlew testDebugUnitTest` with no filter also runs `LedgerRepositoryRuleTest`, which needs the Firebase emulators running. Start them in a second terminal first: `npx firebase emulators:start --only auth,firestore` (needs Node.js), then run the tests. Without the emulators that one test class fails with timeouts; that is expected, not a bug.

Rules tests (Node.js, from the project folder):
```
npm i -D firebase-tools @firebase/rules-unit-testing firebase
npx firebase emulators:exec --project demo-no-project --only firestore "node --test firestore.test.js"
```
All 9 should print `ok`.

### 5.3 Run on your phone (quickest way to try it)
1. On the phone: *Settings → About phone* → tap *Build number* 7 times. Then *Settings → System → Developer options* → turn on **USB debugging**.
2. Connect the phone by USB and accept the "Allow USB debugging?" prompt on the phone.
3. In Android Studio pick your phone in the device dropdown at the top, then click ▶ **Run**.

### 5.4 Build a debug APK file
- Menu **Build → Build App Bundle(s) / APK(s) → Build APK(s)** (newer versions: *Build → Generate App Bundles or APKs → Generate APKs*), or run `./gradlew assembleDebug`.
- When it finishes, click *locate* in the popup. The file is `app/build/outputs/apk/debug/app-debug.apk`.
- Debug APKs install fine on sellers' phones, but for real use build a signed release APK (5.5).

### 5.5 Build a signed release APK
1. Menu **Build → Generate Signed App Bundle or APK…** → choose **APK** → *Next*.
2. **Key store path**:
   - If you already have the upload keystore AI Studio used (`my-upload-key.jks`) and its passwords, choose it (*Choose existing…*). Alias: `upload`.
   - Otherwise *Create new…*: save it somewhere safe outside the project (e.g. `Documents/keys/nodesplit-release.jks`), set a password, alias `upload`, validity 25+ years, fill in at least your name, *OK*.
3. Enter the passwords, tick *Remember passwords* if you like → *Next*.
4. Build variant **release** → *Create* (older versions: *Finish*).
5. Output: `app/release/app-release.apk` (or `app/build/outputs/apk/release/`). Click *locate* in the popup.
6. **Back up the keystore file and both passwords** (e.g. in a password manager). If you lose them, you can never ship an update that installs over this one.
7. Add this key's SHA-1 and SHA-256 to Firebase (Part 6), otherwise Google sign-in fails in the release APK.

(From the command line instead: put the keystore at the project root as `my-upload-key.jks`, or set the `KEYSTORE_PATH` environment variable, set `STORE_PASSWORD` and `KEY_PASSWORD`, then run `./gradlew assembleRelease`.)

---

## Part 6: Signing keys and SHA fingerprints

Google sign-in only works for APKs whose signing key is registered in Firebase. Every key you build with (debug and release) needs its fingerprints added.

### 6.1 Get the fingerprints
**Easiest:** in the Android Studio terminal run `./gradlew signingReport` (Windows: `.\gradlew signingReport`). For each variant it prints `SHA1:` and `SHA-256:` lines. Use the ones under `Variant: debug` and `Variant: release`.

**With keytool** (comes with Android Studio's Java):
- Windows: `"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -list -v -keystore "C:\path\to\nodesplit-release.jks" -alias upload`
- macOS: `"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" -list -v -keystore ~/keys/nodesplit-release.jks -alias upload`
- Your normal debug key: keystore `%USERPROFILE%\.android\debug.keystore` (Windows) or `~/.android/debug.keystore` (Mac/Linux), alias `androiddebugkey`, password `android`.
- If you have AI Studio's `debug.keystore` in the project root, the debug build uses it; check it the same way (alias `androiddebugkey`, password `android`).

### 6.2 Add them to Firebase
1. ⚙ **Project settings → General → Your apps → NodeSplit Android app → Add fingerprint**.
2. Paste the SHA-1, *Save*. Repeat for the SHA-256. Repeat for each key (debug, release).
3. Download `google-services.json` again and replace the one in `app/` (Part 4.1). Then rebuild.

### 6.3 Updating phones that already have the AI Studio version **(important)**
Android only installs an update over an existing app when both are signed with **the same key**.
- If you build with the same keystore AI Studio used (its `my-upload-key.jks` for release, or its `debug.keystore` for debug), the new APK installs over the old one. Nothing else to do.
- If you build with a different key, Android refuses the update ("App not installed" / "package conflicts"). The old app must be uninstalled first. **Before uninstalling, make sure that phone has synced** (cloud icon in the top bar shows done, no "waiting to sync" banner). Sales that reached Firebase come back after signing in again; changes that never synced are lost.

---

## Part 7: Release to your sellers

The new rules and the new app need each other, so switch both at the same time:

1. Build and test the APK on your own phone first (Part 8, using the old rules is fine for a quick look, but sending a report needs the new rules).
2. Publish the new rules (Part 3.5).
3. Right away, send the APK to every seller (Telegram, Drive, etc.). On their phone they open the file and allow *Install unknown apps* for the app they opened it from.
4. Until a seller updates, they can still add sales, but *Send report* fails. They just send again after updating; nothing is lost.

---

## Part 8: Check that everything works

Go through this once with your admin account and one test seller account (a second Gmail):

1. **Admin sign-in**: you land on *Sales* with the *Admin* label. If you see *Workspace not set up yet*, redo Part 3.6.
2. **Approve a seller**: *Settings → Approve a seller* → enter the test Gmail. Sign in with it on another phone (or after signing out); it should get straight in.
3. **Unknown account**: sign in with a Gmail you did not approve. It must stop at *Pending Admin Approval*.
4. **Revoke**: in *Settings → Accounts*, tap *Revoke* on the test seller. Their app shows *access revoked* and can no longer load sales.
5. **Sale and report** (as the approved seller): add a sale, tick *Paid*, *Send report*. On the admin phone switch to that seller's book: the report appears with the same amounts.
6. **Report history**: change a sale, send again. Under *Archives* the admin sees both reports; the older one hasn't changed.
7. **New cycle**: *Start new cycle* with *Save this cycle to Archives*. The sales list empties, the archive appears, and the admin no longer sees an active report for that seller.
8. **Void**: open the archive → *Void…* → enter a reason. It stays in the list marked *Voided*.
9. **PDF**: *Export PDF* → *Save PDF* → choose a folder → open the file.
10. **Offline**: turn on airplane mode, add a sale (banner "Saved on this phone…"), turn airplane mode off, wait until it syncs.

---

## Troubleshooting

| What you see | Cause and fix |
| --- | --- |
| "Google Sign-In configuration missing: default_web_client_id not found" | `app/google-services.json` is missing, or its `oauth_client` list is empty. Do Parts 3.3 and 6.2, download it again (4.1), rebuild. |
| Google sign-in fails with "developer error", code 10, or `[28444]` | The SHA-1 of the key this APK was signed with isn't in Firebase. Run `signingReport`, add the fingerprint (6.2), download `google-services.json` again, rebuild. |
| *Workspace not set up yet* | `config/workspace` is missing or in a different database. Check Part 3.6 and that `firestore_database_id` (4.2) matches the database in the console. |
| "The server refused this action" / PERMISSION_DENIED everywhere | Rules not published, published to a different database, or the account isn't approved. Recheck 3.5 and the account's status under *Settings → Accounts*. |
| Everything is empty, but data exists in the console | `firestore_database_id` points at the wrong database (for example `(default)` instead of `ai-studio-…`). |
| "Send report" fails for a seller | That seller still has the old app, or the new rules aren't published yet. |
| Gradle sync: "Android Gradle plugin requires a newer Android Studio" | Update Android Studio. |
| Gradle sync: "Failed to find target android-36.1" | Install the platform in *Tools → SDK Manager* (5.1 step 3). |
| Release build: "Keystore file … not found" | Use *Build → Generate Signed App Bundle or APK* (5.5), or set `KEYSTORE_PATH`, `STORE_PASSWORD` and `KEY_PASSWORD`. |
| "App not installed" when updating a seller's phone | Signed with a different key than the installed app. See 6.3. |
