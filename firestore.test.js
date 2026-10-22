// Firestore security rules tests. Run against the emulator:
//   npx firebase emulators:exec --only firestore "node --test firestore.test.js"
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const {
  doc,
  getDoc,
  setDoc,
  updateDoc,
  deleteDoc,
  writeBatch,
  serverTimestamp,
} = require("firebase/firestore");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ADMIN_UID = "admin_uid_001";
const SELLER_A_UID = "seller_a_001";
const SELLER_B_UID = "seller_b_002";
const PENDING_SELLER_UID = "seller_pending_003";
const REVOKED_SELLER_UID = "seller_revoked_004";
const NO_PROFILE_UID = "seller_noprofile_005";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastTimestamp() {
  return new Date(Date.now() - 1000);
}

// Auth contexts. Sellers carry a verified Google email, like a real Google sign-in.
function asAdmin() {
  return testEnv.authenticatedContext(ADMIN_UID, { email: "admin@nodesplit.com", email_verified: true }).firestore();
}
function asSellerA() {
  return testEnv.authenticatedContext(SELLER_A_UID, { email: "sellera@nodesplit.com", email_verified: true }).firestore();
}
function asSellerB() {
  return testEnv.authenticatedContext(SELLER_B_UID, { email: "sellerb@nodesplit.com", email_verified: true }).firestore();
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function profile(uid, email, emailKey, displayName, role, status) {
  const now = pastTimestamp();
  return {
    userId: uid,
    workspaceAdminUid: ADMIN_UID,
    email,
    emailKey,
    displayName,
    role,
    status,
    createdAt: now,
    updatedAt: now,
  };
}

async function seedWorkspaceAndProfiles() {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    const now = pastTimestamp();
    await setDoc(doc(db, "config/workspace"), {
      adminUid: ADMIN_UID,
      adminEmail: "",
      businessName: "NodeSplit VPN",
      costPerGbToman: 1200,
      adminProfitPercent: 50,
      settingsRevision: 1,
      createdAt: now,
      updatedAt: now,
    });
    await setDoc(doc(db, `users/${ADMIN_UID}`), profile(ADMIN_UID, "admin@nodesplit.com", "admin@nodesplit.com", "Admin", "ADMIN", "APPROVED"));
    await setDoc(doc(db, `users/${SELLER_A_UID}`), profile(SELLER_A_UID, "sellera@nodesplit.com", "sellera@nodesplit.com", "Seller A", "SELLER", "APPROVED"));
    await setDoc(doc(db, `users/${SELLER_B_UID}`), profile(SELLER_B_UID, "sellerb@nodesplit.com", "sellerb@nodesplit.com", "Seller B", "SELLER", "APPROVED"));
    await setDoc(doc(db, `users/${PENDING_SELLER_UID}`), profile(PENDING_SELLER_UID, "pending@nodesplit.com", "pending_at_nodesplit_dot_com", "Pending Seller", "SELLER", "PENDING"));
    await setDoc(doc(db, `users/${REVOKED_SELLER_UID}`), profile(REVOKED_SELLER_UID, "revoked@nodesplit.com", "revoked@nodesplit.com", "Revoked Seller", "SELLER", "REVOKED"));
  });
}

async function seedInvitation(key, email, status = "APPROVED") {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const ts = pastTimestamp();
    await setDoc(doc(context.firestore(), `approved_emails/${key}`), {
      emailKey: key,
      email,
      sellerName: "Invited Seller",
      workspaceAdminUid: ADMIN_UID,
      status,
      createdAt: ts,
      updatedAt: ts,
    });
  });
}

function salePayload(uid, overrides = {}) {
  const ts = pastTimestamp();
  return {
    ledgerOwnerUid: uid,
    workspaceAdminUid: ADMIN_UID,
    customerName: "Test Customer",
    dataGb: 30,
    priceToman: 135000,
    originalPresetPriceToman: 135000,
    presetName: "25 GB Standard",
    isPaid: false,
    isNewUser: true,
    bonusGb: 5,
    note: "",
    createdAt: ts,
    updatedAt: ts,
    ...overrides,
  };
}

