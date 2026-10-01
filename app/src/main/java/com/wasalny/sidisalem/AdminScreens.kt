package com.wasalny.sidisalem
import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

private fun toEgyptianPhone(value: String): String {
    val digits = value.filter(Char::isDigit)
    return when {
        digits.startsWith("00") -> "+${digits.drop(2)}"
        digits.startsWith("20") -> "+$digits"
        digits.startsWith("0") -> "+20${digits.drop(1)}"
        else -> "+$digits"
    }
}

@Composable
fun AdminLoginScreen(onBack: () -> Unit, onSuccess: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val auth = remember { FirebaseAuth.getInstance() }
    val repository = remember { FirebaseRidesRepository() }
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var verificationId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun finishCredential(credential: PhoneAuthCredential) {
        busy = true
        scope.launch {
            try {
                val result = auth.signInWithCredential(credential).await()
                val uid = result.user?.uid ?: error("تعذر قراءة حساب Firebase")
                if (!repository.isAdmin(uid)) {
                    auth.signOut()
                    throw IllegalStateException("هذا الرقم غير مسجل كمشرف")
                }
                onSuccess()
            } catch (e: Exception) {
                error = e.localizedMessage ?: "تعذر تسجيل دخول المشرف"
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("دخول المشرف", style = MaterialTheme.typography.headlineSmall)
        Text("هذه الشاشة لا تظهر في التنقل العادي.", color = Color.Gray)
        Spacer(Modifier.size(16.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it.filter { char -> char.isDigit() || char == '+' }.take(16) },
            label = { Text("رقم المشرف") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            enabled = verificationId == null && !busy
        )
        if (verificationId != null) {
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = { Text("كود SMS") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !busy
            )
        }
        if (error != null) {
            Spacer(Modifier.size(8.dp))
            Text(error!!, color = Color(0xFFB3261E))
        }
        Spacer(Modifier.size(12.dp))
        Button(
            enabled = !busy && if (verificationId == null) phone.filter(Char::isDigit).length >= 10 else code.length == 6,
            onClick = {
                error = null
                if (verificationId == null) {
                    val currentActivity = activity
                    if (currentActivity == null) {
                        error = "تعذر فتح تحقق الهاتف"
                    } else {
                        busy = true
                        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                                finishCredential(credential)
                            }

                            override fun onVerificationFailed(exception: FirebaseException) {
                                busy = false
                                error = exception.localizedMessage ?: "فشل إرسال كود التحقق"
                            }

                            override fun onCodeSent(
                                id: String,
                                token: PhoneAuthProvider.ForceResendingToken
                            ) {
                                verificationId = id
                                busy = false
                            }
                        }
                        val options = PhoneAuthOptions.newBuilder(auth)
                            .setPhoneNumber(toEgyptianPhone(phone))
                            .setTimeout(60L, TimeUnit.SECONDS)
                            .setActivity(currentActivity)
                            .setCallbacks(callbacks)
                            .build()
                        PhoneAuthProvider.verifyPhoneNumber(options)
                    }
                } else {
                    finishCredential(PhoneAuthProvider.getCredential(verificationId!!, code))
                }
            }
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(if (verificationId == null) "إرسال كود SMS" else "دخول")
        }
        TextButton(onClick = onBack, enabled = !busy) { Text("رجوع") }
    }
}

@Composable
fun AdminPanel(onLogout: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("السائقون", "الاشتراكات", "الرحلات", "التقييمات")
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("لوحة المشرف", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            TextButton(onClick = onLogout) { Text("خروج") }
        }
        ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 0.dp) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
            }
        }
        when (selectedTab) {
            0 -> AdminDriversTab()
            1 -> AdminSubscriptionsTab()
            2 -> AdminRidesTab()
            3 -> AdminRatingsTab()
        }
    }
}

