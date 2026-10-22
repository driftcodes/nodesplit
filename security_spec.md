# VeloLedger Security Specification (Phase 0 TDD)

## 1. Data Invariants
1. **Workspace Singleton Invariant**: `/config/workspace` can only be created once when it does not yet exist, and `adminUid` must match `request.auth.uid`. Once created, `adminUid` and `adminEmail` are immutable.
2. **Role & Privilege Anti-Escalation Invariant**: A Seller cannot self-assign `role: 'ADMIN'` or self-approve `status: 'APPROVED'` in `/users/{userId}` unless their email key exists in `/approved_emails/{emailKey}` with `status == 'APPROVED'` and matching `email`.
3. **Seller vs. Admin Ledger Isolation Invariant**:
   - A Seller (`uid == S1`) can only read/write `/ledgers/S1/entries`, `/ledgers/S1/archives`, and `/ledgers/S1/presets`.
   - A Seller (`uid == S1`) is strictly forbidden from reading or writing another Seller's ledger (`/ledgers/S2/...`) OR the Admin's private ledger (`/ledgers/AdminUid/...`).
   - The Workspace Admin (`uid == workspaceAdminUid`) can read and write their own private ledger (`/ledgers/AdminUid/...`) AND inspect/manage any Seller's ledger (`/ledgers/S1/...`) whose `workspaceAdminUid == AdminUid`.
4. **Query Enforcer Invariant**: Every `allow list` rule evaluates `resource.data` directly (`resource.data.ledgerOwnerUid == request.auth.uid` or `resource.data.workspaceAdminUid == request.auth.uid`) without any `get()` or `exists()` calls inside `list`.

## 2. The "Dirty Dozen" Adversarial Payloads
1. **Unauthenticated Scrape**: Unauthenticated caller attempts `get` or `list` on `/ledgers/{uid}/entries`.
2. **Seller Reads Admin Private Book**: Authenticated Seller `S1` attempts `get` or `list` on `/ledgers/{adminUid}/entries`.
3. **Cross-Seller Snooping**: Authenticated Seller `S1` attempts `get` or `list` on `/ledgers/{seller2Uid}/entries`.
4. **Privilege Escalation on Registration**: Seller `S1` attempts to create `/users/S1` with `role: 'ADMIN'` when `/config/workspace` belongs to `adminUid`.
5. **Unapproved Self-Approval**: Seller `S1` attempts to create `/users/S1` with `status: 'APPROVED'` without a corresponding `/approved_emails/{emailKey}` document.
6. **Shadow Field Injection ("Ghost Key")**: Seller `S1` creates a `SaleEntry` containing an extra key `"isVerified": true`.
7. **Negative Price / GB Overflow**: Seller `S1` creates a `SaleEntry` with `priceToman: -50000` or `dataGb: 0`.
8. **Future Timestamp Spoofing**: Caller writes a `SaleEntry` with `createdAt` set in the future (`> request.time`).
9. **Immutable Ownership Mutation**: Seller `S1` updates an existing `SaleEntry` to change `ledgerOwnerUid` or `createdAt`.
10. **Archive Tampering**: Caller attempts to `update` an existing `LedgerArchive` snapshot in `/ledgers/{uid}/archives/{archiveId}`.
11. **PII Blanket Read on `/users`**: Seller `S1` attempts to `get` `/users/S2` or `list` all `/users`.
12. **Unfiltered Collection List**: Caller attempts to `list` `/ledgers/{uid}/entries` without aligning with `ledgerOwnerUid` / `workspaceAdminUid`.
