# Changes: audit fixes (October 2026)

This round applies the Claude audit and the consolidated implementation brief. IDs refer to the brief; `#n` to the audit. Several brief items had already been applied in an earlier pass (exact approval check, removal of the hardcoded admin email, the 500-sale guard, Save-as via the system dialog, measured PDF wrapping); those were verified and kept.

## Verification actually run

| Check | Result |
| --- | --- |
| `firestore.test.js` against the Firestore emulator (firebase-tools 13) | **9/9 pass**: T01–T08 and a 500-sale archive/report test |
| `FinancialRulesTest` (plain JUnit, JVM) | **12/12 pass**, including both screenshot fixtures |
| Kotlin type-check of models, repository, ViewModel, PDF exporter | Compiles (against small Firebase/Android stand-ins) |
| Kotlin + Compose type-check of every changed UI file and `MainActivity` | Compiles (against Compose Multiplatform 1.7.3 desktop + stand-ins for Android-only APIs) |
| Gradle Android build, APK, Robolectric tests, emulator/device runs | **Not run.** The review environment cannot download the Android SDK or Google Maven artifacts. `LedgerRepositoryRuleTest` was updated but not executed. |

Before release, run `./gradlew assembleDebug testDebugUnitTest` locally and try sign-in, offline use and a PDF export on a real phone.

## Done

### Security (Critical #1–#4; SEC-01 … SEC-05)
- **SEC-01** Approval is `status == "APPROVED"` in the app, and every ledger rule requires an approved profile in the current workspace (already present; verified by T01).
- **SEC-02** Self-created profiles must use the token email; self-approval also needs `email_verified` (audit #2). Invitations are now keyed by the email itself, so `a.b@x` and `a_dot_b@x` can no longer collide; old keys are migrated by the Admin's app using only each document's stored email. A non-admin can read only their own invitation, and probing another key fails whether or not it exists.
- **SEC-03** Revoked sellers can't self-reverse (T03). Approving or revoking changes the profile and the invitation in one batch, and old-key copies are removed in that batch. *Cancel invitation* and *Revoke* are separate, clearly labelled actions. Permission-denied on the profile listener now shows an error instead of reusing the last approved profile.
- **SEC-04** No client can create `config/workspace` (audit #4). The app shows a *Workspace not set up yet* screen with the user ID to paste into the console. No hardcoded admin email (audit #3).
- **SEC-05** Archive and report totals are re-derived from their items, and a mismatch or unreadable item is flagged on screen instead of trusted or crashing.

### Money and history (High #5, #6; DATA-01 … DATA-03, MONEY-01, MONEY-02)
- **DATA-01** *Send report* creates a new permanent document under `submissions/` with its own id, server receipt time, captured rates and settings revision; a pointer marks the latest one. Reports can't be overwritten, edited or deleted, the Admin can mark one *Settled*, and both sides can browse the history under *Archives*. A new cycle clears the current-report pointer.
- **DATA-02** Starting a new cycle re-reads every sale in a transaction when online and aborts if another device changed or removed one; offline it queues one atomic batch. Only the captured sale ids are deleted. Cycles over 500 sales are refused before anything is written.
- **DATA-03** Payment checkboxes in archives and reports change only one `paidMap` entry, so two devices ticking different sales don't overwrite each other. Archives can no longer be deleted; they can be voided with a reason (kept, marked, read-only). Deleting a working sale asks for confirmation.
- **MONEY-01** One calculation for the dashboard, report, archive, PDF and sale preview. Captured zero rates stay zero; old records without rates are labelled. Covered by JVM tests (odd amounts, fractional GB, 0 %/100 %, losses, bonus counted once).
- **MONEY-02** Inputs are validated, not coerced (already present; verified by tests). Saving rates now increments `settingsRevision`.

### Reliability (REL-01 … REL-04)
- **REL-01** The session's ViewModel lives in a store that is cleared on sign-out or account change, so old Firestore listeners don't keep running.
- **REL-02** Entries are tagged with the book they were loaded for. Send, reset and export are disabled until the selected book has loaded, and a seller who disappears falls back to the Admin's own book.
- **REL-03** A failed load shows an error card with *Try again* instead of an empty ledger. Firestore errors are turned into plain messages (offline, refused, …).
- **REL-04** Open/Share/Save handle a missing file or missing app without crashing.

### UI (UI-01 … UI-04, PDF-01/02, UX-01)
- New shared components (`FinancialSummaryComponents.kt`) stack label, full amount and explanation for every figure. Nothing ellipsizes money, and buttons wrap onto a new line when space is short.
- **UI-01** Dashboard order: gross sales, then sales count and GB, then *Financial breakdown* (infrastructure with its formula on its own line, seller profit, admin profit), then the amount-due block, then a rates note and the buttons.
- **UI-02** The app bar shows NodeSplit, the role and the sync state; the selected book has its own full-width line. Archive cards give title, owner and date their own lines, put the amounts in separate blocks, and keep actions below.
- **UI-03** Archive sheet: separate header lines, a close button with its own touch target, stacked metrics, the amount due in its own block, wrapping actions.
- **UI-04** Wording: *Sales / Archives / Settings*, *Financial breakdown*, *Paid / Not paid yet*, *Export PDF*, *Start new cycle*, *Send report*. Export PDF appears twice (summary card and menu) instead of three times. The Admin's private book shows *Business profit* instead of a transfer to themselves, in the app and in the PDF. Loss-making sales get a warning. The checkboxes have TalkBack labels and state.
- **PDF-02** Summary amounts in the PDF are never cut: they shrink slightly, then move the unit to a second line.
- Search shows "Showing X of N. Totals and PDF export include all N."

### Privacy and release (PRIV-01 … PRIV-03)
- **PRIV-01** No signing material is in the repository. Debug builds use `debug.keystore` only if it exists locally (keeps in-place updates for AI Studio builds), otherwise the normal debug key.
- **PRIV-02** Backups and device transfer exclude all app data (`allowBackup=false` plus extraction rules). Release builds log only the operation and error code, not paths, UIDs or emails. Signing out with unsynced changes asks first.

## Not done (needs a decision, a real device, or a larger change)

| Item | Why it's open |
| --- | --- |
| **OFF-01 … OFF-05** Room database + outbox + WorkManager | The app relies on Firestore's on-device cache, which already saves changes locally and syncs later (statuses: *Saved on this phone*, *Queued*, *Uploaded*). A full Room/outbox layer is a large redesign that must be built and tested on devices. |
| **DATA-02** Item sub-collections for cycles over 500 sales | Over 500 is refused up front for now; nothing is truncated. |
| **UI-04** Move every string to `strings.xml` | Mechanical but touches every screen; best done together with any Persian translation. |
| **T21–T29** Screenshot renders at 320–411 dp and large fonts, TalkBack, real sign-in, release build | Needs an Android build and a device or emulator. |
| **PRIV-03** App Check, R8 shrinking | Must be tested on a real build before enforcing, or sellers could be locked out. |
| **#7 / §10** Business rules: whether unpaid sales count toward the amount due, who absorbs discounts and losses, seller→Admin payment records | Business decisions; the current behaviour (everything counts, profits share discounts and losses) is unchanged and documented in the README. |
| **#8** Cross-checking reports against the Remnawave panel | Optional, needs a trusted backend so panel credentials never ship in the APK. |