function snapshotPayload(uid, overrides = {}) {
  return {
    ledgerOwnerUid: uid,
    workspaceAdminUid: ADMIN_UID,
    ownerName: "Seller A",
    cycleTitle: "Cycle 1",
    totalSalesToman: 135000,
    paidToman: 0,
    unpaidToman: 135000,
    totalGb: 30,
    costPerGbToman: 1200,
    adminProfitPercent: 50,
    entryCount: 1,
    items: [{ itemId: "sale_1", customerName: "Ali Rezaei", dataGb: 30, priceToman: 135000, isPaid: false }],
    paidMap: { sale_1: false },
    createdAt: pastTimestamp(),
    ...overrides,
  };
}

test("T01: signed-out, no-profile, pending and revoked callers are denied on protected ledger paths", async () => {
  await seedWorkspaceAndProfiles();

  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(getDoc(doc(unauthDb, "config/workspace")));
  await assertFails(getDoc(doc(unauthDb, `ledgers/${SELLER_A_UID}/entries/s1`)));

  const noProfileDb = testEnv.authenticatedContext(NO_PROFILE_UID, { email: "np@nodesplit.com", email_verified: true }).firestore();
  await assertFails(setDoc(doc(noProfileDb, `ledgers/${NO_PROFILE_UID}/entries/s1`), salePayload(NO_PROFILE_UID)));

  const pendingDb = testEnv.authenticatedContext(PENDING_SELLER_UID, { email: "pending@nodesplit.com", email_verified: true }).firestore();
  await assertFails(setDoc(doc(pendingDb, `ledgers/${PENDING_SELLER_UID}/entries/s1`), salePayload(PENDING_SELLER_UID)));
  await assertFails(getDoc(doc(pendingDb, `ledgers/${PENDING_SELLER_UID}/archives/a1`)));
  await assertFails(getDoc(doc(pendingDb, `ledgers/${ADMIN_UID}/presets/builtin_10`)));
  // A pending user can still read their own profile status.
  await assertSucceeds(getDoc(doc(pendingDb, `users/${PENDING_SELLER_UID}`)));

  const revokedDb = testEnv.authenticatedContext(REVOKED_SELLER_UID, { email: "revoked@nodesplit.com", email_verified: true }).firestore();
  await assertFails(setDoc(doc(revokedDb, `ledgers/${REVOKED_SELLER_UID}/entries/s1`), salePayload(REVOKED_SELLER_UID)));
  await assertFails(getDoc(doc(revokedDb, `ledgers/${REVOKED_SELLER_UID}/entries/s1`)));
  await assertFails(
    setDoc(doc(revokedDb, `ledgers/${REVOKED_SELLER_UID}/submissions/sub_1`), snapshotPayload(REVOKED_SELLER_UID, { createdAt: serverTimestamp() }))
  );

  // Approved owner still works.
  await assertSucceeds(setDoc(doc(asSellerA(), `ledgers/${SELLER_A_UID}/entries/s1`), salePayload(SELLER_A_UID)));
});

