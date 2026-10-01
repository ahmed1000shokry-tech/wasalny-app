package com.wasalny.sidisalem

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
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
    val status: String,
    val createdAt: Long? = null
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
    val profileImageUrl: String = "",
    val approved: Boolean = false,
    val needsMoreData: Boolean = false,
    val adminMessage: String = "",
    val createdAt: Long? = null,
    val updatedAt: Long? = null
)

data class DriverLiveLocation(val lat: Double, val lon: Double, val updatedAt: Long?)

data class SubscriptionRequest(
    val id: String,
    val driverId: String,
    val driverName: String,
    val amount: Int,
    val months: Int,
    val proofPath: String,
    val note: String,
    val status: String
)

data class DriverSubscription(val active: Boolean, val expiresAt: Long?, val plan: String, val daysLeft: Int)

data class DriverStats(val completedRides: Int, val averageRating: Double, val totalEarnings: Int, val ratingCount: Int)

data class CustomerStats(
    val totalRides: Int,
    val completedRides: Int,
    val cancelledRides: Int,
    val cancelsToday: Int,
    val banUntil: Long?
) {
    val isBanned: Boolean get() = banUntil != null && banUntil > System.currentTimeMillis()
}

data class RatingRecord(val id: String, val rideId: String, val raterId: String, val stars: Int, val comment: String)

class FirebaseRidesRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance()
) {
    private val rides get() = db.collection("rides")
    private val drivers get() = db.collection("drivers")
    private val storage get() = FirebaseStorage.getInstance()

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
        if (existing.getBoolean("needsMoreData") == true) {
            payload["needsMoreData"] = false
            payload["adminMessage"] = ""
        }
        if (!existing.exists()) payload["createdAt"] = FieldValue.serverTimestamp()
        ref.set(payload, SetOptions.merge()).await()
    }

    suspend fun getDriverApplication(uid: String): DriverApplication? =
        drivers.document(uid).get().await().takeIf { it.exists() }?.toDriverApplication()

    fun listenDriverApplication(
        uid: String,
        onChange: (DriverApplication?) -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration = drivers.document(uid).addSnapshotListener { snapshot, error ->
        if (error != null) onError(error)
        else onChange(snapshot?.takeIf { it.exists() }?.toDriverApplication())
    }

    suspend fun requestDriverMoreData(uid: String, message: String) {
        require(message.trim().isNotEmpty()) { "اكتب البيانات المطلوبة من السائق" }
        drivers.document(uid).update(
            mapOf(
                "needsMoreData" to true,
                "adminMessage" to message.trim().take(300),
                "available" to false,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    suspend fun listAllDrivers(limit: Int = 80): List<DriverApplication> = runCatching {
        drivers.orderBy("createdAt", Query.Direction.DESCENDING).limit(limit.toLong()).get().await().documents
    }.getOrElse { drivers.limit(limit.toLong()).get().await().documents }.map { snapshot ->
        DriverApplication(
            uid = snapshot.id,
            name = snapshot.getString("displayName") ?: "",
            phone = snapshot.getString("phone") ?: "",
            licenseType = snapshot.getString("licenseType") ?: "",
            vehicleType = snapshot.getString("vehicleType") ?: "",
            idCardImageUrl = snapshot.getString("idCardImageUrl") ?: "",
            vehicleImageUrl = snapshot.getString("vehicleImageUrl") ?: "",
            profileImageUrl = snapshot.getString("profileImageUrl") ?: "",
            approved = snapshot.getBoolean("approved") == true,
            needsMoreData = snapshot.getBoolean("needsMoreData") == true,
            adminMessage = snapshot.getString("adminMessage") ?: "",
            createdAt = snapshot.getTimestamp("createdAt")?.toDate()?.time
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
        if (available) check(getDriverSubscription(uid).active) {
            "الاشتراك غير نشط. حوّل رسوم الاشتراك عبر فودافون كاش على 01069631950."
        }
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
        assertCustomerNotBanned(customerId)
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
            "invitedDriverIds" to emptyList<String>(),
            "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp()
        )).await()
        rideRef.collection("private").document("contact").set(mapOf("customerPhone" to customerPhone)).await()
        return rideRef.id
    }

    suspend fun runSearch(rideId: String) {
        functions.getHttpsCallable("startRideSearch").call(mapOf("rideId" to rideId)).await()
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
            check(offer.exists() && offer.getString("status") == "pending") { "هذا العرض لم يعد متاحاً" }
            val price = offer.getLong("price")
            check(price != null && price >= 1) { "سعر العرض غير صالح" }
            if (request.exists()) {
                check(request.getString("status") in listOf("searching", "pending")) { "دعوة السائق لم تعد متاحة" }
            }
            transaction.update(rideRef, mapOf(
                "status" to "accepted", "selectedDriverId" to driverId,
                "selectedOfferId" to driverId, "selectedPrice" to price,
                "updatedAt" to FieldValue.serverTimestamp()
            ))
            transaction.update(offerRef, "status", "selected")
            if (request.exists()) transaction.update(requestRef, "status", "selected")
            null
        }.await()
    }

    suspend fun updateRideStatus(rideId: String, actorId: String, newStatus: String) {
        val ref = rides.document(rideId)
        val selectedDriverId = db.runTransaction { transaction ->
            val ride = transaction.get(ref)
            val customerId = ride.getString("customerId")
            val driverId = ride.getString("selectedDriverId")
            check(actorId == customerId || actorId == driverId) { "غير مصرح" }
            val current = ride.getString("status") ?: ""
            val allowed = when (current) {
                "searching", "scheduled", "offered" -> newStatus == "cancelled"
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
            driverId
        }.await()
        if (newStatus == "completed" || newStatus == "cancelled") {
            if (selectedDriverId != null) {
                runCatching { markDriverOffline(selectedDriverId) }
            }
        }
    }

    suspend fun cancelRide(rideId: String, customerId: String) {
        check(FirebaseAuth.getInstance().currentUser?.uid == customerId) { "غير مصرح" }
        functions.getHttpsCallable("cancelCustomerRide").call(mapOf("rideId" to rideId)).await()
    }

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
        drivers.document(driverId).collection("requests")
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(50)
            .addSnapshotListener { snapshot, error ->
            if (error != null) onError(error) else onChange(snapshot?.documents.orEmpty().mapNotNull { doc ->
                DriverRideRequest(
                    rideId = doc.id, from = doc.getString("fromAddress") ?: "", to = doc.getString("toAddress") ?: "",
                    fromLat = doc.getDouble("fromLat") ?: return@mapNotNull null,
                    fromLon = doc.getDouble("fromLon") ?: return@mapNotNull null,
                    distanceKm = doc.getDouble("distanceKm") ?: 0.0,
                    radiusMeters = (doc.getLong("radiusMeters") ?: 500L).toInt(),
                    status = doc.getString("status") ?: "closed",
                    createdAt = doc.getTimestamp("createdAt")?.toDate()?.time
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

    fun listenDriverLocation(rideId: String, onChange: (DriverLiveLocation?) -> Unit, onError: (Exception) -> Unit): ListenerRegistration =
        rides.document(rideId).collection("private").document("driverLocation").addSnapshotListener { snapshot, error ->
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

    fun listenDriverAvailability(uid: String, onChange: (Boolean) -> Unit, onError: (Exception) -> Unit): ListenerRegistration =
        drivers.document(uid).addSnapshotListener { snapshot, error ->
            if (error != null) onError(error) else onChange(snapshot?.getBoolean("available") == true)
        }

    suspend fun assertCustomerNotBanned(customerId: String) {
        val user = db.collection("users").document(customerId).get().await()
        val banUntil = user.getTimestamp("banUntil")?.toDate()?.time ?: user.getLong("banUntil")
        check(banUntil == null || banUntil <= System.currentTimeMillis()) {
            "حسابك موقوف مؤقتًا بسبب تكرار إلغاء الرحلات. حاول بعد انتهاء الإيقاف."
        }
    }

    suspend fun getDriverSubscription(uid: String): DriverSubscription {
        val snapshot = drivers.document(uid).get().await()
        val expiry = snapshot.getTimestamp("subscriptionExpiresAt")?.toDate()?.time
            ?: snapshot.getLong("subscriptionExpiresAt")
        val active = expiry != null && expiry > System.currentTimeMillis()
        return DriverSubscription(
            active = active,
            expiresAt = expiry,
            plan = snapshot.getString("subscriptionPlan") ?: "none",
            daysLeft = if (active) (((expiry!! - System.currentTimeMillis()) / 86_400_000L).toInt() + 1) else 0
        )
    }

    suspend fun submitSubscriptionRequest(driverId: String, image: Uri, contentType: String, note: String): SubscriptionRequest {
        require(contentType in listOf("image/jpeg", "image/png", "image/webp")) { "اختر صورة JPG أو PNG أو WEBP" }
        val requestRef = db.collection("subscriptionRequests").document()
        val proofPath = "subscriptionProofs/$driverId/${requestRef.id}"
        val proofRef = storage.reference.child(proofPath)
        proofRef.putFile(image, StorageMetadata.Builder().setContentType(contentType).build()).await()
        try {
            val result = functions.getHttpsCallable("submitSubscriptionRequest").call(
                mapOf("requestId" to requestRef.id, "proofPath" to proofPath, "note" to note.trim().take(300))
            ).await()
            val amount = ((result.getData() as? Map<*, *>)?.get("amount") as? Number)?.toInt()
                ?: error("تعذر تحديد مبلغ الاشتراك")
            val driverName = drivers.document(driverId).get().await().getString("displayName") ?: "سائق"
            return SubscriptionRequest(requestRef.id, driverId, driverName, amount, 1, proofPath, note.trim(), "pending")
        } catch (error: Exception) {
            runCatching { proofRef.delete().await() }
            throw error
        }
    }

    suspend fun listPendingSubscriptions(): List<SubscriptionRequest> = db.collection("subscriptionRequests")
        .whereEqualTo("status", "pending").limit(50).get().await().documents.map { document ->
            SubscriptionRequest(
                id = document.id,
                driverId = document.getString("driverId") ?: "",
                driverName = document.getString("driverName") ?: "سائق",
                amount = (document.getLong("amount") ?: 0L).toInt(),
                months = (document.getLong("months") ?: 1L).toInt(),
                proofPath = document.getString("proofPath") ?: "",
                note = document.getString("note") ?: "",
                status = document.getString("status") ?: "pending"
            )
        }

    suspend fun getPendingSubscription(driverId: String): SubscriptionRequest? = db.collection("subscriptionRequests")
        .whereEqualTo("driverId", driverId).whereEqualTo("status", "pending").limit(1).get().await()
        .documents.firstOrNull()?.let { document ->
            SubscriptionRequest(
                id = document.id,
                driverId = driverId,
                driverName = document.getString("driverName") ?: "سائق",
                amount = (document.getLong("amount") ?: 0L).toInt(),
                months = (document.getLong("months") ?: 1L).toInt(),
                proofPath = document.getString("proofPath") ?: "",
                note = document.getString("note") ?: "",
                status = "pending"
            )
        }

    suspend fun reviewSubscriptionRequest(requestId: String, approve: Boolean) {
        functions.getHttpsCallable("reviewSubscriptionRequest").call(
            mapOf("requestId" to requestId, "decision" to if (approve) "approve" else "reject")
        ).await()
    }

    suspend fun getSubscriptionProof(path: String): ByteArray =
        storage.reference.child(path).getBytes(5L * 1024 * 1024).await()

    suspend fun getDriverStats(driverId: String): DriverStats {
        val completed = rides.whereEqualTo("selectedDriverId", driverId)
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(100).get().await().documents
            .filter { it.getString("status") == "completed" }
        var ratingTotal = 0
        var ratingCount = 0
        completed.take(40).forEach { ride ->
            runCatching {
                ride.reference.collection("ratings").get().await().documents
                    .filter { it.getString("raterId") != driverId }
                    .forEach { rating ->
                        ratingTotal += (rating.getLong("stars") ?: 0L).toInt()
                        ratingCount++
                    }
            }
        }
        val earnings = completed.sumOf { (it.getLong("selectedPrice") ?: 0L).toInt() }
        return DriverStats(
            completedRides = completed.size,
            averageRating = if (ratingCount == 0) 0.0 else ratingTotal.toDouble() / ratingCount,
            totalEarnings = earnings,
            ratingCount = ratingCount
        )
    }

    suspend fun getCustomerStats(customerId: String): CustomerStats {
        val customerRides = rides.whereEqualTo("customerId", customerId).limit(100).get().await().documents
        val cairoTimeZone = java.util.TimeZone.getTimeZone("Africa/Cairo")
        val dayKey = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .apply { timeZone = cairoTimeZone }.format(java.util.Date())
        val userRef = db.collection("users").document(customerId)
        val cancelCount = userRef.collection("cancels").document(dayKey).get().await().getLong("count")?.toInt() ?: 0
        val user = userRef.get().await()
        val banUntil = user.getTimestamp("banUntil")?.toDate()?.time ?: user.getLong("banUntil")
        return CustomerStats(
            totalRides = customerRides.size,
            completedRides = customerRides.count { it.getString("status") == "completed" },
            cancelledRides = customerRides.count { it.getString("status") == "cancelled" },
            cancelsToday = cancelCount,
            banUntil = banUntil
        )
    }

    suspend fun listRecentRides(limit: Int = 40): List<RideRecord> = runCatching {
        rides.orderBy("createdAt", Query.Direction.DESCENDING).limit(limit.toLong())
            .get().await().documents.mapNotNull { it.toRideRecord() }
    }.getOrElse {
        rides.limit(limit.toLong()).get().await().documents.mapNotNull { it.toRideRecord() }
    }

    suspend fun listRecentRatings(limit: Int = 40): List<RatingRecord> {
        val result = mutableListOf<RatingRecord>()
        for (ride in listRecentRides(30)) {
            val ratings = rides.document(ride.id).collection("ratings").get().await()
            ratings.documents.forEach { rating ->
                result += RatingRecord(
                    id = "${ride.id}_${rating.id}",
                    rideId = ride.id,
                    raterId = rating.getString("raterId") ?: rating.id,
                    stars = (rating.getLong("stars") ?: 0L).toInt(),
                    comment = rating.getString("comment") ?: ""
                )
            }
            if (result.size >= limit) return result.take(limit)
        }
        return result
    }

    private fun DocumentSnapshot.toDriverCandidate() = DriverCandidate(
        uid = id, displayName = getString("displayName") ?: "بدون اسم",
        approved = getBoolean("approved") ?: false, available = getBoolean("available") ?: false,
        lat = getDouble("lat") ?: 0.0, lon = getDouble("lon") ?: 0.0,
        updatedAt = getTimestamp("updatedAt")?.toDate()?.time
    )

    private fun DocumentSnapshot.toDriverApplication() = DriverApplication(
        uid = id,
        name = getString("displayName") ?: "",
        phone = getString("phone") ?: "",
        licenseType = getString("licenseType") ?: "غير محدد",
        vehicleType = getString("vehicleType") ?: "غير محدد",
        idCardImageUrl = getString("idCardImageUrl") ?: "",
        vehicleImageUrl = getString("vehicleImageUrl") ?: "",
        profileImageUrl = getString("profileImageUrl") ?: "",
        approved = getBoolean("approved") == true,
        needsMoreData = getBoolean("needsMoreData") == true,
        adminMessage = getString("adminMessage") ?: "",
        createdAt = getTimestamp("createdAt")?.toDate()?.time,
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
