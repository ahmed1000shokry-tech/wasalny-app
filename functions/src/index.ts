import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { onDocumentCreated, onDocumentUpdated } from "firebase-functions/v2/firestore";
import { initializeApp } from "firebase-admin/app";
import { getMessaging } from "firebase-admin/messaging";
import { getFirestore, FieldValue, Timestamp } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
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

async function requireAdmin(uid: string) {
  const admin = await db.collection("admins").doc(uid).get();
  if (admin.get("role") !== "admin" || admin.get("active") !== true) {
    throw new HttpsError("permission-denied", "حساب المشرف غير معتمد");
  }
}

function cairoDayKey(date: Date) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Africa/Cairo",
    year: "numeric",
    month: "2-digit",
    day: "2-digit"
  }).formatToParts(date);
  const part = (type: string) => parts.find(item => item.type === type)?.value ?? "00";
  return `${part("year")}-${part("month")}-${part("day")}`;
}

function subscriptionAmount(driver: FirebaseFirestore.DocumentData) {
  return driver.hasEverSubscribed === true
    || (typeof driver.subscriptionPlan === "string" && driver.subscriptionPlan !== "none")
    ? 200
    : 100;
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
      const expiry = d.subscriptionExpiresAt?.toMillis?.()
        ?? (typeof d.subscriptionExpiresAt === "number" ? d.subscriptionExpiresAt : 0);
      if (expiry <= Date.now()) return;
      const updated = d.updatedAt?.toMillis?.() ?? 0;
      if (Date.now() - updated > 45000) return;
      if (distanceBetween([lat, lon], [d.lat, d.lon]) * 1000 <= radius) result.set(doc.id, doc);
    });
  }
  return [...result.values()];
}


async function allAvailableDrivers() {
  const snap = await db.collection("drivers")
    .where("approved", "==", true)
    .where("available", "==", true)
    .get();
  return snap.docs.filter(doc => {
    const d = doc.data();
    const expiry = d.subscriptionExpiresAt?.toMillis?.()
      ?? (typeof d.subscriptionExpiresAt === "number" ? d.subscriptionExpiresAt : 0);
    const updated = d.updatedAt?.toMillis?.() ?? 0;
    return expiry > Date.now() && Date.now() - updated <= 45000;
  });
}


async function commitWrites(writes: Array<{ ref: FirebaseFirestore.DocumentReference; data: FirebaseFirestore.DocumentData }>) {
  // Firestore batches are limited to 500 writes. Keep headroom for future changes.
  const chunkSize = 450;
  for (let i = 0; i < writes.length; i += chunkSize) {
    const batch = db.batch();
    for (const write of writes.slice(i, i + chunkSize)) {
      batch.set(write.ref, write.data);
    }
    await batch.commit();
  }
}