test("T02: invitations are bound to the signed-in token email and do not leak", async () => {
  await seedWorkspaceAndProfiles();
  await seedInvitation("invited@nodesplit.com", "invited@nodesplit.com");
  await seedInvitation("legacy_at_nodesplit_dot_com", "legacy@nodesplit.com");
  const ts = pastTimestamp();

  const attackerDb = testEnv.authenticatedContext("attacker_uid", { email: "attacker@evil.com", email_verified: true }).firestore();
  // Probing someone else's key fails whether or not the invitation exists (no existence leak).
  await assertFails(getDoc(doc(attackerDb, "approved_emails/invited@nodesplit.com")));
  await assertFails(getDoc(doc(attackerDb, "approved_emails/nobody@nodesplit.com")));
  await assertFails(getDoc(doc(attackerDb, "approved_emails/legacy_at_nodesplit_dot_com")));
  // Own key is readable even when no invitation exists.
  await assertSucceeds(getDoc(doc(attackerDb, "approved_emails/attacker@evil.com")));

  // Claiming another person's invited email is denied (approved or pending).
  const impersonation = profile("attacker_uid", "invited@nodesplit.com", "invited@nodesplit.com", "Impersonator", "SELLER", "APPROVED");
  await assertFails(setDoc(doc(attackerDb, "users/attacker_uid"), { ...impersonation, createdAt: ts, updatedAt: ts }));
  await assertFails(setDoc(doc(attackerDb, "users/attacker_uid"), { ...impersonation, status: "PENDING", createdAt: ts, updatedAt: ts }));
  // A pending profile for one's own token email is fine.
  await assertSucceeds(
    setDoc(doc(attackerDb, "users/attacker_uid"), profile("attacker_uid", "attacker@evil.com", "attacker@evil.com", "Attacker", "SELLER", "PENDING"))
  );

  // Unverified token email cannot self-approve, even with a matching invitation.
  const unverifiedDb = testEnv.authenticatedContext("invited_uid", { email: "invited@nodesplit.com", email_verified: false }).firestore();
  await assertFails(
    setDoc(doc(unverifiedDb, "users/invited_uid"), profile("invited_uid", "invited@nodesplit.com", "invited@nodesplit.com", "Invited", "SELLER", "APPROVED"))
  );

  // Verified invitee self-approves.
  const invitedDb = testEnv.authenticatedContext("invited_uid", { email: "invited@nodesplit.com", email_verified: true }).firestore();
  await assertSucceeds(getDoc(doc(invitedDb, "approved_emails/invited@nodesplit.com")));
  await assertSucceeds(
    setDoc(doc(invitedDb, "users/invited_uid"), profile("invited_uid", "invited@nodesplit.com", "invited@nodesplit.com", "Invited", "SELLER", "APPROVED"))
  );

  // Legacy-keyed invitation: own one is readable, and a PENDING profile can upgrade while moving to the email key.
  const legacyDb = testEnv.authenticatedContext("legacy_uid", { email: "legacy@nodesplit.com", email_verified: true }).firestore();
  await assertSucceeds(getDoc(doc(legacyDb, "approved_emails/legacy_at_nodesplit_dot_com")));
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await setDoc(
      doc(context.firestore(), "users/legacy_uid"),
      profile("legacy_uid", "legacy@nodesplit.com", "legacy_at_nodesplit_dot_com", "Legacy", "SELLER", "PENDING")
    );
  });
  await assertSucceeds(
    updateDoc(doc(legacyDb, "users/legacy_uid"), { status: "APPROVED", updatedAt: serverTimestamp() })
  );

  // Admin can read and list every invitation.
  await assertSucceeds(getDoc(doc(asAdmin(), "approved_emails/invited@nodesplit.com")));
});

test("T03: a revoked seller cannot self-reverse, even with an approved invitation", async () => {
  await seedWorkspaceAndProfiles();
  await seedInvitation("revoked@nodesplit.com", "revoked@nodesplit.com");

  const revokedDb = testEnv.authenticatedContext(REVOKED_SELLER_UID, { email: "revoked@nodesplit.com", email_verified: true }).firestore();
  await assertFails(updateDoc(doc(revokedDb, `users/${REVOKED_SELLER_UID}`), { status: "APPROVED", updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(doc(revokedDb, `users/${REVOKED_SELLER_UID}`), { role: "ADMIN", updatedAt: serverTimestamp() }));

  // The admin can still restore them explicitly.
  await assertSucceeds(updateDoc(doc(asAdmin(), `users/${REVOKED_SELLER_UID}`), { status: "APPROVED", updatedAt: serverTimestamp() }));
});

test("T04: sellers manage only their own book; settings and presets are admin-only", async () => {
  await seedWorkspaceAndProfiles();
  const sellerADb = asSellerA();

  await assertSucceeds(setDoc(doc(sellerADb, `ledgers/${SELLER_A_UID}/entries/sale_1`), salePayload(SELLER_A_UID)));
  await assertFails(setDoc(doc(sellerADb, `ledgers/${SELLER_B_UID}/entries/sale_1`), salePayload(SELLER_B_UID)));
  await assertFails(updateDoc(doc(sellerADb, "config/workspace"), { costPerGbToman: 0, updatedAt: serverTimestamp() }));
  await assertFails(
    setDoc(doc(sellerADb, `ledgers/${ADMIN_UID}/presets/builtin_10`), {
      ledgerOwnerUid: ADMIN_UID,
      workspaceAdminUid: ADMIN_UID,
      label: "Hacked 10 GB",
      dataGb: 10,
      defaultPriceToman: 1000,
      newUserBonusGb: 50,
      createdAt: pastTimestamp(),
      updatedAt: pastTimestamp(),
    })
  );

  await testEnv.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), `ledgers/${ADMIN_UID}/entries/admin_private_1`), salePayload(ADMIN_UID));
  });
  await assertFails(getDoc(doc(sellerADb, `ledgers/${ADMIN_UID}/entries/admin_private_1`)));
  await assertFails(getDoc(doc(asSellerB(), `ledgers/${SELLER_A_UID}/entries/sale_1`)));

  // Admin reads and updates rates; admin reads a seller's sale.
  await assertSucceeds(getDoc(doc(asAdmin(), `ledgers/${SELLER_A_UID}/entries/sale_1`)));
  await assertSucceeds(updateDoc(doc(asAdmin(), "config/workspace"), { costPerGbToman: 1500, updatedAt: serverTimestamp() }));
});

