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

function validTripLog(uid, status = "COMPLETED") {
  const now = new Date(Date.now() - 1000);
  return {
    userId: uid,
    title: "Casa -> Lavoro",
    startedAtMillis: 1732360000000,
    endedAtMillis: 1732361500000,
    durationSec: 1500,
    sampleCount: 750,
    distanceKm: 18.4,
    avgSpeedKmh: 44.2,
    maxSpeedKmh: 92.0,
    maxRpm: 3100,
    maxCoolantC: 90.0,
    maxDpfTempC: 410.0,
    minBatteryV: 13.8,
    maxBatteryV: 14.5,
    avgBatteryV: 14.2,
    maxTurboKpa: 215.0,
    estimatedFuelLiters: 1.05,
    avgLitersPer100Km: 5.7,
    avgLitersPerHour: 2.5,
    trafficSeconds: 210,
    dpfRegenDetected: false,
    dpfRegenSeconds: 0,
    csvFileName: "trip_20261123_080000.csv",
    status,
    demoMode: false,
    timelinePoints: [
      { elapsedSec: 0, speedKmh: 0, rpm: 820, coolantC: 42, dpfTempC: 180, batteryV: 14.2, fuelLph: 0.7 },
      { elapsedSec: 600, speedKmh: 72, rpm: 2100, coolantC: 88, dpfTempC: 390, batteryV: 14.3, fuelLph: 4.1 },
    ],
    createdAt: now,
    updatedAt: now,
  };
}

test("Trip logs: unauthenticated and cross-user access denied; owner CRUD & partial update succeed", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore(DATABASE_ID);
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("trip_logs").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("trip_logs").doc("trip_1").set(validTripLog(ALICE_UID))
  );

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore(DATABASE_ID);
  const tripRef = aliceDb.collection("users").doc(ALICE_UID).collection("trip_logs").doc("trip_1");
  await assertSucceeds(tripRef.set(validTripLog(ALICE_UID, "RECORDING")));
  await assertSucceeds(
    tripRef.update({
      title: "Casa -> Lavoro (Mattina)",
      status: "COMPLETED",
      endedAtMillis: 1732361800000,
      durationSec: 1800,
      sampleCount: 900,
      estimatedFuelLiters: 1.18,
      avgLitersPer100Km: 5.9,
      trafficSeconds: 260,
      dpfRegenDetected: true,
      dpfRegenSeconds: 180,
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("trip_logs").get());

  // Cross-user isolation
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore(DATABASE_ID);
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("trip_logs").doc("trip_1").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("trip_logs").get());

  // Reject invalid status, immutable field mutation, negative fuel, and future timestamp
  await assertFails(tripRef.update({ csvFileName: "hijack.csv", updatedAt: new Date(Date.now() - 500) }));
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("trip_logs").doc("trip_bad_status").set(validTripLog(ALICE_UID, "UNKNOWN"))
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("trip_logs").doc("trip_neg_fuel").set({
      ...validTripLog(ALICE_UID),
      estimatedFuelLiters: -0.5,
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("trip_logs").doc("trip_future").set({
      ...validTripLog(ALICE_UID),
      createdAt: new Date(Date.now() + 60000),
    })
  );

  await assertSucceeds(tripRef.delete());
});
