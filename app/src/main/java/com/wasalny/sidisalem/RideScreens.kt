package com.wasalny.sidisalem

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@Composable
fun RidesV4(nav: NavController, role: String, activeRideId: String? = null) {
    val uid = FirebaseAuth.getInstance().currentUser?.uid
    if (uid == null) {
        Text("انتهت جلسة الحساب. أعد فتح التطبيق.")
        return
    }
    if (role == "driver") DriverRideRequestsScreen(uid) else if (activeRideId != null) {
        CustomerRideOffersScreen(activeRideId, uid, nav)
    } else {
        CustomerRideHistoryScreen(uid, nav)
    }
}

@Composable
private fun CustomerRideOffersScreen(rideId: String, customerId: String, nav: NavController) {
    val repository = remember { FirebaseRidesRepository() }
    val scope = rememberCoroutineScope()
    var ride by remember { mutableStateOf<RideRecord?>(null) }
    var offers by remember { mutableStateOf<List<RideOffer>>(emptyList()) }
    var driverLocation by remember { mutableStateOf<DriverLiveLocation?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyDriverId by remember { mutableStateOf<String?>(null) }
    var showRating by remember { mutableStateOf(false) }
    var alreadyRated by remember { mutableStateOf(false) }

    DisposableEffect(rideId) {
        val regs = mutableListOf<ListenerRegistration>()
        regs += repository.listenRide(rideId, { ride = it }, { error = it.localizedMessage })
        regs += repository.listenOffers(rideId, { offers = it }, { error = it.localizedMessage })
        onDispose { regs.forEach { it.remove() } }
    }

    DisposableEffect(ride?.selectedDriverId, ride?.status, rideId) {
        val currentRide = ride
        if (currentRide?.selectedDriverId == null || currentRide.status !in listOf("accepted", "driver_arriving", "driver_arrived", "in_progress")) {
            driverLocation = null
            return@DisposableEffect onDispose { }
        }
        val reg = repository.listenDriverLocation(rideId, { driverLocation = it }, { error = it.localizedMessage })
        onDispose { reg.remove() }
    }

    LaunchedEffect(rideId, ride?.status) {
        if (ride?.status != "searching") return@LaunchedEffect
        runCatching { repository.runSearch(rideId) }
            .onFailure { error = it.localizedMessage ?: "تعذر بدء البحث عن سائق" }
    }

    LaunchedEffect(ride?.status) {
        if (ride?.status == "completed") {
            alreadyRated = runCatching { repository.hasSubmittedRating(rideId, customerId) }.getOrDefault(false)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("متابعة الرحلة", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { nav.navigate("rides") }) { Text("رحلاتي") }
        }
        ride?.let { current ->
            Text("${current.from} → ${current.to}", fontWeight = FontWeight.SemiBold)
            Text("المسافة: %.2f كم".format(current.distanceKm), color = Color.Gray)
            if (current.selectedPrice != null) Text("الأجرة المتفق عليها: ${current.selectedPrice} جنيه", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            StatusCard(current.status)
            if (current.status == "scheduled" && current.bookingType == "school") {
                Spacer(Modifier.height(6.dp))
                Text(
                    "الحجز محفوظ. عند حلول الموعد سيتم إرساله لكل السائقين المتاحين، وليس بنظام نطاق 500م/1كم/2كم/5كم.",
                    color = Color(0xFF0D7C3E),
                    fontSize = 12.sp
                )
            }
            if (current.selectedDriverId != null && driverLocation != null) {
                val age = driverLocation?.updatedAt?.let { (System.currentTimeMillis() - it) / 1000 }
                Text("موقع التوكتوك: %.5f, %.5f".format(driverLocation!!.lat, driverLocation!!.lon), color = Color(0xFF0D7C3E))
                Text("آخر تحديث: ${age ?: 0} ثانية", color = Color.Gray, fontSize = MaterialTheme.typography.bodySmall.fontSize)
                LiveRideMap(
                    pickup = Coordinate(current.fromLat, current.fromLon),
                    dropoff = Coordinate(current.toLat, current.toLon),
                    driver = Coordinate(driverLocation!!.lat, driverLocation!!.lon)
                )
            }
        }
        if (error != null) Text(error!!, color = Color(0xFFB3261E))
        Spacer(Modifier.height(10.dp))
        val currentStatus = ride?.status
        if (currentStatus == "completed" && !alreadyRated) {
            Button(onClick = { showRating = true }, modifier = Modifier.fillMaxWidth()) { Text("⭐ قيّم الرحلة والسائق") }
        }
        if (currentStatus == "searching" || currentStatus == "offered") {
            val pendingOffers = offers.filter { it.status == "pending" }.sortedBy { it.price }.take(5)
            Text("أفضل العروض (${pendingOffers.size}/5)", fontWeight = FontWeight.Bold)
            if (pendingOffers.isEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    if (ride?.bookingType == "school") "تم إرسال الحجز لكل السائقين المتاحين عند موعد الرحلة."
                    else "بنوسع البحث تلقائياً: 500م → 1كم → 2كم → 5كم",
                    color = Color.Gray
                )
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(pendingOffers, key = { it.driverId }) { offer ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(offer.driverName, fontWeight = FontWeight.Bold)
                                    Text("${offer.price} جنيه", color = Color(0xFF0D7C3E), fontWeight = FontWeight.Bold)
                                }
                                Text("الوصول المتوقع: ${offer.etaMinutes} دقيقة")
                                Button(enabled = busyDriverId == null, onClick = {
                                    busyDriverId = offer.driverId
                                    scope.launch {
                                        try { repository.selectOffer(rideId, customerId, offer.driverId) }
                                        catch (e: Exception) { error = e.localizedMessage ?: "تعذر اختيار العرض" }
                                        finally { busyDriverId = null }
                                    }
                                }) { if (busyDriverId == offer.driverId) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("اختيار هذا السائق") }
                            }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { scope.launch { runCatching { repository.cancelRide(rideId, customerId) }.onFailure { error = it.localizedMessage } } }, Modifier.fillMaxWidth()) {
                Text("إلغاء الطلب")
            }
        } else if (currentStatus in listOf("scheduled", "accepted", "driver_arriving", "driver_arrived", "in_progress")) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { scope.launch { runCatching { repository.cancelRide(rideId, customerId) }.onFailure { error = it.localizedMessage } } }, Modifier.fillMaxWidth()) {
                Text("إلغاء الرحلة")
            }
        }
    }

    if (showRating) {
        RatingDialog(
            title = "تقييم السائق",
            onDismiss = { showRating = false },
            onSubmit = { stars, comment ->
                scope.launch {
                    try {
                        repository.submitRating(rideId, customerId, stars, comment)
                        alreadyRated = true
                        showRating = false
                    } catch (e: Exception) { error = e.localizedMessage ?: "تعذر إرسال التقييم" }
                }
            }
        )
    }
}