test("T05: frozen archives allow one paid checkbox or a reasoned void, never edits or deletes", async () => {
  await seedWorkspaceAndProfiles();
  const sellerADb = asSellerA();
  const archiveRef = doc(sellerADb, `ledgers/${SELLER_A_UID}/archives/arch_1`);

  await assertSucceeds(setDoc(archiveRef, snapshotPayload(SELLER_A_UID)));

  // Narrow paid update succeeds for the owner and the admin.
  await assertSucceeds(updateDoc(archiveRef, { "paidMap.sale_1": true, updatedAt: serverTimestamp() }));
  await assertSucceeds(updateDoc(doc(asAdmin(), `ledgers/${SELLER_A_UID}/archives/arch_1`), { "paidMap.sale_1": false }));

  // Rewriting items, totals or rates is denied.
  await assertFails(
    updateDoc(archiveRef, {
      items: [{ itemId: "sale_1", customerName: "Ali Rezaei", dataGb: 1, priceToman: 1, isPaid: true }],
    })
  );
  await assertFails(updateDoc(archiveRef, { totalSalesToman: 50000, paidToman: 50000, unpaidToman: 0 }));
  await assertFails(updateDoc(archiveRef, { costPerGbToman: 0 }));
  await assertFails(updateDoc(archiveRef, { paidToman: 135000, unpaidToman: 0 }));

  // Hard delete is denied for everyone.
  await assertFails(deleteDoc(archiveRef));
  await assertFails(deleteDoc(doc(asAdmin(), `ledgers/${SELLER_A_UID}/archives/arch_1`)));

  // Void needs a reason, the caller's uid and the server time.
  await assertFails(updateDoc(archiveRef, { voided: true, voidReason: "", voidedBy: SELLER_A_UID, voidedAt: serverTimestamp() }));
  await assertFails(updateDoc(archiveRef, { voided: true, voidReason: "Duplicate cycle", voidedBy: ADMIN_UID, voidedAt: serverTimestamp() }));
  await assertSucceeds(
    updateDoc(archiveRef, { voided: true, voidReason: "Duplicate cycle", voidedBy: SELLER_A_UID, voidedAt: serverTimestamp() })
  );
  // ...is one-way, and a voided archive is fully read-only.
  await assertFails(updateDoc(archiveRef, { voided: false }));
  await assertFails(updateDoc(archiveRef, { voidReason: "changed my mind" }));
  await assertFails(updateDoc(archiveRef, { "paidMap.sale_1": true }));

  // A new archive cannot be born voided.
  await assertFails(
    setDoc(doc(sellerADb, `ledgers/${SELLER_A_UID}/archives/arch_2`), snapshotPayload(SELLER_A_UID, { voided: true }))
  );
});

