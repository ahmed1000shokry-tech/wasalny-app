package com.wasalny.sidisalem

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

/**
 * Single source of truth for ride state and Firebase operations.
 * Search/matching is performed by the trusted Cloud Function; the client only observes state.
 */
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
    val selectedPrice: Int? = null,
    val driverLat: Double? = null,
    val driverLon: Double? = null,
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

data class DriverApplication(
    val uid: String,
    val name: String,
    val phone: String,
    val licenseType: String,
    val vehicleType: String,
    val idCardImageUrl: String = "",
    val vehicleImageUrl: String = "",
    val approved: Boolean = false,
    val createdAt: Long? = null,
    val updatedAt: Long? = null
)

data class DriverLiveLocation(val lat: Double, val lon: Double, val updatedAt: Long?)

class FirebaseRidesRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance()
) {
    private val rides get() = db.collection("rides")
    private val drivers get() = db.collection("drivers")

    suspend fun isAdmin(uid: String): Boolean {
        val admin = db.collection("admins").document(uid).get().await()
        return admin.exists() && admin.getString("role") == "admin" && admin.getBoolean("active") == true
    }

    fun listenPendingDrivers(onChange: (List<DriverCandidate>) -> Unit, onError: (Exception) -> Unit): ListenerRegistration =
        drivers.whereEqualTo("approved", false).addSnapshotListener { snapshot, error ->
            if (error != null) return@addSnapshotListener onError(error)
            onChange(snapshot?.documents.orEmpty().mapNotNull { it.toDriverCandidate() })
        }

    suspend fun setDriverApproval(uid: String, approved: Boolean) {
        drivers.document(uid).update(
            mapOf("approved" to approved, "available" to false, "updatedAt" to FieldValue.serverTimestamp())
        ).await()
    }

    suspend fun signInAnonymously(): String {
        val auth = FirebaseAuth.getInstance()
        return auth.currentUser?.uid ?: auth.signInAnonymously().await().user?.uid
        ?: error("تعذر إنشاء جلسة Firebase")
    }

    suspend fun saveUserProfile(uid: String, role: String, name: String, phone: String) {
        db.collection("users").document(uid).set(
            mapOf("uid" to uid, "role" to role, "name" to name, "phone" to phone, "updatedAt" to FieldValue.serverTimestamp()),
            SetOptions.merge()
        ).await()
    }

    suspend fun saveFcmToken(uid: String, token: String) {
        val ref = db.collection("users").document(uid)
        val existing = ref.get().await()
        if (!existing.exists()) {
            ref.set(
                mapOf(
                    "uid" to uid,
                    "role" to "customer",
                    "name" to "",
                    "phone" to (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.phoneNumber ?: ""),
                    "fcmToken" to token,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } else {
            ref.update(
                mapOf("fcmToken" to token, "updatedAt" to FieldValue.serverTimestamp())
            ).await()
        }
    }

    suspend fun saveDriverApplication(
        uid: String, name: String, phone: String, licenseType: String, vehicleType: String,
        idCardImageUrl: String = "", vehicleImageUrl: String = ""
    ) {
        val ref = drivers.document(uid)
        val existing = ref.get().await()
        val payload = mutableMapOf<String, Any>(
            "uid" to uid, "displayName" to name, "phone" to phone,
            "licenseType" to licenseType, "vehicleType" to vehicleType,
            "idCardImageUrl" to idCardImageUrl, "vehicleImageUrl" to vehicleImageUrl,
            "approved" to (existing.getBoolean("approved") ?: false), "available" to false,
            "lat" to Config.LAT, "lon" to Config.LON,
            "geohash" to com.firebase.geofire.GeoFireUtils.getGeoHashForLocation(
                com.firebase.geofire.GeoLocation(Config.LAT, Config.LON)
            ),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (!existing.exists()) payload["createdAt"] = FieldValue.serverTimestamp()
        ref.set(payload, SetOptions.merge()).await()
    }

    suspend fun getDriverApplication(uid: String): DriverApplication? =
        drivers.document(uid).get().await().takeIf { it.exists() }?.let { snapshot ->
            DriverApplication(
                uid = snapshot.id,
                name = snapshot.getString("displayName") ?: "",
                phone = snapshot.getString("phone") ?: "",
                licenseType = snapshot.getString("licenseType") ?: "غير محدد",
                vehicleType = snapshot.getString("vehicleType") ?: "غير محدد",
                idCardImageUrl = snapshot.getString("idCardImageUrl") ?: "",
                vehicleImageUrl = snapshot.getString("vehicleImageUrl") ?: "",
                approved = snapshot.getBoolean("approved") == true,
                createdAt = snapshot.getTimestamp("createdAt")?.toDate()?.time,
                updatedAt = snapshot.getTimestamp("updatedAt")?.toDate()?.time
            )
        }

    suspend fun ensureDriverProfile(uid: String, name: String) {
        val ref = drivers.document(uid)
        if (!ref.get().await().exists()) {
            val hash = com.firebase.geofire.GeoFireUtils.getGeoHashForLocation(
                com.firebase.geofire.GeoLocation(Config.LAT, Config.LON)
            )
            ref.set(mapOf(
                "uid" to uid, "displayName" to name, "approved" to false, "available" to false,
                "lat" to Config.LAT, "lon" to Config.LON, "geohash" to hash,
                "updatedAt" to FieldValue.serverTimestamp(), "createdAt" to FieldValue.serverTimestamp()
            )).await()
        }
    }

    suspend fun setDriverAvailability(uid: String, available: Boolean) {
        val ref = drivers.document(uid)
        val snapshot = ref.get().await()
        check(snapshot.getBoolean("approved") == true) { "السائق غير معتمد" }
        ref.update("available", available, "updatedAt", FieldValue.serverTimestamp()).await()
    }

    suspend fun publishDriverLocation(uid: String, name: String, point: Coordinate): Boolean {
        val ref = drivers.document(uid)
        val snapshot = ref.get().await()
        if (!snapshot.exists()) ensureDriverProfile(uid, name)
        val current = ref.get().await()
        if (current.getBoolean("approved") != true || current.getBoolean("available") != true) return false
        ref.update(
            mapOf(
                "displayName" to name, "lat" to point.latitude, "lon" to point.longitude,
                "geohash" to com.firebase.geofire.GeoFireUtils.getGeoHashForLocation(
                    com.firebase.geofire.GeoLocation(point.latitude, point.longitude)
                ), "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        return true
    }

    suspend fun markDriverOffline(uid: String) {
        if (drivers.document(uid).get().await().exists()) {
            drivers.document(uid).update("available", false, "updatedAt", FieldValue.serverTimestamp()).await()
        }
    }

    suspend fun createRide(
        customerId: String, customerName: String, customerPhone: String,
        fromAddress: String, toAddress: String, from: Coordinate, to: Coordinate,
        distanceKm: Double, femaleMode: Boolean, withLuggage: Boolean,
        bookingType: String = "now",
        scheduledAt: Long? = null
    ): String {
        require(distanceKm > 0.0 && distanceKm <= 50.0) { "مسافة الرحلة غير صالحة" }
        val rideRef = rides.document()
        val isScheduled = bookingType == "school" && scheduledAt != null && scheduledAt > System.currentTimeMillis()
        if (bookingType == "school") require(scheduledAt != null && scheduledAt > System.currentTimeMillis() + 5 * 60_000) { "موعد الحجز يجب أن يكون بعد 5 دقائق على الأقل" }
        rideRef.set(mapOf(
            "customerId" to customerId, "customerName" to customerName,
            "fromAddress" to fromAddress, "toAddress" to toAddress,
            "fromLat" to from.latitude, "fromLon" to from.longitude,
            "toLat" to to.latitude, "toLon" to to.longitude,
            "distanceKm" to distanceKm, "femaleMode" to femaleMode, "withLuggage" to withLuggage,
            "bookingType" to bookingType, "status" to if (isScheduled) "scheduled" else "searching",
            "searchRadiusMeters" to 500, "searchStage" to 0,
            "scheduledAt" to scheduledAt,
            "invitedDriverIds" to emptyList<String>(), "selectedDriverId" to null,
            "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp()
        )).await()
        rideRef.collection("private").document("contact").set(mapOf("customerPhone" to customerPhone)).await()
        // Immediate rides enter server-side matching now; school bookings wait for the scheduler.
        if (!isScheduled) functions.getHttpsCallable("startRideSearch").call(mapOf("rideId" to rideRef.id))
        return rideRef.id
    }

    suspend fun runSearch(rideId: String, pickup: Coordinate, onStage: (Int, Int) -> Unit) {
        functions.getHttpsCallable("startRideSearch").call(mapOf("rideId" to rideId)).await()
        getRide(rideId)?.let { onStage(it.searchRadiusMeters, 0) }
    }

    suspend fun submitOffer(rideId: String, uid: String, driverName: String, price: Int, etaMinutes: Int) {
        require(price in 1..100_000) { "اكتب سعراً صحيحاً" }
        require(etaMinutes in 1..240) { "اكتب وقت وصول من دقيقة إلى 240 دقيقة" }
        val rideRef = rides.document(rideId)
        val offerRef = rideRef.collection("offers").document(uid)
        db.runTransaction { transaction ->
            val ride = transaction.get(rideRef)
            check(ride.getString("status") == "searching") { "انتهى استقبال عروض الرحلة" }
            check(transaction.get(offerRef).exists().not()) { "أرسلت عرضاً لهذه الرحلة بالفعل" }
            transaction.set(offerRef, mapOf(
                "driverId" to uid, "driverName" to driverName, "price" to price,
                "etaMinutes" to etaMinutes, "status" to "pending", "createdAt" to FieldValue.serverTimestamp()
            ))
            null
        }.await()
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
            check(ride.getString("status") in listOf("searching", "offered")) { "لم تعد الرحلة متاحة" }
            check(offer.getString("status") == "pending") { "هذا العرض لم يعد متاحاً" }
            check(request.getString("status") == "searching") { "دعوة السائق لم تعد متاحة" }
            transaction.update(rideRef, mapOf(
                "status" to "accepted", "selectedDriverId" to driverId,
                "selectedOfferId" to driverId, "selectedPrice" to offer.getLong("price"),
                "updatedAt" to FieldValue.serverTimestamp()
            ))
            transaction.update(offerRef, "status", "selected")
            transaction.update(requestRef, "status", "selected")
            null
        }.await()
    }

    suspend fun updateRideStatus(rideId: String, actorId: String, newStatus: String) {
        val ref = rides.document(rideId)
        db.runTransaction { transaction ->
            val ride = transaction.get(ref)
            val customerId = ride.getString("customerId")
            val driverId = ride.getString("selectedDriverId")
            check(actorId == customerId || actorId == driverId) { "غير مصرح" }
            val current = ride.getString("status") ?: ""
            val allowed = when (current) {
                "accepted" -> newStatus == "driver_arriving" || newStatus == "cancelled"
                "driver_arriving" -> newStatus == "driver_arrived" || newStatus == "cancelled"
                "driver_arrived" -> newStatus == "in_progress" || newStatus == "cancelled"
                "in_progress" -> newStatus == "completed" || newStatus == "cancelled"
                else -> false
            }
            check(allowed) { "انتقال غير مسموح: $current → $newStatus" }
            if (actorId == customerId) check(newStatus == "cancelled") { "الراكب لا ينفذ هذه الحالة" }
            if (newStatus == "cancelled" && current == "in_progress") {
                check(actorId == driverId || actorId == customerId) { "غير مصرح" }
            }
            transaction.update(ref, "status", newStatus, "updatedAt", FieldValue.serverTimestamp())
            null
        }.await()
        if (newStatus == "completed" || newStatus == "cancelled") {
            runCatching { markDriverOffline(actorId) }
        }
    }

    suspend fun cancelRide(rideId: String, customerId: String) = updateRideStatus(rideId, customerId, "cancelled")

    suspend fun submitRating(rideId: String, raterId: String, stars: Int, comment: String = "") {
        require(stars in 1..5) { "التقييم من 1 إلى 5 نجوم" }
        val rideRef = rides.document(rideId)
        val ratingRef = rideRef.collection("ratings").document(raterId)
        db.runTransaction { tx ->
            val ride = tx.get(rideRef)
            check(ride.getString("status") == "completed") { "يمكن التقييم بعد انتهاء الرحلة فقط" }
            val customerId = ride.getString("customerId")
            val driverId = ride.getString("selectedDriverId")
            check(raterId == customerId || raterId == driverId) { "غير مصرح بالتقييم" }
            check(!tx.get(ratingRef).exists()) { "تم إرسال تقييمك بالفعل" }
            tx.set(ratingRef, mapOf(
                "raterId" to raterId,
                "stars" to stars,
                "comment" to comment.trim().take(300),
                "createdAt" to FieldValue.serverTimestamp()
            ))
            null
        }.await()
    }

    suspend fun hasSubmittedRating(rideId: String, raterId: String): Boolean =
        rides.document(rideId).collection("ratings").document(raterId).get().await().exists()

    suspend fun listenCustomerRidesOnce(customerId: String): List<RideRecord> = rides.whereEqualTo("customerId", customerId)
        .orderBy("createdAt", Query.Direction.DESCENDING).limit(30).get().await().documents.mapNotNull { it.toRideRecord() }

    suspend fun getRide(rideId: String): RideRecord? = rides.document(rideId).get().await().takeIf { it.exists() }?.toRideRecord()

    suspend fun getAcceptedCustomerPhone(rideId: String, driverId: String): String {
        val ride = rides.document(rideId).get().await()
        check(ride.getString("status") in listOf("accepted", "driver_arriving", "driver_arrived", "in_progress") && ride.getString("selectedDriverId") == driverId) {
            "بيانات التواصل تظهر للسائق المختار فقط"
        }
        return rides.document(rideId).collection("private").document("contact").get().await().getString("customerPhone") ?: ""
    }

    fun listenCustomerRides(customerId: String, onChange: (List<RideRecord>) -> Unit, onError: (Exception) -> Unit): ListenerRegistration =
        rides.whereEqualTo("customerId", customerId).orderBy("createdAt", Query.Direction.DESCENDING).limit(30)
            .addSnapshotListener { snapshot, error ->
                if (error != null) onError(error) else onChange(snapshot?.documents.orEmpty().mapNotNull { it.toRideRecord() })
            }

    fun listenDriverRequests(driverId: String, onChange: (List<DriverRideRequest>) -> Unit, onError: (Exception) -> Unit): ListenerRegistration =
        drivers.document(driverId).collection("requests").addSnapshotListener { snapshot, error ->
            if (error != null) onError(error) else onChange(snapshot?.documents.orEmpty().mapNotNull { doc ->
                DriverRideRequest(
                    rideId = doc.id, from = doc.getString("fromAddress") ?: "", to = doc.getString("toAddress") ?: "",
                    fromLat = doc.getDouble("fromLat") ?: return@mapNotNull null,
                    fromLon = doc.getDouble("fromLon") ?: return@mapNotNull null,
                    distanceKm = doc.getDouble("distanceKm") ?: 0.0,
                    radiusMeters = (doc.getLong("radiusMeters") ?: 500L).toInt(),
                    status = doc.getString("status") ?: "closed"
                )
            })
        }

    fun listenRide(rideId: String, onChange: (RideRecord?) -> Unit, onError: (Exception) -> Unit): ListenerRegistration =
        rides.document(rideId).addSnapshotListener { snapshot, error ->
            if (error != null) onError(error) else onChange(snapshot?.takeIf { it.exists() }?.toRideRecord())
        }

    fun listenOffers(rideId: String, onChange: (List<RideOffer>) -> Unit, onError: (Exception) -> Unit): ListenerRegistration =
        rides.document(rideId).collection("offers").addSnapshotListener { snapshot, error ->
            if (error != null) onError(error) else onChange(snapshot?.documents.orEmpty().mapNotNull { doc ->
                RideOffer(
                    driverId = doc.getString("driverId") ?: doc.id,
                    driverName = doc.getString("driverName") ?: "سائق",
                    price = (doc.getLong("price") ?: return@mapNotNull null).toInt(),
                    etaMinutes = (doc.getLong("etaMinutes") ?: 0L).toInt(),
                    status = doc.getString("status") ?: "pending"
                )
            })
        }

    fun listenDriverLocation(driverId: String, onChange: (DriverLiveLocation?) -> Unit, onError: (Exception) -> Unit): ListenerRegistration =
        drivers.document(driverId).addSnapshotListener { snapshot, error ->
            if (error != null) onError(error) else if (snapshot?.exists() == true) {
                onChange(DriverLiveLocation(
                    lat = snapshot.getDouble("lat") ?: return@addSnapshotListener,
                    lon = snapshot.getDouble("lon") ?: return@addSnapshotListener,
                    updatedAt = snapshot.getTimestamp("updatedAt")?.toDate()?.time
                ))
            }
        }

    suspend fun getDriverApproval(uid: String): Boolean? {
        val snapshot = drivers.document(uid).get().await()
        return if (snapshot.exists()) snapshot.getBoolean("approved") == true else null
    }

    suspend fun getDriverAvailability(uid: String): Boolean =
        drivers.document(uid).get().await().getBoolean("available") == true

    private fun DocumentSnapshot.toDriverCandidate() = DriverCandidate(
        uid = id, displayName = getString("displayName") ?: "بدون اسم",
        approved = getBoolean("approved") ?: false, available = getBoolean("available") ?: false,
        lat = getDouble("lat") ?: 0.0, lon = getDouble("lon") ?: 0.0,
        updatedAt = getTimestamp("updatedAt")?.toDate()?.time
    )

    private fun DocumentSnapshot.toRideRecord(): RideRecord? {
        val customerId = getString("customerId") ?: return null
        return RideRecord(
            id = id, customerId = customerId,
            from = getString("fromAddress") ?: "", to = getString("toAddress") ?: "",
            fromLat = getDouble("fromLat") ?: 0.0, fromLon = getDouble("fromLon") ?: 0.0,
            toLat = getDouble("toLat") ?: 0.0, toLon = getDouble("toLon") ?: 0.0,
            distanceKm = getDouble("distanceKm") ?: 0.0,
            status = getString("status") ?: "searching",
            searchRadiusMeters = (getLong("searchRadiusMeters") ?: 500L).toInt(),
            selectedDriverId = getString("selectedDriverId"),
            selectedPrice = (getLong("selectedPrice"))?.toInt(),
            driverLat = getDouble("driverLat"), driverLon = getDouble("driverLon"),
            createdAt = getTimestamp("createdAt")?.toDate()?.time
        )
    }
}
