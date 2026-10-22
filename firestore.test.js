const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
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

async function seedWorkspaceAndProfiles() {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    const now = pastTimestamp();
    await db.collection("config").doc("workspace").set({
      adminUid: ADMIN_UID,
      adminEmail: "",
      businessName: "NodeSplit VPN",
      costPerGbToman: 1200,
      adminProfitPercent: 50,
      settingsRevision: 1,
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("users").doc(ADMIN_UID).set({
      userId: ADMIN_UID,
      workspaceAdminUid: ADMIN_UID,
      email: "admin@nodesplit.com",
      emailKey: "admin_at_nodesplit_dot_com",
      displayName: "Admin",
      role: "ADMIN",
      status: "APPROVED",
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("users").doc(SELLER_A_UID).set({
      userId: SELLER_A_UID,
      workspaceAdminUid: ADMIN_UID,
      email: "sellera@nodesplit.com",
      emailKey: "sellera_at_nodesplit_dot_com",
      displayName: "Seller A",
      role: "SELLER",
      status: "APPROVED",
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("users").doc(SELLER_B_UID).set({
      userId: SELLER_B_UID,
      workspaceAdminUid: ADMIN_UID,
      email: "sellerb@nodesplit.com",
      emailKey: "sellerb_at_nodesplit_dot_com",
      displayName: "Seller B",
      role: "SELLER",
      status: "APPROVED",
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("users").doc(PENDING_SELLER_UID).set({
      userId: PENDING_SELLER_UID,
      workspaceAdminUid: ADMIN_UID,
      email: "pending@nodesplit.com",
      emailKey: "pending_at_nodesplit_dot_com",
      displayName: "Pending Seller",
      role: "SELLER",
      status: "PENDING",
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("users").doc(REVOKED_SELLER_UID).set({
      userId: REVOKED_SELLER_UID,
      workspaceAdminUid: ADMIN_UID,
      email: "revoked@nodesplit.com",
      emailKey: "revoked_at_nodesplit_dot_com",
      displayName: "Revoked Seller",
      role: "SELLER",
      status: "REVOKED",
      createdAt: now,
      updatedAt: now,
    });
  });
}

test("T01: Signed-out, no-profile, PENDING, and REVOKED callers are denied on protected ledger paths", async () => {
  await seedWorkspaceAndProfiles();
  const ts = pastTimestamp();

  const validSalePayload = (uid) => ({
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
  });

  // 1. Unauthenticated
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("config").doc("workspace").get());
  await assertFails(unauthDb.collection("ledgers").doc(SELLER_A_UID).collection("entries").get());

  // 2. No profile
  const noProfileDb = testEnv.authenticatedContext(NO_PROFILE_UID).firestore();
  await assertFails(
    noProfileDb.collection("ledgers").doc(NO_PROFILE_UID).collection("entries").doc("s1").set(validSalePayload(NO_PROFILE_UID))
  );

  // 3. PENDING profile
  const pendingDb = testEnv.authenticatedContext(PENDING_SELLER_UID).firestore();
  await assertFails(
    pendingDb.collection("ledgers").doc(PENDING_SELLER_UID).collection("entries").doc("s1").set(validSalePayload(PENDING_SELLER_UID))
  );
  // Pending user can still read their own profile status
  await assertSucceeds(pendingDb.collection("users").doc(PENDING_SELLER_UID).get());

  // 4. REVOKED profile
  const revokedDb = testEnv.authenticatedContext(REVOKED_SELLER_UID).firestore();
  await assertFails(
    revokedDb.collection("ledgers").doc(REVOKED_SELLER_UID).collection("entries").doc("s1").set(validSalePayload(REVOKED_SELLER_UID))
  );
});

test("T02 & T03: Invitation token email binding and REVOKED cannot self-reverse to APPROVED", async () => {
  await seedWorkspaceAndProfiles();
  const ts = pastTimestamp();

  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("approved_emails").doc("invited_at_nodesplit_dot_com").set({
      emailKey: "invited_at_nodesplit_dot_com",
      email: "invited@nodesplit.com",
      sellerName: "Invited Seller",
      workspaceAdminUid: ADMIN_UID,
      status: "APPROVED",
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("approved_emails").doc("revoked_at_nodesplit_dot_com").set({
      emailKey: "revoked_at_nodesplit_dot_com",
      email: "revoked@nodesplit.com",
      sellerName: "Revoked Seller",
      workspaceAdminUid: ADMIN_UID,
      status: "APPROVED",
      createdAt: ts,
      updatedAt: ts,
    });
  });

  // Attacker with different token email cannot read or claim another user's approved_emails entry
  const attackerDb = testEnv.authenticatedContext("attacker_uid", { email: "attacker@evil.com" }).firestore();
  await assertFails(attackerDb.collection("approved_emails").doc("invited_at_nodesplit_dot_com").get());
  await assertFails(
    attackerDb.collection("users").doc("attacker_uid").set({
      userId: "attacker_uid",
      workspaceAdminUid: ADMIN_UID,
      email: "invited@nodesplit.com",
      emailKey: "invited_at_nodesplit_dot_com",
      displayName: "Impersonator",
      role: "SELLER",
      status: "APPROVED",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // T03: REVOKED seller cannot self-upgrade back to APPROVED even if an approved_emails doc exists
  const revokedDb = testEnv.authenticatedContext(REVOKED_SELLER_UID, { email: "revoked@nodesplit.com" }).firestore();
  await assertFails(
    revokedDb.collection("users").doc(REVOKED_SELLER_UID).update({
      status: "APPROVED",
      updatedAt: pastTimestamp(),
    })
  );
});

test("T04: Approved Seller can manage own sales, but cannot access other books or edit Admin settings/presets", async () => {
  await seedWorkspaceAndProfiles();
  const sellerADb = testEnv.authenticatedContext(SELLER_A_UID).firestore();
  const entryRef = sellerADb.collection("ledgers").doc(SELLER_A_UID).collection("entries").doc("sale_1");
  const ts = pastTimestamp();

  await assertSucceeds(
    entryRef.set({
      ledgerOwnerUid: SELLER_A_UID,
      workspaceAdminUid: ADMIN_UID,
      customerName: "Ali Rezaei",
      dataGb: 30,
      priceToman: 135000,
      originalPresetPriceToman: 135000,
      presetName: "25 GB Standard",
      isPaid: false,
      isNewUser: true,
      bonusGb: 5,
      note: "New user +5 GB bonus",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Seller cannot modify global workspace rates
  await assertFails(
    sellerADb.collection("config").doc("workspace").update({
      costPerGbToman: 0,
      updatedAt: pastTimestamp(),
    })
  );

  // Seller cannot modify shared presets
  await assertFails(
    sellerADb.collection("ledgers").doc(ADMIN_UID).collection("presets").doc("builtin_10").set({
      ledgerOwnerUid: ADMIN_UID,
      workspaceAdminUid: ADMIN_UID,
      label: "Hacked 10 GB",
      dataGb: 10,
      defaultPriceToman: 1000,
      newUserBonusGb: 50,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Seller A cannot read Seller B or Admin private entries
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("ledgers").doc(ADMIN_UID).collection("entries").doc("admin_private_1").set({
      ledgerOwnerUid: ADMIN_UID,
      workspaceAdminUid: ADMIN_UID,
      customerName: "VIP Client",
      dataGb: 500,
      priceToman: 1500000,
      originalPresetPriceToman: 1500000,
      presetName: "500 GB VIP",
      isPaid: true,
      note: "Private admin book",
      createdAt: ts,
      updatedAt: ts,
    });
  });

  await assertFails(
    sellerADb.collection("ledgers").doc(ADMIN_UID).collection("entries").doc("admin_private_1").get()
  );
});

test("T05 & T06: Finalized archive allows payment-only updates but rejects financial tampering or inconsistent totals", async () => {
  await seedWorkspaceAndProfiles();
  const sellerADb = testEnv.authenticatedContext(SELLER_A_UID).firestore();
  const archiveRef = sellerADb.collection("ledgers").doc(SELLER_A_UID).collection("archives").doc("arch_1");
  const ts = pastTimestamp();

  // Reject archive where paidToman + unpaidToman != totalSalesToman
  await assertFails(
    archiveRef.set({
      ledgerOwnerUid: SELLER_A_UID,
      workspaceAdminUid: ADMIN_UID,
      ownerName: "Seller A",
      cycleTitle: "Cycle 1",
      totalSalesToman: 135000,
      paidToman: 100000,
      unpaidToman: 100000,
      totalGb: 30,
      costPerGbToman: 1200,
      adminProfitPercent: 50,
      entryCount: 1,
      items: [{ customerName: "Ali Rezaei", dataGb: 30, priceToman: 135000, isPaid: false }],
      createdAt: ts,
    })
  );

  // Create valid finalized archive
  await assertSucceeds(
    archiveRef.set({
      ledgerOwnerUid: SELLER_A_UID,
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
      createdAt: ts,
    })
  );

  // Allowed payment-only update succeeds
  await assertSucceeds(
    archiveRef.update({
      paidToman: 135000,
      unpaidToman: 0,
      items: [{ itemId: "sale_1", customerName: "Ali Rezaei", dataGb: 30, priceToman: 135000, isPaid: true }],
      paidMap: { sale_1: true },
    })
  );

  // Attempt to tamper with totalSalesToman or costPerGbToman on finalized archive is denied
  await assertFails(
    archiveRef.update({
      totalSalesToman: 50000,
      paidToman: 50000,
      unpaidToman: 0,
    })
  );
  await assertFails(
    archiveRef.update({
      costPerGbToman: 0,
    })
  );
});