test("T06: malformed financial payloads are rejected", async () => {
  await seedWorkspaceAndProfiles();
  const sellerADb = asSellerA();

  // Totals that do not add up.
  await assertFails(
    setDoc(doc(sellerADb, `ledgers/${SELLER_A_UID}/archives/bad_1`), snapshotPayload(SELLER_A_UID, { paidToman: 100000, unpaidToman: 100000 }))
  );
  // entryCount does not match items.
  await assertFails(setDoc(doc(sellerADb, `ledgers/${SELLER_A_UID}/archives/bad_2`), snapshotPayload(SELLER_A_UID, { entryCount: 2 })));
  // NaN / negative / zero values on a sale.
  await assertFails(setDoc(doc(sellerADb, `ledgers/${SELLER_A_UID}/entries/nan`), salePayload(SELLER_A_UID, { dataGb: NaN })));
  await assertFails(setDoc(doc(sellerADb, `ledgers/${SELLER_A_UID}/entries/neg`), salePayload(SELLER_A_UID, { priceToman: -1 })));
  await assertFails(setDoc(doc(sellerADb, `ledgers/${SELLER_A_UID}/entries/zero`), salePayload(SELLER_A_UID, { dataGb: 0 })));
  // Unknown field and future timestamp.
  await assertFails(setDoc(doc(sellerADb, `ledgers/${SELLER_A_UID}/entries/ghost`), salePayload(SELLER_A_UID, { isVerified: true })));
  await assertFails(
    setDoc(doc(sellerADb, `ledgers/${SELLER_A_UID}/entries/future`), salePayload(SELLER_A_UID, { createdAt: new Date(Date.now() + 3600_000) }))
  );
  // A zero price is a supported value.
  await assertSucceeds(setDoc(doc(sellerADb, `ledgers/${SELLER_A_UID}/entries/free`), salePayload(SELLER_A_UID, { priceToman: 0 })));
});

test("T07: sent reports are immutable and only the admin records settlement", async () => {
  await seedWorkspaceAndProfiles();
  const sellerADb = asSellerA();
  const adminDb = asAdmin();
  const subPath = `ledgers/${SELLER_A_UID}/submissions/sub_1`;

  // Receipt time must be the server's.
  await assertFails(setDoc(doc(sellerADb, subPath), snapshotPayload(SELLER_A_UID)));
  // The admin cannot forge a report in a seller's book.
  await assertFails(setDoc(doc(adminDb, subPath), snapshotPayload(SELLER_A_UID, { createdAt: serverTimestamp() })));

  // Report + pointer in one batch.
  const batch = writeBatch(sellerADb);
  batch.set(doc(sellerADb, subPath), snapshotPayload(SELLER_A_UID, { createdAt: serverTimestamp(), submissionRevision: 1, schemaVersion: 2 }));
  batch.set(doc(sellerADb, `ledgers/${SELLER_A_UID}/pointers/latest_submission`), {
    ledgerOwnerUid: SELLER_A_UID,
    workspaceAdminUid: ADMIN_UID,
    submissionId: "sub_1",
    updatedAt: serverTimestamp(),
  });
  await assertSucceeds(batch.commit());

  // Both sides can read it; another seller cannot.
  await assertSucceeds(getDoc(doc(adminDb, subPath)));
  await assertSucceeds(getDoc(doc(adminDb, `ledgers/${SELLER_A_UID}/pointers/latest_submission`)));
  await assertFails(getDoc(doc(asSellerB(), subPath)));

  // Nobody rewrites or deletes it.
  await assertFails(updateDoc(doc(sellerADb, subPath), { totalSalesToman: 1, unpaidToman: 1 }));
  await assertFails(updateDoc(doc(sellerADb, subPath), { cycleTitle: "Edited" }));
  await assertFails(deleteDoc(doc(sellerADb, subPath)));
  await assertFails(deleteDoc(doc(adminDb, subPath)));
  await assertFails(setDoc(doc(sellerADb, subPath), snapshotPayload(SELLER_A_UID, { createdAt: serverTimestamp() })));

  // Payment checkbox and settlement status.
  await assertSucceeds(updateDoc(doc(adminDb, subPath), { "paidMap.sale_1": true }));
  await assertFails(
    updateDoc(doc(sellerADb, subPath), { adminStatus: "SETTLED", adminStatusUpdatedAt: serverTimestamp() })
  );
  await assertSucceeds(updateDoc(doc(adminDb, subPath), { adminStatus: "SETTLED", adminStatusUpdatedAt: serverTimestamp() }));

  // The pointer cannot reference a report that does not exist; '' clears it after a new cycle.
  const pointerRef = doc(sellerADb, `ledgers/${SELLER_A_UID}/pointers/latest_submission`);
  await assertFails(
    setDoc(pointerRef, { ledgerOwnerUid: SELLER_A_UID, workspaceAdminUid: ADMIN_UID, submissionId: "sub_missing", updatedAt: serverTimestamp() })
  );
  await assertSucceeds(
    setDoc(pointerRef, { ledgerOwnerUid: SELLER_A_UID, workspaceAdminUid: ADMIN_UID, submissionId: "", updatedAt: serverTimestamp() })
  );
  await assertFails(deleteDoc(pointerRef));

  // The legacy overwrite-only document is read-only now.
  await assertFails(
    setDoc(doc(sellerADb, `ledgers/${SELLER_A_UID}/archives/submitted_current`), snapshotPayload(SELLER_A_UID, { cycleTitle: "Current Sent List" }))
  );
});