@Composable
private fun AdminDriversTab() {
    val repository = remember { FirebaseRidesRepository() }
    val scope = rememberCoroutineScope()
    var drivers by remember { mutableStateOf<List<DriverCandidate>>(emptyList()) }
    var allDrivers by remember { mutableStateOf<List<DriverApplication>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedDriver by remember { mutableStateOf<DriverCandidate?>(null) }
    var savingUid by remember { mutableStateOf<String?>(null) }
    var driverDetails by remember { mutableStateOf<DriverApplication?>(null) }

    DisposableEffect(Unit) {
        val registration = repository.listenPendingDrivers(
            { drivers = it },
            { error = it.localizedMessage ?: "تعذر تحميل طلبات السائقين" }
        )
        onDispose { registration.remove() }
    }

    LaunchedEffect(Unit) {
        runCatching { allDrivers = repository.listAllDrivers() }
            .onFailure { error = it.localizedMessage ?: "تعذر تحميل قائمة السائقين" }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("طلبات السائقين المنتظرين: ${drivers.size}", color = Color.Gray)
        if (error != null) Text(error!!, color = Color(0xFFB3261E))
        Spacer(Modifier.size(12.dp))
        if (drivers.isEmpty()) {
            Text("لا توجد طلبات سائقين جديدة.", color = Color.Gray)
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(drivers, key = { it.uid }) { driver ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(driver.displayName, style = MaterialTheme.typography.titleMedium)
                            Text("UID: ${driver.uid}", color = Color.Gray)
                            Text("الموقع: %.5f, %.5f".format(driver.lat, driver.lon), color = Color.Gray)
                            Text(if (driver.available) "متصل" else "غير متصل", color = Color.Gray)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = savingUid == null,
                                    onClick = {
                                        selectedDriver = driver
                                        scope.launch {
                                            driverDetails = repository.getDriverApplication(driver.uid)
                                        }
                                    }
                                ) { Text("مراجعة وقبول") }
                            }
                            OutlinedButton(
                                enabled = savingUid == null,
                                onClick = {
                                    savingUid = driver.uid
                                    scope.launch {
                                        try {
                                            repository.requestDriverMoreData(driver.uid, "يرجى استكمال البيانات والصور المطلوبة ثم إعادة إرسال الطلب.")
                                        } catch (e: Exception) {
                                            error = e.localizedMessage ?: "تعذر إرسال طلب الاستكمال"
                                        } finally { savingUid = null }
                                    }
                                }
                            ) { Text("طلب استكمال البيانات") }
                        }
                    }
                }
            }
        }
        Text("السائقون المعتمدون", fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(allDrivers.filter { it.approved }, key = { "approved-${it.uid}" }) { driver ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(driver.name, fontWeight = FontWeight.Medium)
                        Text("${driver.phone} • ${driver.vehicleType}", color = Color.Gray)
                    }
                }
            }
        }
    }

    selectedDriver?.let { driver ->
        AlertDialog(
            onDismissRequest = { if (savingUid == null) selectedDriver = null },
            title = { Text("اعتماد السائق") },
            text = {
                Column {
                    Text("هل تريد اعتماد ${driver.displayName} لاستقبال طلبات الرحلات؟")
                    Spacer(Modifier.size(8.dp))
                    Text("الاسم: ${driverDetails?.name ?: driver.displayName}", color = Color.Gray)
                    Text("رقم الهاتف: ${driverDetails?.phone ?: "غير متوفر"}", color = Color.Gray)
                    Text("نوع الرخصة: ${driverDetails?.licenseType ?: "غير محدد"}", color = Color.Gray)
                    Text("نوع المركبة: ${driverDetails?.vehicleType ?: "غير محدد"}", color = Color.Gray)
                    if (!driverDetails?.idCardImageUrl.isNullOrBlank()) {
                        Text("بطاقة: ${driverDetails!!.idCardImageUrl}", color = Color.Gray)
                    }
                    if (!driverDetails?.vehicleImageUrl.isNullOrBlank()) {
                        Text("مركبة: ${driverDetails!!.vehicleImageUrl}", color = Color.Gray)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = savingUid == null,
                    onClick = {
                        savingUid = driver.uid
                        scope.launch {
                            try {
                                repository.setDriverApproval(driver.uid, true)
                                allDrivers = repository.listAllDrivers()
                                selectedDriver = null
                                driverDetails = null
                            } catch (e: Exception) {
                                error = e.localizedMessage ?: "تعذر اعتماد السائق"
                            } finally {
                                savingUid = null
                            }
                        }
                    }
                ) { Text("اعتماد") }
            },
            dismissButton = {
                TextButton(onClick = { selectedDriver = null }, enabled = savingUid == null) {
                    Text("إلغاء")
                }
            }
        )
    }
}

