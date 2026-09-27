package com.wasalny.sidisalem

import com.firebase.geofire.GeoFireUtils
import com.firebase.geofire.GeoLocation
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlin.math.*

data class Coordinate(val latitude: Double, val longitude: Double)

data class RideRecord(
    val id: String,
    val customerId: String,
    val from: String,
    val to: String,
    val fromLat: Double,
    val fromLon: Double,
    val toLat: Double,
    val toLon: Double,
    val distanceKm: Double,
    val status: String,
    val searchRadiusMeters: Int,
    val selectedDriverId: String? = null,
    val createdAt: Long? = null
)

data class RideOffer(
    val driverId: String,
    val driverName: String,
    val price: Int,
    val etaMinutes: Int,
    val status: String
)

data class DriverRideRequest(
    val rideId: String,
    val from: String,
    val to: String,
    val fromLat: Double,
    val fromLon: Double,
    val distanceKm: Double,
    val radiusMeters: Int,
    val status: String
)

data class DriverCandidate(
    val uid: String,
    val displayName: String,
    val approved: Boolean,
    val available: Boolean,
    val lat: Double,
    val lon: Double,
    val updatedAt: Long?
)

class FirebaseRidesRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val rides get() = db.collection("rides")
    private val drivers get() = db.collection("drivers")

    suspend fun isAdmin(uid: String): Boolean {
        val admin = db.collection("admins").document(uid).get().await()
        return admin.exists() && admin.getString("role") == "admin" && admin.getBoolean("active") == true
    }

    fun listenPendingDrivers(
        onChange: (List<DriverCandidate>) -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration {
        return drivers.whereEqualTo("approved", false)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error)
                    return@addSnapshotListener
                }
                onChange(snapshot?.documents.orEmpty().mapNotNull { document ->
                    DriverCandidate(
                        uid = document.id,
                        displayName = document.getString("displayName") ?: "بدون اسم",
                        approved = document.getBoolean("approved") ?: false,
                        available = document.getBoolean("available") ?: false,
                        lat = document.getDouble("lat") ?: 0.0,
                        lon = document.getDouble("lon") ?: 0.0,
                        updatedAt = document.getTimestamp("updatedAt")?.toDate()?.time
                    )
                })
            }
    }

    suspend fun setDriverApproval(uid: String, approved: Boolean) {
        drivers.document(uid).update(
            mapOf(
                "approved" to approved,
                "available" to false,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    suspend fun signInAnonymously(): String {
        val auth = FirebaseAuth.getInstance()
        return auth.currentUser?.uid
            ?: auth.signInAnonymously().await().user?.uid
            ?: error("تعذر إنشاء جلسة Firebase")
    }

    suspend fun ensureDriverProfile(uid: String, name: String) {
        val ref = drivers.document(uid)
        if (!ref.get().await().exists()) {
            val initialHash = GeoFireUtils.getGeoHashForLocation(
                GeoLocation(Config.LAT, Config.LON)
            )
            ref.set(
                mapOf(
                    "uid" to uid,
                    "displayName" to name,
                    "approved" to false,
                    "available" to false,
                    "lat" to Config.LAT,
                    "lon" to Config.LON,
                    "geohash" to initialHash,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
    }

    suspend fun publishDriverLocation(uid: String, name: String, point: Coordinate): Boolean {
        val ref = drivers.document(uid)
        val snapshot = ref.get().await()
        if (!snapshot.exists()) ensureDriverProfile(uid, name)
        val current = ref.get().await()
        if (current.getBoolean("approved") != true) return false
        ref.update(
            mapOf(
                "displayName" to name,
                "available" to true,
                "lat" to point.latitude,
                "lon" to point.longitude,
                "geohash" to GeoFireUtils.getGeoHashForLocation(
                    GeoLocation(point.latitude, point.longitude)
                ),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        return true
    }

    suspend fun markDriverOffline(uid: String) {
        val ref = drivers.document(uid)
        if (ref.get().await().exists()) {
            ref.update("available", false, "updatedAt", FieldValue.serverTimestamp()).await()
        }
    }

    suspend fun createRide(
        customerId: String,
        customerName: String,
        customerPhone: String,
        fromAddress: String,
        toAddress: String,
        from: Coordinate,
        to: Coordinate,
        distanceKm: Double,
        femaleMode: Boolean,
        withLuggage: Boolean
    ): String {
        val rideRef = rides.document()
        val batch = db.batch()
        batch.set(
            rideRef,
            mapOf(
                "customerId" to customerId,
                "customerName" to customerName,
                "fromAddress" to fromAddress,
                "toAddress" to toAddress,
                "fromLat" to from.latitude,
                "fromLon" to from.longitude,
                "toLat" to to.latitude,
                "toLon" to to.longitude,
                "distanceKm" to distanceKm,
                "femaleMode" to femaleMode,
                "withLuggage" to withLuggage,
                "status" to "searching",
                "searchRadiusMeters" to 500,
                "searchStage" to 0,
                "invitedDriverIds" to emptyList<String>(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
        rideRef.collection("private").document("contact")
            .set(mapOf("customerPhone" to customerPhone)).await()
        return rideRef.id
    }

    suspend fun runSearch(
        rideId: String,
        pickup: Coordinate,
        onStage: (Int, Int) -> Unit
    ) {
        val rideRef = rides.document(rideId)
        val radii = listOf(500, 1_000, 2_000, 5_000)
        val initial = rideRef.get().await()
        if (initial.getString("status") != "searching") return
        val invited = (initial.get("invitedDriverIds") as? List<*>)
            ?.filterIsInstance<String>()?.toMutableSet() ?: mutableSetOf()
        val startStage = (initial.getLong("searchStage") ?: 0L).toInt().coerceIn(0, 3)

        for (stage in startStage..radii.lastIndex) {
            val current = rideRef.get().await()
            if (current.getString("status") != "searching") return
            val radius = radii[stage]
            rideRef.update(
                mapOf(
                    "searchRadiusMeters" to radius,
                    "searchStage" to stage,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            val deadline = System.currentTimeMillis() + 15_000
            var foundDriverInStage = false
            do {
                val stillSearching = rideRef.get().await().getString("status") == "searching"
                if (!stillSearching) return
                val nearbyDrivers = findAvailableDrivers(pickup, radius)
                val newDrivers = nearbyDrivers.filterNot { it.id in invited }
                if (newDrivers.isNotEmpty()) {
                    inviteDrivers(rideRef, newDrivers, current, radius)
                    invited.addAll(newDrivers.map { it.id })
                    foundDriverInStage = true
                }
                onStage(radius, nearbyDrivers.size)
                if (!foundDriverInStage) break
                val remaining = deadline - System.currentTimeMillis()
                if (remaining > 0) delay(min(3_000L, remaining))
            } while (System.currentTimeMillis() < deadline)
        }

        val finalRide = rideRef.get().await()
        if (finalRide.getString("status") != "searching") return
        val offers = rideRef.collection("offers").get().await()
        rideRef.update(
            mapOf(
                "status" to if (offers.isEmpty) "no_drivers" else "offered",
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    private suspend fun findAvailableDrivers(center: Coordinate, radiusMeters: Int): List<DocumentSnapshot> {
        val bounds = GeoFireUtils.getGeoHashQueryBounds(
            GeoLocation(center.latitude, center.longitude), radiusMeters.toDouble()
        )
        val matches = linkedMapOf<String, DocumentSnapshot>()
        for (bound in bounds) {
            val result = drivers
                .whereEqualTo("approved", true)
                .whereEqualTo("available", true)
                .orderBy("geohash")
                .startAt(bound.startHash)
                .endAt(bound.endHash)
                .get()
                .await()
            result.documents.forEach { doc ->
                val lat = doc.getDouble("lat") ?: return@forEach
                val lon = doc.getDouble("lon") ?: return@forEach
                val updatedAt = doc.getTimestamp("updatedAt")?.toDate()?.time ?: return@forEach
                val fresh = System.currentTimeMillis() - updatedAt <= 30_000
                if (fresh && distanceMeters(center.latitude, center.longitude, lat, lon) <= radiusMeters) {
                    matches[doc.id] = doc
                }
            }
        }
        return matches.values.toList()
    }

    private suspend fun inviteDrivers(
        rideRef: com.google.firebase.firestore.DocumentReference,
        selectedDrivers: List<DocumentSnapshot>,
        ride: DocumentSnapshot,
        radius: Int
    ) {
        val batch = db.batch()
        selectedDrivers.forEach { driver ->
            val requestRef = drivers.document(driver.id)
                .collection("requests").document(rideRef.id)
            batch.set(
                requestRef,
                mapOf(
                    "rideId" to rideRef.id,
                    "customerId" to ride.getString("customerId"),
                    "fromAddress" to ride.getString("fromAddress"),
                    "toAddress" to ride.getString("toAddress"),
                    "fromLat" to ride.getDouble("fromLat"),
                    "fromLon" to ride.getDouble("fromLon"),
                    "distanceKm" to ride.getDouble("distanceKm"),
                    "femaleMode" to ride.getBoolean("femaleMode"),
                    "withLuggage" to ride.getBoolean("withLuggage"),
                    "radiusMeters" to radius,
                    "status" to "searching",
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
        }
        batch.update(
            rideRef,
            "invitedDriverIds",
            FieldValue.arrayUnion(*selectedDrivers.map { it.id }.toTypedArray())
        )
        batch.commit().await()
    }

    suspend fun submitOffer(rideId: String, uid: String, driverName: String, price: Int, etaMinutes: Int) {
        require(price in 1..100_000) { "اكتب سعراً صحيحاً" }
        require(etaMinutes in 1..240) { "اكتب وقت وصول من دقيقة إلى 240 دقيقة" }
        val rideRef = rides.document(rideId)
        val offerRef = rideRef.collection("offers").document(uid)
        db.runTransaction { transaction ->
            val ride = transaction.get(rideRef)
            check(ride.getString("status") == "searching") { "انتهى استقبال عروض الرحلة" }
            val existing = transaction.get(offerRef)
            check(!existing.exists()) { "أرسلت عرضاً لهذه الرحلة بالفعل" }
            transaction.set(
                offerRef,
                mapOf(
                    "driverId" to uid,
                    "driverName" to driverName,
                    "price" to price,
                    "etaMinutes" to etaMinutes,
                    "status" to "pending",
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            null
        }.await()
    }

    fun listenOffers(rideId: String, onChange: (List<RideOffer>) -> Unit, onError: (Exception) -> Unit): ListenerRegistration {
        return rides.document(rideId).collection("offers")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error)
                    return@addSnapshotListener
                }
                onChange(snapshot?.documents.orEmpty().mapNotNull { doc ->
                    val price = doc.getLong("price")?.toInt() ?: return@mapNotNull null
                    RideOffer(
                        driverId = doc.getString("driverId") ?: doc.id,
                        driverName = doc.getString("driverName") ?: "سائق",
                        price = price,
                        etaMinutes = (doc.getLong("etaMinutes") ?: 0L).toInt(),
                        status = doc.getString("status") ?: "pending"
                    )
                })
            }
    }

    fun listenRide(rideId: String, onChange: (RideRecord?) -> Unit, onError: (Exception) -> Unit): ListenerRegistration {
        return rides.document(rideId).addSnapshotListener { snapshot, error ->
            if (error != null) {
                onError(error)
                return@addSnapshotListener
            }
            onChange(snapshot?.takeIf { it.exists() }?.toRideRecord())
        }
    }

    suspend fun selectOffer(rideId: String, customerId: String, driverId: String) {
        val rideRef = rides.document(rideId)
        val offerRef = rideRef.collection("offers").document(driverId)
        val requestRef = drivers.document(driverId).collection("requests").document(rideId)
        db.runTransaction { transaction ->
            val ride = transaction.get(rideRef)
            val offer = transaction.get(offerRef)
            val request = transaction.get(requestRef)
            check(ride.getString("customerId") == customerId) { "هذه الرحلة ليست لحسابك" }
            check(ride.getString("status") == "searching" || ride.getString("status") == "offered") {
                "لم تعد الرحلة متاحة للاختيار"
            }
            check(offer.getString("status") == "pending") { "هذا العرض لم يعد متاحاً" }
            check(request.getString("status") == "searching") { "دعوة السائق لم تعد متاحة" }
            transaction.update(
                rideRef,
                mapOf(
                    "status" to "accepted",
                    "selectedDriverId" to driverId,
                    "selectedOfferId" to driverId,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            transaction.update(offerRef, "status", "selected")
            transaction.update(requestRef, "status", "selected")
            null
        }.await()
    }

    suspend fun cancelRide(rideId: String, customerId: String) {
        val ref = rides.document(rideId)
        db.runTransaction { transaction ->
            val ride = transaction.get(ref)
            check(ride.getString("customerId") == customerId) { "هذه الرحلة ليست لحسابك" }
            check(ride.getString("status") == "searching" || ride.getString("status") == "offered") {
                "لا يمكن إلغاء الرحلة بعد اختيار السائق"
            }
            transaction.update(
                ref,
                mapOf("status" to "cancelled", "updatedAt" to FieldValue.serverTimestamp())
            )
            null
        }.await()
    }

    suspend fun listenCustomerRidesOnce(customerId: String): List<RideRecord> {
        return rides.whereEqualTo("customerId", customerId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(30)
            .get()
            .await()
            .documents
            .mapNotNull { it.toRideRecord() }
    }

    suspend fun getRide(rideId: String): RideRecord? =
        rides.document(rideId).get().await().takeIf { it.exists() }?.toRideRecord()

    suspend fun getAcceptedCustomerPhone(rideId: String, driverId: String): String {
        val ride = rides.document(rideId).get().await()
        check(ride.getString("status") == "accepted" && ride.getString("selectedDriverId") == driverId) {
            "بيانات التواصل تظهر للسائق الذي اختاره الراكب فقط"
        }
        return rides.document(rideId).collection("private").document("contact")
            .get().await().getString("customerPhone") ?: ""
    }

    fun listenCustomerRides(
        customerId: String,
        onChange: (List<RideRecord>) -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration {
        return rides.whereEqualTo("customerId", customerId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(30)
            .addSnapshotListener { snapshot, error ->
                if (error != null) onError(error)
                else onChange(snapshot?.documents.orEmpty().mapNotNull { it.toRideRecord() })
            }
    }

    fun listenDriverRequests(
        driverId: String,
        onChange: (List<DriverRideRequest>) -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration {
        return drivers.document(driverId).collection("requests")
            .addSnapshotListener { snapshot, error ->
                if (error != null) onError(error)
                else onChange(snapshot?.documents.orEmpty().mapNotNull { doc ->
                    DriverRideRequest(
                        rideId = doc.id,
                        from = doc.getString("fromAddress") ?: "",
                        to = doc.getString("toAddress") ?: "",
                        fromLat = doc.getDouble("fromLat") ?: return@mapNotNull null,
                        fromLon = doc.getDouble("fromLon") ?: return@mapNotNull null,
                        distanceKm = doc.getDouble("distanceKm") ?: 0.0,
                        radiusMeters = (doc.getLong("radiusMeters") ?: 500L).toInt(),
                        status = doc.getString("status") ?: "closed"
                    )
                })
            }
    }

    suspend fun getDriverApproval(uid: String): Boolean? {
        val snapshot = drivers.document(uid).get().await()
        if (!snapshot.exists()) return null
        return snapshot.getBoolean("approved") == true
    }

    private fun DocumentSnapshot.toRideRecord(): RideRecord? {
        val customerId = getString("customerId") ?: return null
        return RideRecord(
            id = id,
            customerId = customerId,
            from = getString("fromAddress") ?: "",
            to = getString("toAddress") ?: "",
            fromLat = getDouble("fromLat") ?: 0.0,
            fromLon = getDouble("fromLon") ?: 0.0,
            toLat = getDouble("toLat") ?: 0.0,
            toLon = getDouble("toLon") ?: 0.0,
            distanceKm = getDouble("distanceKm") ?: 0.0,
            status = getString("status") ?: "searching",
            searchRadiusMeters = (getLong("searchRadiusMeters") ?: 500L).toInt(),
            selectedDriverId = getString("selectedDriverId"),
            createdAt = getTimestamp("createdAt")?.toDate()?.time
        )
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
