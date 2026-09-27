package com.wasalny.sidisalem

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@Composable
fun RidesV4(nav: NavController, role: String, activeRideId: String? = null) {
    val userId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    if (role == "driver") {
        DriverRideRequestsScreen(userId)
    } else if (!activeRideId.isNullOrBlank()) {
        CustomerRideOffersScreen(activeRideId, userId, nav)
    } else {
        CustomerRideHistoryScreen(userId, nav)
    }
}

@Composable
private fun CustomerRideOffersScreen(rideId: String, customerId: String, nav: NavController) {
    val repository = remember { FirebaseRidesRepository() }
    val scope = rememberCoroutineScope()
    var ride by remember(rideId) { mutableStateOf<RideRecord?>(null) }
    var offers by remember(rideId) { mutableStateOf<List<RideOffer>>(emptyList()) }
    var stageMessage by remember(rideId) { mutableStateOf("جارٍ تجهيز البحث عن السائقين...") }
    var error by remember(rideId) { mutableStateOf<String?>(null) }
    var busyDriverId by remember(rideId) { mutableStateOf<String?>(null) }

    DisposableEffect(rideId) {
        val registrations = mutableListOf<ListenerRegistration>()
        registrations += repository.listenRide(rideId, { ride = it }, { error = it.localizedMessage })
        registrations += repository.listenOffers(rideId, { offers = it }, { error = it.localizedMessage })
        onDispose { registrations.forEach { it.remove() } }
    }

    LaunchedEffect(rideId) {
        try {
            val currentRide = repository.getRide(rideId)
                ?: throw IllegalStateException("الرحلة غير موجودة أو لا تملك صلاحية عرضها")
            ride = currentRide
            if (currentRide.status == "searching") {
                repository.runSearch(rideId, Coordinate(currentRide.fromLat, currentRide.fromLon)) { radius, count ->
                    stageMessage = if (count == 0) {
                        "لا يوجد سائق متاح في النطاق الحالي. جارٍ توسيع البحث..."
                    } else {
                        "تم إرسال الطلب لسائقين ضمن ${radius}م. ننتظر عروضهم حتى 15 ثانية..."
                    }
                }
            }
        } catch (e: Exception) {
            error = e.localizedMessage ?: "تعذر متابعة البحث"
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("عروض السائقين", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { nav.navigate("rides") }) { Text("رحلاتي") }
        }
        ride?.let { current ->
            Text("${current.from} → ${current.to}", fontWeight = FontWeight.SemiBold)
            Text("المسافة التقريبية: %.2f كم".format(current.distanceKm), color = Color.Gray)
            Spacer(Modifier.height(8.dp))
            when (current.status) {
                "searching" -> Text("${stageMessage}\nنطاق البحث الحالي: ${current.searchRadiusMeters}م")
                "offered" -> Text("انتهى البحث. اختر العرض المناسب لك.", color = Color(0xFF0D7C3E))
                "accepted" -> Text("تم اختيار السائق: ${current.selectedDriverId}", color = Color(0xFF0D7C3E))
                "no_drivers" -> Text("لم نجد سائقاً متاحاً خلال البحث حتى 5 كم.", color = Color(0xFFB3261E))
                "cancelled" -> Text("تم إلغاء الطلب.", color = Color.Gray)
                else -> Text("حالة الرحلة: ${current.status}")
            }
        }
        if (error != null) {
            Text(error!!, color = Color(0xFFB3261E))
            TextButton(onClick = {
                error = null
                scope.launch {
                    val currentRide = repository.getRide(rideId)
                    if (currentRide?.status == "searching") {
                        repository.runSearch(rideId, Coordinate(currentRide.fromLat, currentRide.fromLon)) { radius, count ->
                            stageMessage = "نطاق البحث ${radius}م، سائقون جدد: $count"
                        }
                    }
                }
            }) { Text("إعادة المحاولة") }
        }
        Spacer(Modifier.height(12.dp))
        if (offers.isEmpty()) {
            if (ride?.status == "searching") {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("أول ما يوصل عرض هيظهر هنا. البحث يتوسع من 500م حتى 5كم.", color = Color.Gray)
            } else if (ride?.status == "no_drivers") {
                Text("لا توجد عروض لهذه الرحلة.", color = Color.Gray)
            }
        } else {
            Text("العروض (${offers.size})", fontWeight = FontWeight.Bold)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(offers, key = { it.driverId }) { offer ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(offer.driverName, fontWeight = FontWeight.Bold)
                                Text("${offer.price} جنيه", color = Color(0xFF0D7C3E), fontWeight = FontWeight.Bold)
                            }
                            Text("وقت الوصول المتوقع: ${offer.etaMinutes} دقيقة")
                            when {
                                offer.status == "selected" -> Text("تم اختيار هذا العرض", color = Color(0xFF0D7C3E))
                                ride?.status in listOf("searching", "offered") -> Button(
                                    enabled = busyDriverId == null,
                                    onClick = {
                                        busyDriverId = offer.driverId
                                        scope.launch {
                                            try {
                                                repository.selectOffer(rideId, customerId, offer.driverId)
                                            } catch (e: Exception) {
                                                error = e.localizedMessage ?: "تعذر اختيار العرض"
                                            } finally {
                                                busyDriverId = null
                                            }
                                        }
                                    }
                                ) {
                                    if (busyDriverId == offer.driverId) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else Text("اختيار العرض")
                                }
                            }
                        }
                    }
                }
            }
        }
        if (ride?.status in listOf("searching", "offered")) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        try {
                            repository.cancelRide(rideId, customerId)
                        } catch (e: Exception) {
                            error = e.localizedMessage
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("إلغاء البحث") }
        }
    }
}

