import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { onDocumentCreated, onDocumentUpdated } from "firebase-functions/v2/firestore";
import { initializeApp } from "firebase-admin/app";
import { getMessaging } from "firebase-admin/messaging";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import type { QueryDocumentSnapshot } from "firebase-admin/firestore";
import { geohashQueryBounds, distanceBetween, geohashForLocation } from "geofire-common";

initializeApp();
const db = getFirestore();
const radii = [500, 1000, 2000, 5000];
const waitMs = 12000;

function requireAuth(request: any): string {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "تسجيل الدخول مطلوب");
  return uid;
}

async function nearbyDrivers(lat: number, lon: number, radius: number) {
  const bounds = geohashQueryBounds([lat, lon], radius);
  const result = new Map<string, QueryDocumentSnapshot>();
  for (const [startHash, endHash] of bounds) {
    const snap = await db.collection("drivers").where("approved", "==", true).where("available", "==", true)
      .orderBy("geohash").startAt(startHash).endAt(endHash).get();
    snap.docs.forEach(doc => {
      const d = doc.data();
      if (typeof d.lat !== "number" || typeof d.lon !== "number") return;
      const updated = d.updatedAt?.toMillis?.() ?? 0;
      if (Date.now() - updated > 45000) return;
      if (distanceBetween([lat, lon], [d.lat, d.lon]) * 1000 <= radius) result.set(doc.id, doc);
    });
  }
  return [...result.values()];
}

async function performRideSearch(uid: string, rideId: string) {
  const rideRef = db.collection("rides").doc(rideId);
  const rideSnap = await rideRef.get();
  if (!rideSnap.exists) throw new HttpsError("not-found", "الرحلة غير موجودة");
  const ride = rideSnap.data()!;
  if (ride.customerId !== uid) throw new HttpsError("permission-denied", "هذه الرحلة ليست لحسابك");
  if (ride.status !== "searching") return { status: ride.status };

  const lock = await db.runTransaction(async tx => {
    const latest = await tx.get(rideRef);
    const data = latest.data();
    if (!data || data.status !== "searching") return false;
    const started = data.searchWorkerStartedAt?.toMillis?.() ?? 0;
    if (data.searchWorkerActive === true && Date.now() - started < 90000) return false;
    tx.update(rideRef, { searchWorkerActive: true, searchWorkerStartedAt: FieldValue.serverTimestamp() });
    return true;
  });
  if (!lock) return { status: (await rideRef.get()).data()?.status ?? "searching" };

  const invited = new Set<string>(Array.isArray(ride.invitedDriverIds) ? ride.invitedDriverIds : []);
  for (let stage = 0; stage < radii.length; stage++) {
    const current = (await rideRef.get()).data();
    if (!current || current.status !== "searching") { await rideRef.update({ searchWorkerActive: false }); return { status: current?.status ?? "closed" }; }
    const radius = radii[stage];
    await rideRef.update({ searchRadiusMeters: radius, searchStage: stage, updatedAt: FieldValue.serverTimestamp() });
    const candidates = await nearbyDrivers(current.fromLat, current.fromLon, radius);
    const batch = db.batch(); let added = 0;
    for (const driver of candidates) {
      if (invited.has(driver.id)) continue;
      invited.add(driver.id); added++;
      batch.set(driver.ref.collection("requests").doc(rideId), {
        rideId, customerId: uid, fromAddress: current.fromAddress, toAddress: current.toAddress,
        fromLat: current.fromLat, fromLon: current.fromLon, distanceKm: current.distanceKm,
        femaleMode: current.femaleMode === true, withLuggage: current.withLuggage === true,
        radiusMeters: radius, status: "searching", createdAt: FieldValue.serverTimestamp()
      });
    }
    if (added) await batch.commit();
    await rideRef.update({ invitedDriverIds: [...invited], updatedAt: FieldValue.serverTimestamp() });
    const until = Date.now() + waitMs;
    while (Date.now() < until) {
      const latest = (await rideRef.get()).data();
      if (!latest || latest.status !== "searching") { await rideRef.update({ searchWorkerActive: false }); return { status: latest?.status ?? "closed" }; }
      const offers = await rideRef.collection("offers").limit(1).get();
      if (!offers.empty) {
        await rideRef.update({ status: "offered", searchWorkerActive: false, updatedAt: FieldValue.serverTimestamp() });
        return { status: "offered" };
      }
      await new Promise(resolve => setTimeout(resolve, 2000));
    }
  }
  const final = await rideRef.get();
  if (final.data()?.status === "searching") {
    const offers = await rideRef.collection("offers").limit(1).get();
    await rideRef.update({ status: offers.empty ? "no_drivers" : "offered", updatedAt: FieldValue.serverTimestamp() });
  }
  await rideRef.update({ searchWorkerActive: false, updatedAt: FieldValue.serverTimestamp() });
  return { status: (await rideRef.get()).data()?.status ?? "closed" };
}