test("T08: no client can bootstrap or take over the workspace", async () => {
  const strangerDb = testEnv.authenticatedContext("stranger", { email: "stranger@gmail.com", email_verified: true }).firestore();
  const ts = pastTimestamp();
  await assertFails(
    setDoc(doc(strangerDb, "config/workspace"), {
      adminUid: "stranger",
      adminEmail: "",
      businessName: "Mine now",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await seedWorkspaceAndProfiles();
  await assertFails(updateDoc(doc(asSellerA(), "config/workspace"), { adminUid: SELLER_A_UID, updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(doc(asAdmin(), "config/workspace"), { adminUid: SELLER_A_UID, updatedAt: serverTimestamp() }));
  await assertFails(deleteDoc(doc(asAdmin(), "config/workspace")));
  // The admin's own ADMIN profile can still be created once the workspace exists.
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await deleteDoc(doc(context.firestore(), `users/${ADMIN_UID}`));
  });
  await assertSucceeds(
    setDoc(doc(asAdmin(), `users/${ADMIN_UID}`), profile(ADMIN_UID, "admin@nodesplit.com", "admin@nodesplit.com", "Admin", "ADMIN", "APPROVED"))
  );
  // A seller cannot create an ADMIN profile.
  await assertFails(
    setDoc(doc(asSellerB(), `users/${SELLER_B_UID}`), profile(SELLER_B_UID, "sellerb@nodesplit.com", "sellerb@nodesplit.com", "B", "ADMIN", "APPROVED"))
  );
});

test("T17: a 500-sale archive and report still accept the payment checkbox and void", async () => {
  await seedWorkspaceAndProfiles();
  const sellerADb = asSellerA();
  const items = [];
  const paidMap = {};
  for (let i = 0; i < 500; i++) {
    items.push({ itemId: `sale_${i}`, customerName: `Customer ${i}`, dataGb: 10, priceToman: 1000, isPaid: false, bonusGb: 0 });
    paidMap[`sale_${i}`] = false;
  }
  const big = { totalSalesToman: 500000, paidToman: 0, unpaidToman: 500000, totalGb: 5000, entryCount: 500, items, paidMap };

  const archiveRef = doc(sellerADb, `ledgers/${SELLER_A_UID}/archives/arch_big`);
  await assertSucceeds(setDoc(archiveRef, snapshotPayload(SELLER_A_UID, big)));
  await assertSucceeds(updateDoc(archiveRef, { "paidMap.sale_250": true, updatedAt: serverTimestamp() }));
  await assertSucceeds(
    updateDoc(archiveRef, { voided: true, voidReason: "Entered twice", voidedBy: SELLER_A_UID, voidedAt: serverTimestamp() })
  );

  const subRef = doc(sellerADb, `ledgers/${SELLER_A_UID}/submissions/sub_big`);
  await assertSucceeds(setDoc(subRef, snapshotPayload(SELLER_A_UID, { ...big, createdAt: serverTimestamp() })));
  await assertSucceeds(updateDoc(doc(asAdmin(), `ledgers/${SELLER_A_UID}/submissions/sub_big`), { "paidMap.sale_499": true }));

  // 501 sales is over the snapshot limit.
  const tooMany = [...items, { itemId: "sale_500", customerName: "Extra", dataGb: 10, priceToman: 0, isPaid: false }];
  await assertFails(
    setDoc(doc(sellerADb, `ledgers/${SELLER_A_UID}/archives/arch_501`), snapshotPayload(SELLER_A_UID, { ...big, items: tooMany, entryCount: 501 }))
  );
});