@Composable
private fun AdminSubscriptionsTab() {
    val repository = remember { FirebaseRidesRepository() }
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<SubscriptionRequest>>(emptyList()) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var proofRequest by remember { mutableStateOf<SubscriptionRequest?>(null) }
    var proofBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    suspend fun refresh() {
        pending = repository.listPendingSubscriptions()
    }
    LaunchedEffect(Unit) {
        runCatching { refresh() }.onFailure { error = it.localizedMessage ?: "تعذر تحميل الاشتراكات" }
    }

    Column(Modifier.fillMaxSize()) {
        Text("طلبات اشتراك السائقين", fontWeight = FontWeight.Bold)
        Text("١٠٠ جنيه لأول شهر • ٢٠٠ جنيه للتجديد", color = Color.Gray)
        if (error != null) Text(error!!, color = Color(0xFFB3261E))
        if (pending.isEmpty()) Text("لا توجد طلبات اشتراك قيد المراجعة.", color = Color.Gray)
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(pending, key = { it.id }) { subscription ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(subscription.driverName, fontWeight = FontWeight.Bold)
                        Text("${subscription.amount} جنيه • شهر واحد")
                        if (subscription.note.isNotBlank()) Text(subscription.note, color = Color.Gray)
                        OutlinedButton(enabled = busyId == null, onClick = {
                            proofRequest = subscription
                            proofBitmap = null
                            scope.launch {
                                runCatching {
                                    val bytes = repository.getSubscriptionProof(subscription.proofPath)
                                    proofBitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                }.onFailure { error = it.localizedMessage ?: "تعذر تحميل صورة الإثبات" }
                            }
                        }) { Text("معاينة صورة التحويل") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = busyId == null, onClick = {
                                busyId = subscription.id
                                scope.launch {
                                    try { repository.reviewSubscriptionRequest(subscription.id, true); refresh() }
                                    catch (e: Exception) { error = e.localizedMessage ?: "تعذر اعتماد الاشتراك" }
                                    finally { busyId = null }
                                }
                            }) { Text("قبول وتجديد") }
                            OutlinedButton(enabled = busyId == null, onClick = {
                                busyId = subscription.id
                                scope.launch {
                                    try { repository.reviewSubscriptionRequest(subscription.id, false); refresh() }
                                    catch (e: Exception) { error = e.localizedMessage ?: "تعذر رفض الاشتراك" }
                                    finally { busyId = null }
                                }
                            }) { Text("رفض") }
                        }
                    }
                }
            }
        }
    }

    proofRequest?.let { selected ->
        AlertDialog(
            onDismissRequest = { proofRequest = null },
            title = { Text("إثبات التحويل • ${selected.driverName}") },
            text = {
                proofBitmap?.let { Image(it, contentDescription = "صورة إثبات التحويل", modifier = Modifier.fillMaxWidth().height(320.dp)) }
                    ?: CircularProgressIndicator()
            },
            confirmButton = { TextButton(onClick = { proofRequest = null }) { Text("إغلاق") } }
        )
    }
}

@Composable
private fun AdminRidesTab() {
    val repository = remember { FirebaseRidesRepository() }
    var recentRides by remember { mutableStateOf<List<RideRecord>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { recentRides = repository.listRecentRides(50) }
            .onFailure { error = it.localizedMessage ?: "تعذر تحميل الرحلات" }
    }
    Column(Modifier.fillMaxSize()) {
        Text("آخر الرحلات", fontWeight = FontWeight.Bold)
        if (error != null) Text(error!!, color = Color(0xFFB3261E))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(recentRides, key = { it.id }) { ride ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text("${ride.from} → ${ride.to}", fontWeight = FontWeight.Medium)
                        val status = when (ride.status) {
                            "completed" -> "مكتملة"
                            "cancelled" -> "ملغاة"
                            "accepted" -> "مقبولة"
                            "searching" -> "جارٍ البحث"
                            "in_progress" -> "جارية"
                            else -> ride.status
                        }
                        Text("$status • %.1f كم • ${ride.selectedPrice?.let { "$it ج" } ?: "—"}", color = Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminRatingsTab() {
    val repository = remember { FirebaseRidesRepository() }
    var ratings by remember { mutableStateOf<List<RatingRecord>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { ratings = repository.listRecentRatings(40) }
            .onFailure { error = it.localizedMessage ?: "تعذر تحميل التقييمات" }
    }
    Column(Modifier.fillMaxSize()) {
        Text("آخر التقييمات", fontWeight = FontWeight.Bold)
        if (error != null) Text(error!!, color = Color(0xFFB3261E))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ratings, key = { it.id }) { rating ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        val stars = rating.stars.coerceIn(0, 5)
                        Text("${"★".repeat(stars)}${"☆".repeat(5 - stars)}  $stars/5", fontWeight = FontWeight.Bold)
                        if (rating.comment.isNotBlank()) Text(rating.comment)
                        Text("رحلة ${rating.rideId.take(8)}…", color = Color.Gray)
                    }
                }
            }
        }
    }
}