export const startRideSearch = onCall({ region: "us-central1", timeoutSeconds: 70, memory: "256MiB" }, async request => {
  const uid = requireAuth(request);
  const rideId = String(request.data?.rideId ?? "");
  if (!rideId) throw new HttpsError("invalid-argument", "rideId مطلوب");
  return performRideSearch(uid, rideId);
});

export const dispatchScheduledRides = onSchedule({ schedule: "every 1 minutes", region: "us-central1", timeZone: "Africa/Cairo", memory: "512MiB", timeoutSeconds: 540, maxInstances: 1 }, async () => {
  const now = Date.now();
  const snap = await db.collection("rides")
    .where("status", "==", "scheduled")
    .where("scheduledAt", "<=", new Date(now))
    .limit(8).get();
  await Promise.all(snap.docs.map(async doc => {
    const data = doc.data();
    const claimed = await db.runTransaction(async tx => {
      const latest = await tx.get(doc.ref);
      if (latest.data()?.status !== "scheduled") return false;
      tx.update(doc.ref, { status: "searching", updatedAt: FieldValue.serverTimestamp(), searchStage: 0, searchRadiusMeters: 500 });
      return true;
    });
    if (claimed) {
      try {
        await performRideSearch(String(data.customerId), doc.id);
      } catch (error) {
        console.error("Scheduled ride search failed", doc.id, error);
      }
    }
  }));
});

async function sendToUser(uid: string | undefined, title: string, body: string, rideId: string) {
  if (!uid) return;
  const user = await db.collection("users").doc(uid).get();
  const token = user.data()?.fcmToken;
  if (typeof token !== "string" || !token) return;
  try {
    await getMessaging().send({
      token,
      notification: { title, body },
      data: { rideId, title, body }
    });
  } catch (error: any) {
    const code = error?.code ?? "";
    if (code.includes("registration-token-not-registered") || code.includes("invalid-registration-token")) {
      await user.ref.update({ fcmToken: FieldValue.delete(), updatedAt: FieldValue.serverTimestamp() });
    }
  }
}

export const notifyDriverOnRequest = onDocumentCreated("drivers/{driverId}/requests/{rideId}", async event => {
  const data = event.data?.data();
  if (!data) return;
  await sendToUser(event.params.driverId, "طلب رحلة جديد", `${data.fromAddress ?? "نقطة الركوب"} → ${data.toAddress ?? "الوجهة"}`, event.params.rideId);
});

export const notifyRideEvents = onDocumentUpdated("rides/{rideId}", async event => {
  const before = event.data?.before.data();
  const after = event.data?.after.data();
  if (!before || !after || before.status === after.status) return;
  const status = after.status;
  const messages: Record<string, string> = {
    accepted: "تم اختيار السائق لرحلتك",
    driver_arriving: "السائق بدأ التوجه إليك",
    driver_arrived: "السائق وصل إلى نقطة الركوب",
    in_progress: "بدأت الرحلة",
    completed: "انتهت الرحلة. ننتظر تقييمك",
    cancelled: "تم إلغاء الرحلة",
    no_drivers: "لم يتم العثور على توكتوك متاح",
    offered: "وصلت عروض جديدة لرحلتك"
  };
  const body = messages[status];
  if (!body) return;
  await sendToUser(after.customerId, "وصلني توكتوك", body, event.params.rideId);
  if (after.selectedDriverId) await sendToUser(after.selectedDriverId, "تحديث الرحلة", body, event.params.rideId);
});

export const heartbeatDriver = onCall({ region: "us-central1" }, async request => {
  const uid = requireAuth(request);
  const lat = Number(request.data?.lat), lon = Number(request.data?.lon);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) throw new HttpsError("invalid-argument", "إحداثيات غير صالحة");
  const ref = db.collection("drivers").doc(uid); const snap = await ref.get();
  if (!snap.exists || snap.data()?.approved !== true) throw new HttpsError("permission-denied", "السائق غير معتمد");
  if (snap.data()?.available !== true) return { available: false };
  await ref.update({ lat, lon, geohash: geohashForLocation([lat, lon]), updatedAt: FieldValue.serverTimestamp() });
  return { available: true };
});