@Composable
private fun CustomerRideHistoryScreen(customerId: String, nav: NavController) {
    val repository = remember { FirebaseRidesRepository() }
    var rides by remember { mutableStateOf<List<RideRecord>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(customerId) {
        val registration = repository.listenCustomerRides(
            customerId,
            { rides = it },
            { error = it.localizedMessage ?: "تعذر تحميل الرحلات" }
        )
        onDispose { registration.remove() }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("رحلاتي", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        if (error != null) Text(error!!, color = Color(0xFFB3261E))
        if (rides.isEmpty() && error == null) {
            Text("لا توجد رحلات بعد.", color = Color.Gray)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rides, key = { it.id }) { ride ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${ride.from} → ${ride.to}", fontWeight = FontWeight.Bold)
                            Text("الحالة: ${ride.status.arabicRideStatus()} | %.2f كم".format(ride.distanceKm))
                            if (ride.status in listOf("searching", "offered")) {
                                TextButton(onClick = { nav.navigate("rides?rideId=${ride.id}") }) {
                                    Text("عرض العروض ومتابعة البحث")
                                }
                            }
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
    val fused = remember { LocationServices.getFusedLocationProviderClient(context) }
    var approved by remember { mutableStateOf<Boolean?>(null) }
    var locationAllowed by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    var requests by remember { mutableStateOf<List<DriverRideRequest>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedRequest by remember { mutableStateOf<DriverRideRequest?>(null) }
    var selectedCustomerPhone by remember { mutableStateOf<String?>(null) }
    var priceText by remember { mutableStateOf("") }
    var etaText by remember { mutableStateOf("10") }
    var sendingOffer by remember { mutableStateOf(false) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        locationAllowed = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    LaunchedEffect(driverId) {
        try {
            repository.ensureDriverProfile(driverId, getUserName(context))
            approved = repository.getDriverApproval(driverId)
        } catch (e: Exception) {
            error = e.localizedMessage ?: "تعذر تسجيل حساب السائق"
        }
    }

    LaunchedEffect(approved, locationAllowed) {
        if (approved == true && !locationAllowed) {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    LaunchedEffect(driverId, approved, locationAllowed) {
        if (approved == true && locationAllowed) {
            try {
                while (isActive) {
                    val location = fused.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        CancellationTokenSource().token
                    ).await()
                    if (location != null && System.currentTimeMillis() - location.time < 30_000 && location.accuracy <= 100f) {
                        repository.publishDriverLocation(
                            driverId,
                            getUserName(context),
                            Coordinate(location.latitude, location.longitude)
                        )
                        error = null
                    } else {
                        error = "تعذر الحصول على موقع حديث ودقيق. تأكد من تشغيل GPS."
                    }
                    delay(15_000)
                }
            } catch (e: Exception) {
                error = e.localizedMessage ?: "تعذر تحديث موقع السائق"
            }
        }
    }

    DisposableEffect(driverId, approved) {
        var registration: ListenerRegistration? = null
        if (approved == true) {
            registration = repository.listenDriverRequests(
                driverId,
                { requests = it },
                { error = it.localizedMessage ?: "تعذر تحميل الطلبات" }
            )
        }
        onDispose {
            registration?.remove()
            if (approved == true) scope.launch { runCatching { repository.markDriverOffline(driverId) } }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("طلبات المشاوير", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        when (approved) {
            null -> CircularProgressIndicator()
            false -> {
                Text("حساب السائق ينتظر اعتماد الإدارة قبل استقبال الطلبات.", color = Color(0xFFB3261E))
                Text("معرّف الحساب: $driverId", color = Color.Gray)
            }
            true -> {
                Text(
                    if (locationAllowed) "متصل: يتم تحديث موقعك كل 15 ثانية أثناء فتح هذه الشاشة."
                    else "اسمح بالموقع لتظهر لك الطلبات القريبة.",
                    color = Color.Gray
                )
                if (!locationAllowed) {
                    Button(onClick = {
                        locationPermissionLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        )
                    }) { Text("السماح بالموقع") }
                }
                if (error != null) Text(error!!, color = Color(0xFFB3261E))
                val visibleRequests = requests.filter { it.status == "searching" || it.status == "selected" }
                if (visibleRequests.isEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("لا توجد طلبات في نطاق موقعك حالياً.", color = Color.Gray)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(visibleRequests, key = { it.rideId }) { request ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("${request.from} → ${request.to}", fontWeight = FontWeight.Bold)
                                    Text("المسافة: %.2f كم تقريباً | نطاق الإرسال: ${request.radiusMeters}م".format(request.distanceKm))
                                    if (request.status == "selected") {
                                        Text("الراكب اختار عرضك.", color = Color(0xFF0D7C3E))
                                        TextButton(onClick = {
                                            scope.launch {
                                                try {
                                                    selectedCustomerPhone = repository.getAcceptedCustomerPhone(
                                                        request.rideId,
                                                        driverId
                                                    )
                                                } catch (e: Exception) {
                                                    error = e.localizedMessage
                                                }
                                            }
                                        }) { Text("عرض رقم التواصل") }
                                        selectedCustomerPhone?.let { Text("رقم الراكب: $it") }
                                    } else {
                                        Button(onClick = {
                                            selectedRequest = request
                                            priceText = ""
                                            etaText = "10"
                                        }) { Text("تقديم عرض سعر") }
                                    }
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
            text = {
                Column {
                    Text("من ${request.from} إلى ${request.to}")
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = it.filter(Char::isDigit).take(6) },
                        label = { Text("السعر بالجنيه") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = etaText,
                        onValueChange = { etaText = it.filter(Char::isDigit).take(3) },
                        label = { Text("وقت الوصول بالدقائق") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    if (error != null) Text(error!!, color = Color(0xFFB3261E))
                }
            },
            confirmButton = {
                Button(
                        enabled = !sendingOffer &&
                            (priceText.toIntOrNull()?.let { it in 1..100_000 } == true) &&
                            (etaText.toIntOrNull()?.let { it in 1..240 } == true),
                    onClick = {
                        val price = priceText.toInt()
                        val eta = etaText.toInt()
                        sendingOffer = true
                        scope.launch {
                            try {
                                repository.submitOffer(request.rideId, driverId, getUserName(context), price, eta)
                                selectedRequest = null
                                error = null
                            } catch (e: Exception) {
                                error = e.localizedMessage ?: "تعذر إرسال العرض"
                            } finally {
                                sendingOffer = false
                            }
                        }
                    }
                ) {
                    if (sendingOffer) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("إرسال العرض")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedRequest = null }, enabled = !sendingOffer) { Text("إلغاء") }
            }
        )
    }
}

@Composable
private fun String.arabicRideStatus(): String = when (this) {
    "searching" -> "جارٍ البحث عن سائقين"
    "offered" -> "وصلت عروض"
    "accepted" -> "تم اختيار سائق"
    "no_drivers" -> "لا يوجد سائقون"
    "cancelled" -> "ملغاة"
    else -> this
}