async function broadcastScheduledRide(uid: string, rideId: string, current: FirebaseFirestore.DocumentData, invited: Set<string>) {
  const candidates = await allAvailableDrivers();
  const writes: Array<{ ref: FirebaseFirestore.DocumentReference; data: FirebaseFirestore.DocumentData }> = [];
  let added = 0;
  for (const driver of candidates) {
    if (invited.has(driver.id)) continue;
    invited.add(driver.id);
    added++;
    writes.push({
      ref: driver.ref.collection("requests").doc(rideId),
      data: {
        rideId, customerId: uid, fromAddress: current.fromAddress, toAddress: current.toAddress,
        fromLat: current.fromLat, fromLon: current.fromLon, distanceKm: current.distanceKm,
        femaleMode: current.femaleMode === true, withLuggage: current.withLuggage === true,
        radiusMeters: 0, status: "searching", createdAt: FieldValue.serverTimestamp()
      }
    });
  }
  if (added > 0) await commitWrites(writes);
  await db.collection("rides").doc(rideId).update({
    invitedDriverIds: [...invited], searchRadiusMeters: 0, searchStage: 0, updatedAt: FieldValue.serverTimestamp()
  });
  const until = Date.now() + waitMs;
  while (Date.now() < until) {
    const latest = (await db.collection("rides").doc(rideId).get()).data();
    if (!latest || latest.status !== "searching") return latest?.status ?? "closed";
    const offers = await db.collection("rides").doc(rideId).collection("offers").where("status", "==", "pending").limit(1).get();
    if (!offers.empty) {
      await db.collection("rides").doc(rideId).update({ status: "offered", searchWorkerActive: false, updatedAt: FieldValue.serverTimestamp() });
      return "offered";
    }
    await new Promise(resolve => setTimeout(resolve, 2000));
  }
  const final = await db.runTransaction(async tx => {
    const ref = db.collection("rides").doc(rideId);
    const snap = await tx.get(ref);
    if (!snap.exists) return "closed";
    if (snap.get("status") !== "searching") return String(snap.get("status") ?? "closed");
    const offers = await tx.get(ref.collection("offers").where("status", "==", "pending").limit(1));
    const status = offers.empty ? "no_drivers" : "offered";
    tx.update(ref, { status, searchWorkerActive: false, updatedAt: FieldValue.serverTimestamp() });
    return status;
  });
  return final;
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
  if (ride.bookingType === "school") {
    const current = (await rideRef.get()).data();
    if (!current || current.status !== "searching") {
      await rideRef.update({ searchWorkerActive: false, updatedAt: FieldValue.serverTimestamp() });
      return { status: current?.status ?? "closed" };
    }
    const status = await broadcastScheduledRide(uid, rideId, current, invited);
    return { status };
  }
  for (let stage = 0; stage < radii.length; stage++) {
    const current = (await rideRef.get()).data();
    if (!current || current.status !== "searching") { await rideRef.update({ searchWorkerActive: false }); return { status: current?.status ?? "closed" }; }
    const radius = radii[stage];
    await rideRef.update({ searchRadiusMeters: radius, searchStage: stage, updatedAt: FieldValue.serverTimestamp() });
    const candidates = await nearbyDrivers(current.fromLat, current.fromLon, radius);
    const writes: Array<{ ref: FirebaseFirestore.DocumentReference; data: FirebaseFirestore.DocumentData }> = [];
    let added = 0;
    for (const driver of candidates) {
      if (invited.has(driver.id)) continue;
      invited.add(driver.id); added++;
      writes.push({
        ref: driver.ref.collection("requests").doc(rideId),
        data: {
          rideId, customerId: uid, fromAddress: current.fromAddress, toAddress: current.toAddress,
          fromLat: current.fromLat, fromLon: current.fromLon, distanceKm: current.distanceKm,
          femaleMode: current.femaleMode === true, withLuggage: current.withLuggage === true,
          radiusMeters: radius, status: "searching", createdAt: FieldValue.serverTimestamp()
        }
      });
    }
    if (added) await commitWrites(writes);
    await rideRef.update({ invitedDriverIds: [...invited], updatedAt: FieldValue.serverTimestamp() });
    const until = Date.now() + waitMs;
    while (Date.now() < until) {
      const latest = (await rideRef.get()).data();
      if (!latest || latest.status !== "searching") { await rideRef.update({ searchWorkerActive: false }); return { status: latest?.status ?? "closed" }; }
      const offers = await rideRef.collection("offers").where("status", "==", "pending").limit(5).get();
      if (offers.size >= 5) break;
      await new Promise(resolve => setTimeout(resolve, 2000));
    }

    const stageStatus = await db.runTransaction(async tx => {
      const latest = await tx.get(rideRef);
      if (!latest.exists) return "closed";
      const latestStatus = latest.get("status");
      if (latestStatus !== "searching") {
        tx.update(rideRef, { searchWorkerActive: false, updatedAt: FieldValue.serverTimestamp() });
        return String(latestStatus ?? "closed");
      }
      const offers = await tx.get(rideRef.collection("offers").where("status", "==", "pending").limit(1));
      if (!offers.empty) {
        tx.update(rideRef, { status: "offered", searchWorkerActive: false, updatedAt: FieldValue.serverTimestamp() });
        return "offered";
      }
      return "searching";
    });
    if (stageStatus !== "searching") return { status: stageStatus };
  }
  const finalStatus = await db.runTransaction(async tx => {
    const final = await tx.get(rideRef);
    if (!final.exists) return "closed";
    const status = final.get("status");
    if (status !== "searching") {
      tx.update(rideRef, { searchWorkerActive: false, updatedAt: FieldValue.serverTimestamp() });
      return String(status ?? "closed");
    }
    const offers = await tx.get(rideRef.collection("offers").where("status", "==", "pending").limit(1));
    const nextStatus = offers.empty ? "no_drivers" : "offered";
    tx.update(rideRef, { status: nextStatus, searchWorkerActive: false, updatedAt: FieldValue.serverTimestamp() });
    return nextStatus;
  });
  return { status: finalStatus };
}

export const startRideSearch = onCall({ region: "us-central1", timeoutSeconds: 70, memory: "256MiB" }, async request => {
  const uid = requireAuth(request);
  const rideId = String(request.data?.rideId ?? "");
  if (!rideId) throw new HttpsError("invalid-argument", "rideId مطلوب");
  return performRideSearch(uid, rideId);
});

