const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
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

test("Unauthenticated user: cannot read safety status", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("safety_status").get());
});

test("Protected user: can create their own user profile", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: "alice@safety.org",
      displayName: "Alice",
      role: "protected",
      consentGiven: true,
      familyCode: "FAM123",
      createdAt: new Date(),
    })
  );
});

test("Protected user: cannot create user profile for another UID", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).set({
      userId: BOB_UID,
      email: "bob@safety.org",
      displayName: "Bob",
      role: "protected",
      consentGiven: true,
      familyCode: "FAM123",
      createdAt: new Date(),
    })
  );
});

test("Protected user: can update own safety status", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("safety_status").doc(ALICE_UID).set({
      userId: ALICE_UID,
      familyCode: "FAM123",
      displayName: "Alice",
      sharingActive: true,
      latitude: 23.8103,
      longitude: 90.4125,
      accuracy: 10.5,
      batteryLevel: 85,
      isCharging: false,
      activityState: "WALKING",
      stepCount: 2300,
      lastUpdated: new Date(),
      createdAt: new Date(),
    })
  );
});

test("Guardian: can list safety status for same familyCode", async () => {
  // Seed Alice status
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("safety_status").doc(ALICE_UID).set({
      userId: ALICE_UID,
      familyCode: "FAM123",
      displayName: "Alice",
      sharingActive: true,
      latitude: 23.8103,
      longitude: 90.4125,
      batteryLevel: 85,
      activityState: "WALKING",
      lastUpdated: new Date(),
      createdAt: new Date(),
    });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertSucceeds(
    bobDb.collection("safety_status").where("familyCode", "==", "FAM123").get()
  );
});

test("Protected user: can trigger SOS alert and Guardian can read it", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("sos_alerts").doc("alert_1").set({
      userId: ALICE_UID,
      displayName: "Alice",
      familyCode: "FAM123",
      latitude: 23.8103,
      longitude: 90.4125,
      batteryLevel: 22,
      status: "ACTIVE",
      message: "Emergency! I need help at this location.",
      createdAt: new Date(),
    })
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertSucceeds(
    bobDb.collection("sos_alerts").where("familyCode", "==", "FAM123").get()
  );
});