@Composable
private fun LiveRideMap(pickup: Coordinate, dropoff: Coordinate, driver: Coordinate) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(210.dp),
        factory = { context ->
            Configuration.getInstance().userAgentValue = context.packageName
            MapView(context).apply {
                setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(15.0)
                controller.setCenter(OsmGeoPoint(driver.latitude, driver.longitude))
                onResume()
            }
        },
        update = { map ->
            map.controller.setCenter(OsmGeoPoint(driver.latitude, driver.longitude))
            map.overlays.removeAll(map.overlays.filterIsInstance<Marker>())
            listOf(
                Triple(pickup, "نقطة الركوب", "📍"),
                Triple(dropoff, "الوجهة", "🏁"),
                Triple(driver, "التوكتوك الآن", "🚖")
            ).forEach { (point, title, _) ->
                map.overlays.add(Marker(map).apply {
                    position = OsmGeoPoint(point.latitude, point.longitude)
                    this.title = title
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
            }
            map.invalidate()
        }
    )
}

@Composable
private fun StatusCard(status: String) {
    val (title, text) = when (status) {
        "searching" -> "جارٍ البحث" to "يتم البحث عن توكتوك قريب منك."
        "offered" -> "وصلت عروض" to "اختار السعر ووقت الوصول المناسبين."
        "accepted" -> "تم اختيار السائق" to "السائق سيبدأ التوجه إليك."
        "driver_arriving" -> "السائق في الطريق" to "التوكتوك متجه إلى نقطة الركوب."
        "driver_arrived" -> "السائق وصل" to "السائق ينتظر عند نقطة الركوب."
        "in_progress" -> "الرحلة بدأت" to "رحلتك جارية الآن."
        "completed" -> "اكتملت الرحلة" to "تم إنهاء الرحلة بنجاح."
        "cancelled" -> "تم الإلغاء" to "تم إغلاق الرحلة."
        "no_drivers" -> "لا يوجد سائقون" to "لم يصل عرض مناسب خلال نطاق البحث."
        else -> "حالة الرحلة" to status
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
        Column(Modifier.padding(12.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(text, color = Color.Gray) }
    }
}

@Composable
private fun CustomerRideHistoryScreen(customerId: String, nav: NavController) {
    val repository = remember { FirebaseRidesRepository() }
    var rides by remember { mutableStateOf<List<RideRecord>>(emptyList()) }
    var stats by remember { mutableStateOf<CustomerStats?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(customerId) {
        val registration = repository.listenCustomerRides(customerId, { rides = it }, { error = it.localizedMessage ?: "تعذر تحميل الرحلات" })
        onDispose { registration.remove() }
    }
    LaunchedEffect(customerId) {
        runCatching { stats = repository.getCustomerStats(customerId) }
            .onFailure { error = it.localizedMessage ?: "تعذر تحميل الإحصاءات" }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("رحلاتي", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        stats?.let { summary ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
                Column(Modifier.padding(12.dp)) {
                    Text("إجمالي ${summary.totalRides} • مكتملة ${summary.completedRides} • ملغاة ${summary.cancelledRides}")
                    Text("إلغاءات اليوم: ${summary.cancelsToday}/3", color = Color.Gray)
                    if (summary.isBanned) Text("الحساب موقوف مؤقتًا حتى ${java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("ar"))
                        .format(java.util.Date(summary.banUntil!!))}", color = Color(0xFFB3261E), fontWeight = FontWeight.Bold)
                }
            }
        }
        if (error != null) Text(error!!, color = Color(0xFFB3261E))
        if (rides.isEmpty()) Text("لا توجد رحلات بعد.", color = Color.Gray)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rides, key = { it.id }) { ride ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${ride.from} → ${ride.to}", fontWeight = FontWeight.Bold)
                        Text("${ride.status.arabicRideStatus()} | %.2f كم".format(ride.distanceKm))
                        if (ride.status !in listOf("completed", "cancelled", "no_drivers")) {
                            TextButton(onClick = { nav.navigate("rides?rideId=${ride.id}") }) { Text("متابعة الرحلة") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DriverRideRequestsScreen(driverId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { FirebaseRidesRepository() }
    var approved by remember { mutableStateOf<Boolean?>(null) }
    var online by remember { mutableStateOf(false) }
    var locationAllowed by remember { mutableStateOf(hasLocationPermission(context)) }
    var requests by remember { mutableStateOf<List<DriverRideRequest>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedRequest by remember { mutableStateOf<DriverRideRequest?>(null) }
    var selectedRides by remember { mutableStateOf<Map<String, RideRecord>>(emptyMap()) }
    var ratedRideIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var ratingRide by remember { mutableStateOf<RideRecord?>(null) }
    var selectedCustomerPhone by remember { mutableStateOf<String?>(null) }
    var priceText by remember { mutableStateOf("") }
    var etaText by remember { mutableStateOf("10") }
    var sendingOffer by remember { mutableStateOf(false) }
    var subscription by remember { mutableStateOf<DriverSubscription?>(null) }
    var pendingSubscription by remember { mutableStateOf<SubscriptionRequest?>(null) }
    var selectedProof by remember { mutableStateOf<android.net.Uri?>(null) }
    var proofPreview by remember { mutableStateOf<ImageBitmap?>(null) }
    var subscriptionNote by remember { mutableStateOf("") }
    var subscriptionBusy by remember { mutableStateOf(false) }
    var subscriptionMessage by remember { mutableStateOf<String?>(null) }
    var driverStats by remember { mutableStateOf<DriverStats?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        locationAllowed = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }
    val proofPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selectedProof = it }

    LaunchedEffect(selectedProof) {
        proofPreview = null
        val imageUri = selectedProof ?: return@LaunchedEffect
        proofPreview = withContext(Dispatchers.IO) {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(imageUri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, bounds)
            }
            val sampleSize = maxOf(1, maxOf(bounds.outWidth / 1000, bounds.outHeight / 1000))
            val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
            context.contentResolver.openInputStream(imageUri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, options)?.asImageBitmap()
            }
        }
    }

    LaunchedEffect(driverId) {
        try {
            repository.ensureDriverProfile(driverId, getUserName(context))
            approved = repository.getDriverApproval(driverId)
            subscription = repository.getDriverSubscription(driverId)
            val currentlyAvailable = repository.getDriverAvailability(driverId)
            online = currentlyAvailable && subscription?.active == true
            if (currentlyAvailable && subscription?.active != true) {
                runCatching { repository.setDriverAvailability(driverId, false) }
            }
            pendingSubscription = repository.getPendingSubscription(driverId)
            driverStats = repository.getDriverStats(driverId)
        }
        catch (e: Exception) { error = e.localizedMessage ?: "تعذر قراءة حساب السائق" }
    }

    DisposableEffect(driverId, approved) {
        if (approved != true) return@DisposableEffect onDispose { }
        val registration = repository.listenDriverRequests(driverId, { requests = it }, { error = it.localizedMessage ?: "تعذر تحميل الطلبات" })
        onDispose {
            registration.remove()
            context.stopService(Intent(context, DriverLocationService::class.java))
            scope.launch { runCatching { repository.markDriverOffline(driverId) } }
        }
    }

    DisposableEffect(driverId, approved) {
        if (approved != true) return@DisposableEffect onDispose { }
        val registration = repository.listenDriverAvailability(
            driverId,
            { available ->
                val activeSubscription = subscription?.active
                if (activeSubscription != null) {
                    online = available && activeSubscription
                    if (!online) context.stopService(Intent(context, DriverLocationService::class.java))
                }
            },
            { error = it.localizedMessage ?: "تعذر تحديث حالة التوفر" }
        )
        onDispose { registration.remove() }
    }

    val selectedRideIds = requests.filter { it.status == "selected" }.take(10).map { it.rideId }
    DisposableEffect(driverId, selectedRideIds) {
        val registrations = selectedRideIds.map { rideId ->
            repository.listenRide(
                rideId,
                { ride ->
                    selectedRides = selectedRides.toMutableMap().apply {
                        if (ride == null) remove(rideId) else put(rideId, ride)
                    }
                },
                { error = it.localizedMessage ?: "تعذر تحديث حالة الرحلة" }
            )
        }
        onDispose { registrations.forEach { it.remove() } }
    }

    LaunchedEffect(selectedRides.values.filter { it.status == "completed" }.map { it.id }) {
        val completedRideIds = selectedRides.values.filter { it.status == "completed" }.map { it.id }
        val rated = completedRideIds.filter { rideId ->
            runCatching { repository.hasSubmittedRating(rideId, driverId) }.getOrDefault(false)
        }.toSet()
        ratedRideIds = rated
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("طلبات السائق", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        when (approved) {
            null -> CircularProgressIndicator()
            false -> Text("حسابك بانتظار اعتماد الإدارة.", color = Color(0xFFB3261E))
            true -> {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                    containerColor = if (subscription?.active == true) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                )) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("الاشتراك الشهري", fontWeight = FontWeight.Bold)
                        if (pendingSubscription != null) {
                            Text("طلبك بمبلغ ${pendingSubscription!!.amount} جنيه قيد مراجعة المشرف.", color = Color(0xFF9A5B00))
                        }
                        driverStats?.let { summary ->
                            Text("رحلات مكتملة: ${summary.completedRides} • أرباح تقريبية: ${summary.totalEarnings} جنيه")
                            Text("متوسط التقييم: ${if (summary.ratingCount == 0) "—" else "%.1f/5".format(summary.averageRating)} (${summary.ratingCount})", color = Color.Gray)
                        }
                        Text(if (subscription?.active == true) {
                            "نشط حتى ${java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("ar"))
                                .format(java.util.Date(subscription!!.expiresAt!!))} • متبقي ${subscription!!.daysLeft} يوم"
                        } else "غير نشط • ${if (subscription?.plan == "none") 100 else 200} جنيه للشهر")
                        Text("تحويل فودافون كاش: 01069631950", color = Color.Gray)
                        TextButton(enabled = !subscriptionBusy, onClick = {
                            scope.launch {
                                runCatching {
                                    subscription = repository.getDriverSubscription(driverId)
                                    pendingSubscription = repository.getPendingSubscription(driverId)
                                    online = repository.getDriverAvailability(driverId) && subscription?.active == true
                                }.onFailure { error = it.localizedMessage ?: "تعذر تحديث حالة الاشتراك" }
                            }
                        }) { Text("تحديث حالة الاشتراك") }
                        OutlinedButton(onClick = { proofPicker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }, enabled = !subscriptionBusy) {
                            Text(if (selectedProof == null) "اختيار صورة إثبات التحويل" else "تغيير صورة الإثبات")
                        }
                        if (selectedProof != null) Text("تم اختيار الصورة", color = Color(0xFF0D7C3E))
                        proofPreview?.let {
                            Image(it, contentDescription = "معاينة إثبات التحويل", modifier = Modifier.fillMaxWidth().height(150.dp))
                        }
                        OutlinedTextField(
                            value = subscriptionNote,
                            onValueChange = { subscriptionNote = it.take(300) },
                            label = { Text("ملاحظة للمشرف (اختياري)") },
                            enabled = !subscriptionBusy,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            enabled = selectedProof != null && !subscriptionBusy && subscription?.active != true && pendingSubscription == null,
                            onClick = {
                                val proof = selectedProof ?: return@Button
                                val contentType = context.contentResolver.getType(proof).orEmpty()
                                subscriptionBusy = true
                                scope.launch {
                                    try {
                                        val submitted = repository.submitSubscriptionRequest(driverId, proof, contentType, subscriptionNote)
                                        pendingSubscription = submitted
                                        subscriptionMessage = "تم استلام إثبات التحويل لمبلغ ${submitted.amount} جنيه، والطلب بانتظار المراجعة."
                                        selectedProof = null
                                        subscriptionNote = ""
                                    } catch (e: Exception) {
                                        error = e.localizedMessage ?: "تعذر إرسال طلب الاشتراك"
                                    } finally { subscriptionBusy = false }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (subscriptionBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("إرسال إثبات الاشتراك")
                        }
                        subscriptionMessage?.let { Text(it, color = Color(0xFF0D7C3E)) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (!locationAllowed) {
                    Button(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("السماح بالموقع") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (online) "🟢 متاح لاستقبال الرحلات" else "⚪ غير متاح")
                    Switch(checked = online, enabled = locationAllowed, onCheckedChange = { target ->
                        scope.launch {
                            try {
                                repository.setDriverAvailability(driverId, target)
                                online = target
                                val serviceIntent = Intent(context, DriverLocationService::class.java).apply {
                                    putExtra(DriverLocationService.EXTRA_UID, driverId)
                                    putExtra(DriverLocationService.EXTRA_NAME, getUserName(context))
                                }
                                if (target) ContextCompat.startForegroundService(context, serviceIntent)
                                else context.stopService(serviceIntent)
                            } catch (e: Exception) { error = e.localizedMessage ?: "تعذر تغيير الحالة" }
                        }
                    })
                }
                if (error != null) Text(error!!, color = Color(0xFFB3261E))
                val visible = requests.filter { it.status == "searching" } +
                    requests.filter { it.status == "selected" }.take(10)
                if (visible.isEmpty()) Text("لا توجد طلبات متاحة حالياً.", color = Color.Gray)
                else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visible, key = { it.rideId }) { request ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${request.from} → ${request.to}", fontWeight = FontWeight.Bold)
                                Text(
                                    if (request.radiusMeters > 0) "%.2f كم | نطاق ${request.radiusMeters}م".format(request.distanceKm)
                                    else "%.2f كم | حجز مُرسل لكل السائقين المتاحين".format(request.distanceKm)
                                )
                                if (request.status == "selected") {
                                    val rs = selectedRides[request.rideId]
                                    when {
                                        rs == null -> Text("جارٍ تحديث الرحلة…", color = Color.Gray)
                                        rs.status in listOf("accepted", "driver_arriving", "driver_arrived", "in_progress") -> {
                                            Text("الراكب اختار عرضك.", color = Color(0xFF0D7C3E), fontWeight = FontWeight.Bold)
                                            DriverActionButtons(repository, driverId, rs, errorSetter = { error = it })
                                            TextButton(onClick = {
                                                scope.launch {
                                                    runCatching { selectedCustomerPhone = repository.getAcceptedCustomerPhone(request.rideId, driverId) }
                                                        .onFailure { error = it.localizedMessage }
                                                }
                                            }) { Text("عرض رقم الراكب") }
                                            selectedCustomerPhone?.let { Text("رقم الراكب: $it") }
                                        }
                                        rs.status == "completed" -> {
                                            Text("اكتملت الرحلة.", color = Color(0xFF0D7C3E))
                                            if (request.rideId !in ratedRideIds) {
                                                Button(onClick = { ratingRide = rs }, modifier = Modifier.fillMaxWidth()) {
                                                    Text("قيّم الراكب")
                                                }
                                            }
                                        }
                                        rs.status == "cancelled" -> Text("أُلغيت الرحلة.", color = Color.Gray)
                                    }
                                } else {
                                    Button(enabled = online, onClick = {
                                        scope.launch {
                                            val current = repository.getRide(request.rideId)
                                            if (current?.status == "searching") {
                                                selectedRequest = request; priceText = ""; etaText = "10"
                                            } else {
                                                error = "الرحلة لم تعد متاحة لاستقبال عروض جديدة."
                                            }
                                        }
                                    }) { Text("تقديم عرض") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { if (!sendingOffer) selectedRequest = null },
            title = { Text("عرضك للرحلة") },
            text = { Column {
                Text("${request.from} → ${request.to}")
                OutlinedTextField(value = priceText, onValueChange = { priceText = it.filter(Char::isDigit).take(6) }, label = { Text("السعر بالجنيه") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                OutlinedTextField(value = etaText, onValueChange = { etaText = it.filter(Char::isDigit).take(3) }, label = { Text("الوصول بالدقائق") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            } },
            confirmButton = { Button(enabled = !sendingOffer && priceText.toIntOrNull()?.let { it in 1..100000 } == true && etaText.toIntOrNull()?.let { it in 1..240 } == true, onClick = {
                sendingOffer = true; scope.launch {
                    try { repository.submitOffer(request.rideId, driverId, getUserName(context), priceText.toInt(), etaText.toInt()); selectedRequest = null }
                    catch (e: Exception) { error = e.localizedMessage ?: "تعذر إرسال العرض" }
                    finally { sendingOffer = false }
                }
            }) { Text("إرسال العرض") } },
            dismissButton = { TextButton(onClick = { selectedRequest = null }, enabled = !sendingOffer) { Text("إلغاء") } }
        )
    }

    ratingRide?.let { rideToRate ->
        RatingDialog(
            title = "تقييم الراكب",
            onDismiss = { ratingRide = null },
            onSubmit = { stars, comment ->
                scope.launch {
                    try {
                        repository.submitRating(rideToRate.id, driverId, stars, comment)
                        ratedRideIds = ratedRideIds + rideToRate.id
                        ratingRide = null
                    } catch (e: Exception) { error = e.localizedMessage ?: "تعذر إرسال التقييم" }
                }
            }
        )
    }
}

@Composable
private fun DriverActionButtons(repository: FirebaseRidesRepository, driverId: String, ride: RideRecord, errorSetter: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val next = when (ride.status) {
        "accepted" -> "driver_arriving" to "🚦 بدأت التوجه للراكب"
        "driver_arriving" -> "driver_arrived" to "📍 وصلت للراكب"
        "driver_arrived" -> "in_progress" to "▶️ ابدأ الرحلة"
        "in_progress" -> "completed" to "✅ إنهاء الرحلة"
        else -> null
    }
    if (next != null) {
        Button(onClick = { scope.launch { runCatching { repository.updateRideStatus(ride.id, driverId, next.first) }.onFailure { errorSetter(it.localizedMessage) } } }, modifier = Modifier.fillMaxWidth()) { Text(next.second) }
    }
    if (ride.status in listOf("accepted", "driver_arriving", "driver_arrived", "in_progress")) {
        OutlinedButton(onClick = { scope.launch { runCatching { repository.updateRideStatus(ride.id, driverId, "cancelled") }.onFailure { errorSetter(it.localizedMessage) } } }, modifier = Modifier.fillMaxWidth()) { Text("إلغاء الرحلة") }
    }
}


@Composable
private fun RatingDialog(title: String, onDismiss: () -> Unit, onSubmit: (Int, String) -> Unit) {
    var stars by remember { mutableIntStateOf(5) }
    var comment by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("اختار عدد النجوم")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    (1..5).forEach { value ->
                        TextButton(onClick = { stars = value }) {
                            Text(if (value <= stars) "★" else "☆", fontSize = 30.sp, color = Color(0xFFFFB300))
                        }
                    }
                }
                OutlinedTextField(
                    value = comment, onValueChange = { comment = it.take(300) },
                    label = { Text("ملاحظة اختيارية") }, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { Button(onClick = { onSubmit(stars, comment) }) { Text("إرسال التقييم") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("لاحقًا") } }
    )
}

private fun hasLocationPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun String.arabicRideStatus(): String = when (this) {
    "scheduled" -> "حجز مسبق"
    "searching" -> "جارٍ البحث"
    "offered" -> "وصلت عروض"
    "accepted" -> "تم اختيار السائق"
    "driver_arriving" -> "السائق في الطريق"
    "driver_arrived" -> "السائق وصل"
    "in_progress" -> "الرحلة جارية"
    "completed" -> "مكتملة"
    "cancelled" -> "ملغاة"
    "no_drivers" -> "لا يوجد سائقون"
    else -> this
}