// Server-side safety net: an immediate ride still starts searching if the
// customer's app closes immediately after creating it. The transaction lock
// inside performRideSearch prevents duplicate search workers.
export const autoStartImmediateRideSearch = onDocumentCreated(
  { document: "rides/{rideId}", region: "us-central1", retry: true },
  async event => {
    const data = event.data?.data();
    if (!data || data.bookingType !== "now" || data.status !== "searching") return;
    await performRideSearch(String(data.customerId), event.params.rideId);
  }
);

export const submitSubscriptionRequest = onCall({ region: "us-central1" }, async request => {
  const uid = requireAuth(request);
  const requestId = String(request.data?.requestId ?? "");
  const proofPath = String(request.data?.proofPath ?? "");
  const note = String(request.data?.note ?? "").trim().slice(0, 300);
  if (!/^[A-Za-z0-9]{20}$/.test(requestId) || proofPath !== `subscriptionProofs/${uid}/${requestId}`) {
    throw new HttpsError("invalid-argument", "بيانات إثبات التحويل غير صالحة");
  }

  const proof = getStorage().bucket().file(proofPath);
  const [exists] = await proof.exists();
  if (!exists) throw new HttpsError("failed-precondition", "ارفع صورة إثبات التحويل أولاً");
  const [metadata] = await proof.getMetadata();
  const size = Number(metadata.size ?? 0);
  if (!/^image\/(jpeg|png|webp)$/.test(String(metadata.contentType ?? "")) || size <= 0 || size > 5 * 1024 * 1024) {
    throw new HttpsError("invalid-argument", "الصورة يجب أن تكون JPG أو PNG أو WEBP وبحجم لا يتجاوز 5 ميجابايت");
  }

  const driverRef = db.collection("drivers").doc(uid);
  const requestRef = db.collection("subscriptionRequests").doc(requestId);
  const pendingQuery = db.collection("subscriptionRequests")
    .where("driverId", "==", uid).where("status", "==", "pending").limit(1);
  return db.runTransaction(async tx => {
    const [driverSnap, requestSnap, pendingSnap] = await Promise.all([
      tx.get(driverRef), tx.get(requestRef), tx.get(pendingQuery)
    ]);
    if (!driverSnap.exists || driverSnap.get("approved") !== true) {
      throw new HttpsError("failed-precondition", "يجب اعتماد حساب السائق قبل طلب الاشتراك");
    }
    if (requestSnap.exists || !pendingSnap.empty) {
      throw new HttpsError("already-exists", "لديك طلب اشتراك قيد المراجعة بالفعل");
    }
    const amount = subscriptionAmount(driverSnap.data()!);
    tx.create(requestRef, {
      driverId: uid,
      driverName: driverSnap.get("displayName") ?? "سائق",
      amount,
      months: 1,
      proofPath,
      note,
      status: "pending",
      createdAt: FieldValue.serverTimestamp()
    });
    return { requestId, amount };
  });
});

export const reviewSubscriptionRequest = onCall({ region: "us-central1" }, async request => {
  const adminUid = requireAuth(request);
  await requireAdmin(adminUid);
  const requestId = String(request.data?.requestId ?? "");
  const decision = String(request.data?.decision ?? "");
  if (!/^[A-Za-z0-9]{20}$/.test(requestId) || !["approve", "reject"].includes(decision)) {
    throw new HttpsError("invalid-argument", "بيانات المراجعة غير صالحة");
  }

  const subscriptionRef = db.collection("subscriptionRequests").doc(requestId);
  return db.runTransaction(async tx => {
    const subscriptionSnap = await tx.get(subscriptionRef);
    if (!subscriptionSnap.exists || subscriptionSnap.get("status") !== "pending") {
      throw new HttpsError("failed-precondition", "طلب الاشتراك لم يعد قيد المراجعة");
    }
    const driverRef = db.collection("drivers").doc(String(subscriptionSnap.get("driverId") ?? ""));
    const driverSnap = await tx.get(driverRef);
    if (!driverSnap.exists || driverSnap.get("approved") !== true) {
      throw new HttpsError("failed-precondition", "حساب السائق غير موجود أو غير معتمد");
    }
    if (decision === "reject") {
      tx.update(subscriptionRef, {
        status: "rejected",
        reviewedBy: adminUid,
        updatedAt: FieldValue.serverTimestamp()
      });
      return { status: "rejected" };
    }

    const driver = driverSnap.data()!;
    if (subscriptionSnap.get("months") !== 1 || subscriptionSnap.get("amount") !== subscriptionAmount(driver)) {
      throw new HttpsError("failed-precondition", "قيمة الاشتراك لا تطابق الخطة الحالية");
    }
    const now = Date.now();
    const currentExpiry = driver.subscriptionExpiresAt instanceof Timestamp
      ? driver.subscriptionExpiresAt.toMillis()
      : typeof driver.subscriptionExpiresAt === "number" ? driver.subscriptionExpiresAt : 0;
    const isRenewal = subscriptionAmount(driver) === 200;
    const expiresAt = Timestamp.fromMillis(Math.max(now, currentExpiry) + 30 * 24 * 60 * 60 * 1000);
    tx.update(driverRef, {
      subscriptionExpiresAt: expiresAt,
      subscriptionPlan: isRenewal ? "monthly" : "first",
      hasEverSubscribed: true,
      available: false,
      updatedAt: FieldValue.serverTimestamp()
    });
    tx.update(subscriptionRef, {
      status: "approved",
      reviewedBy: adminUid,
      updatedAt: FieldValue.serverTimestamp()
    });
    return { status: "approved", expiresAt: expiresAt.toMillis() };
  });
});

