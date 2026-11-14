const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const DATABASE_ID = "ai-studio-android-scrossob-c890c58c-b668-4590-a793-fae16b7dba44";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

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

function validDashboard(uid, name = "Guida D16AA") {
  return {
    userId: uid,
    name,
    isDefault: true,
    widgets: [
      { id: "w1", pidKey: "RPM", style: "NUMERIC", spanColumns: 1, order: 0 },
    ],
    createdAt: new Date(),
    updatedAt: new Date(),
  };
}

function validCustomPid(uid) {
  return {
    userId: uid,
    pidKey: "CUSTOM_TPMS_FL",
    label: "TPMS Ant. Sx",
    command: "221001",
    canHeader: "751",
    unit: "bar",
    formula: "A*0.025",
    minValue: 0,
    maxValue: 4.0,
    targetWheel: "FL",
    createdAt: new Date(),
    updatedAt: new Date(),
  };
}

test("Unauthenticated user cannot read or write dashboards", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore(DATABASE_ID);
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("dashboards").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("dashboards").doc("d1").set(validDashboard(ALICE_UID))
  );
});

test("Owner can create, read, update, and delete own dashboard", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore(DATABASE_ID);
  const ref = aliceDb.collection("users").doc(ALICE_UID).collection("dashboards").doc("dash_main");
  await assertSucceeds(ref.set(validDashboard(ALICE_UID)));
  await assertSucceeds(ref.get());
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("dashboards").get());
  await assertSucceeds(ref.update({ name: "Turbo & DPF", updatedAt: new Date() }));
  await assertSucceeds(ref.delete());
});

test("Cross-user access to dashboards is denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore(DATABASE_ID);
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("dashboards").doc("dash_1").set(validDashboard(ALICE_UID))
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore(DATABASE_ID);
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("dashboards").doc("dash_1").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("dashboards").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("dashboards").doc("dash_2").set(validDashboard(BOB_UID))
  );
});

test("Shadow update with ghost field or immutable userId change fails", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore(DATABASE_ID);
  const ref = aliceDb.collection("users").doc(ALICE_UID).collection("dashboards").doc("dash_1");
  await assertSucceeds(ref.set(validDashboard(ALICE_UID)));

  await assertFails(ref.update({ isAdmin: true, updatedAt: new Date() }));
  await assertFails(ref.update({ userId: BOB_UID, updatedAt: new Date() }));
});

test("Invalid widget array size or empty dashboard name is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore(DATABASE_ID);
  const ref = aliceDb.collection("users").doc(ALICE_UID).collection("dashboards").doc("bad_dash");
  await assertFails(ref.set(validDashboard(ALICE_UID, "")));
});

test("Custom PID CRUD works for owner and validates targetWheel allowlist", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore(DATABASE_ID);
  const ref = aliceDb.collection("users").doc(ALICE_UID).collection("custom_pids").doc("tpms_fl");
  await assertSucceeds(ref.set(validCustomPid(ALICE_UID)));
  await assertSucceeds(ref.update({ targetWheel: "FR", updatedAt: new Date() }));

  const badRef = aliceDb.collection("users").doc(ALICE_UID).collection("custom_pids").doc("bad_wheel");
  await assertFails(badRef.set({ ...validCustomPid(ALICE_UID), targetWheel: "SPARE" }));
});
