# NodeSplit security model

This describes what `firestore.rules` actually enforces. Every rule is covered by `firestore.test.js`.

## Identity and membership

1. **Workspace.** `config/workspace` holds the Admin's UID. No client can create it (`allow create: if false`); the owner creates it once in the console. Only the Admin can update it, and `adminUid` can never change.
2. **Approved member.** A caller is an approved member only if `users/{uid}` exists, has `status == 'APPROVED'` and belongs to the current workspace. Unknown, empty, `PENDING` and `REVOKED` statuses are not approved. Every ledger read and write checks this on the server.
3. **Token binding.** A self-created profile must use the signed-in token's email. Self-approval (new APPROVED profile, or PENDING → APPROVED) also requires `email_verified == true` and an APPROVED invitation for that exact email. A REVOKED profile can never approve itself; only the Admin can restore it.
4. **Invitations.** `approved_emails/{key}` is keyed by the canonical email (lower-case, trimmed). A non-admin can read only the key equal to their own token email, or a legacy-keyed invitation whose stored email is theirs. Probing anyone else's key is denied whether or not it exists, so it doesn't reveal who is invited. Only the Admin can create, change, list or delete invitations.
5. **Roles.** Nobody can make themselves Admin; the Admin role exists only for `workspace.adminUid`.

## Ledgers

6. **Isolation.** A seller reads and writes only `ledgers/{own uid}/…`. The Admin can read every seller's book and has a private book no seller can read.
7. **Working sales** (`entries`) are editable by their approved owner (and the Admin), with a strict field whitelist, finite positive GB, non-negative whole-Toman prices, past timestamps and immutable owner/creation fields.
8. **Sent reports** (`submissions`) are created only by their approved owner, with the server's receipt time. They can never be rewritten or deleted. The only changes allowed are one payment checkbox (`paidMap`) and, for the Admin only, `adminStatus` (RECEIVED/SETTLED).
9. **Current report pointer** (`pointers/latest_submission`) can only point at a report that exists, or be cleared at a new cycle.
10. **Archives** (`archives`) can't be deleted by anyone through the app. Allowed changes: one payment checkbox, or a one-way void with a 3–300 character reason, the caller's UID and the server time. A voided archive is fully read-only. The legacy `submitted_current` document is read-only.
11. **Snapshot validation.** Totals must satisfy `paid + unpaid == total`, `items.size() == entryCount ≤ 500`, and the first and last items are type-checked. Firestore rules cannot loop over a list, so the app re-derives every total from the items it can read and flags any mismatch or unreadable item instead of trusting stored totals.
12. **Presets and rates** are writable only by the Admin and readable by approved members.

## Known limits

- All sales figures are self-reported by sellers. The rules make reports consistent and tamper-evident after sending; they cannot prove a seller provisioned the GB or collected the money.
- A phone that is offline cannot be revoked until it reconnects, and data already cached on it cannot be "unread".
- App Check is not enabled. It adds protection against modified clients but does not replace these rules.