export const cancelCustomerRide = onCall({ region: "us-central1" }, async request => {
  const uid = requireAuth(request);
  const rideId = String(request.data?.rideId ?? "");
  if (!rideId) throw new HttpsError("invalid-argument", "rideId مطلوب");
  const now = Date.now();
  const dayKey = cairoDayKey(new Date(now));
  const rideRef = db.collection("rides").doc(rideId);
  const userRef = db.collection("users").doc(uid);
  const cancelRef = userRef.collection("cancels").doc(dayKey);

  return db.runTransaction(async tx => {
    const [rideSnap, userSnap, cancelSnap] = await Promise.all([
      tx.get(rideRef), tx.get(userRef), tx.get(cancelRef)
    ]);
    if (!rideSnap.exists || rideSnap.get("customerId") !== uid) {
      throw new HttpsError("permission-denied", "هذه الرحلة ليست لحسابك");
    }
    if (rideSnap.get("status") === "cancelled") {
      return { status: "cancelled", count: cancelSnap.get("count") ?? 0 };
    }
    const allowedStatuses = ["searching", "scheduled", "offered", "accepted", "driver_arriving", "driver_arrived", "in_progress"];
    if (!allowedStatuses.includes(String(rideSnap.get("status")))) {
      throw new HttpsError("failed-precondition", "لا يمكن إلغاء الرحلة في حالتها الحالية");
    }
    const banUntil = userSnap.get("banUntil");
    const banUntilMillis = banUntil instanceof Timestamp
      ? banUntil.toMillis()
      : typeof banUntil === "number" ? banUntil : 0;
    if (banUntilMillis > now) {
      throw new HttpsError("permission-denied", "حسابك موقوف مؤقتًا بسبب تكرار إلغاء الرحلات");
    }

    const selectedDriverId = rideSnap.get("selectedDriverId");
    const selectedDriverRef = typeof selectedDriverId === "string"
      ? db.collection("drivers").doc(selectedDriverId)
      : null;
    const selectedDriverSnap = selectedDriverRef ? await tx.get(selectedDriverRef) : null;
    const count = (cancelSnap.get("count") ?? 0) + 1;
    tx.update(rideRef, { status: "cancelled", updatedAt: FieldValue.serverTimestamp() });
    if (selectedDriverRef && selectedDriverSnap?.exists) {
      tx.update(selectedDriverRef, { available: false, updatedAt: FieldValue.serverTimestamp() });
    }
    tx.set(cancelRef, { count, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    if (count > 3) {
      tx.set(userRef, {
        banUntil: Timestamp.fromMillis(now + 24 * 60 * 60 * 1000),
        updatedAt: FieldValue.serverTimestamp()
      }, { merge: true });
    }
    return { status: "cancelled", count, banned: count > 3 };
  });
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
      tx.update(doc.ref, { status: "searching", updatedAt: FieldValue.serverTimestamp(), searchStage: 0, searchRadiusMeters: 0 });
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
  const ride = await db.collection("rides").doc(event.params.rideId).get();
  if (ride.data()?.status !== "searching") {
    const rideData = ride.data();
    const isSelectedDriver = rideData?.selectedDriverId === event.params.driverId;
    const stillActive = ["accepted", "driver_arriving", "driver_arrived", "in_progress"].includes(String(rideData?.status));
    await event.data?.ref.update({
      status: isSelectedDriver && stillActive ? "selected" : isSelectedDriver ? "closed" : "taken_by_other",
      updatedAt: FieldValue.serverTimestamp()
    });
    return;
  }
  await sendToUser(event.params.driverId, "طلب رحلة جديد", `${data.fromAddress ?? "نقطة الركوب"} → ${data.toAddress ?? "الوجهة"}`, event.params.rideId);
});

export const notifyRideEvents = onDocumentUpdated("rides/{rideId}", async event => {
  const before = event.data?.before.data();
  const after = event.data?.after.data();
  if (!before || !after || before.status === after.status) return;
  const status = after.status;
  if (status === "accepted" && typeof after.selectedDriverId === "string" && Array.isArray(after.invitedDriverIds)) {
    const selectedDriverId = after.selectedDriverId as string;
    const invitedDriverIds = [...new Set<string>(
      (after.invitedDriverIds as unknown[]).filter((id): id is string => typeof id === "string" && id.length > 0)
    )];
    const otherDriverIds = invitedDriverIds.filter(id => id !== selectedDriverId);
    const rideRef = db.collection("rides").doc(event.params.rideId);
    const selectedDriverRef = db.collection("drivers").doc(selectedDriverId);
    await db.runTransaction(async tx => {
      const [currentRide, selectedDriver] = await Promise.all([
        tx.get(rideRef), tx.get(selectedDriverRef)
      ]);
      const currentStatus = currentRide.get("status");
      if (selectedDriver.exists
        && currentRide.get("selectedDriverId") === selectedDriverId
        && ["accepted", "driver_arriving", "driver_arrived", "in_progress"].includes(String(currentStatus))) {
        tx.update(selectedDriverRef, { available: false, updatedAt: FieldValue.serverTimestamp() });
      }
    });

    for (let offset = 0; offset < otherDriverIds.length; offset += 400) {
      const batch = db.batch();
      for (const driverId of otherDriverIds.slice(offset, offset + 400)) {
        batch.set(
          db.collection("drivers").doc(driverId).collection("requests").doc(event.params.rideId),
          { status: "taken_by_other", updatedAt: FieldValue.serverTimestamp() },
          { merge: true }
        );
      }
      await batch.commit();
    }

    const pendingOffers = await rideRef.collection("offers").where("status", "==", "pending").get();
    for (let offset = 0; offset < pendingOffers.docs.length; offset += 400) {
      const batch = db.batch();
      pendingOffers.docs.slice(offset, offset + 400)
        .filter(offer => offer.id !== selectedDriverId)
        .forEach(offer => batch.update(offer.ref, {
          status: "rejected",
          updatedAt: FieldValue.serverTimestamp()
        }));
      await batch.commit();
    }

    for (let offset = 0; offset < otherDriverIds.length; offset += 50) {
      await Promise.all(otherDriverIds.slice(offset, offset + 50).map(driverId =>
        sendToUser(driverId, "تم قبول الطلب", "اختار الراكب سائقاً آخر لهذه الرحلة", event.params.rideId)
      ));
    }
  }

  const messages: Record<string, string> = {
    accepted: "تم اختيار السائق لرحلتك",
    driver_arriving: "السائق بدأ التوجه إليك",
    driver_arrived: "السائق وصل إلى نقطة الركوب",
    in_progress: "بدأت الرحلة، رحلة سعيدة وآمنة بإذن الله",
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
  const lat = request.data?.lat, lon = request.data?.lon;
  if (typeof lat !== "number" || typeof lon !== "number"
    || !Number.isFinite(lat) || !Number.isFinite(lon)
    || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
    throw new HttpsError("invalid-argument", "إحداثيات غير صالحة");
  }
  const ref = db.collection("drivers").doc(uid); const snap = await ref.get();
  if (!snap.exists || snap.data()?.approved !== true) throw new HttpsError("permission-denied", "السائق غير معتمد");
  if (snap.data()?.available !== true) return { available: false };
  const expiry = snap.data()?.subscriptionExpiresAt;
  const expiryMillis = expiry?.toMillis?.() ?? (typeof expiry === "number" ? expiry : 0);
  if (expiryMillis <= Date.now()) {
    await ref.update({ available: false, updatedAt: FieldValue.serverTimestamp() });
    throw new HttpsError("failed-precondition", "انتهى الاشتراك الشهري للسائق");
  }
  await ref.update({ lat, lon, geohash: geohashForLocation([lat, lon]), updatedAt: FieldValue.serverTimestamp() });
  return { available: true };
});
