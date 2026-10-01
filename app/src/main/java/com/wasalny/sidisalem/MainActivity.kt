package com.wasalny.sidisalem

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.Calendar
import kotlin.math.*

val Context.dataStore by preferencesDataStore(name = "wasalny_v5")
private const val TERMS_VERSION = "2026-09-30-v1"
private fun termsAcceptedKey(uid: String) = stringPreferencesKey("terms_accepted_version_$uid")

object Config {
    const val LAT = 31.27133
    const val LON = 30.786165
    const val RADIUS_KM = 5.0
    val CENTER = Coordinate(LAT, LON)
}

data class FavPlace(val name: String, val address: String, val lat: Double, val lon: Double)

fun distKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadiusKm = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return earthRadiusKm * 2 * atan2(sqrt(a), sqrt(1 - a))
}

fun inside(lat: Double, lon: Double) =
    distKm(lat, lon, Config.LAT, Config.LON) <= Config.RADIUS_KM

suspend fun geocode(context: Context, point: Coordinate): String = withContext(Dispatchers.IO) {
    try {
        @Suppress("DEPRECATION")
        val addresses = Geocoder(context, Locale("ar")).getFromLocation(point.latitude, point.longitude, 1)
        addresses?.firstOrNull()?.getAddressLine(0)
            ?: "%.4f, %.4f".format(point.latitude, point.longitude)
    } catch (_: Exception) {
        "%.4f, %.4f".format(point.latitude, point.longitude)
    }
}

suspend fun getFavs(context: Context): List<FavPlace> {
    val raw = context.dataStore.data.first()[stringPreferencesKey("favs")] ?: "[]"
    val array = JSONArray(raw)
    return (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        FavPlace(item.getString("name"), item.getString("address"), item.getDouble("lat"), item.getDouble("lon"))
    }
}

suspend fun saveFav(context: Context, place: FavPlace) {
    val current = context.dataStore.data.first()[stringPreferencesKey("favs")] ?: "[]"
    val old = JSONArray(current)
    val next = JSONArray()
    for (index in 0 until old.length()) {
        val item = old.getJSONObject(index)
        if (item.getString("name") != place.name) next.put(item)
    }
    next.put(JSONObject().apply {
        put("name", place.name)
        put("address", place.address)
        put("lat", place.lat)
        put("lon", place.lon)
    })
    context.dataStore.edit { it[stringPreferencesKey("favs")] = next.toString() }
}

suspend fun deleteFav(context: Context, name: String) {
    val old = JSONArray(context.dataStore.data.first()[stringPreferencesKey("favs")] ?: "[]")
    val next = JSONArray()
    for (index in 0 until old.length()) {
        val item = old.getJSONObject(index)
        if (item.getString("name") != name) next.put(item)
    }
    context.dataStore.edit { it[stringPreferencesKey("favs")] = next.toString() }
}

suspend fun getUserName(context: Context): String =
    context.dataStore.data.first()[stringPreferencesKey("user_name")] ?: "مستخدم"

suspend fun getUserPhone(context: Context): String =
    context.dataStore.data.first()[stringPreferencesKey("user_phone")] ?: ""

class MainActivity : ComponentActivity() {
    private val notificationRideId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationRideId.value = intent.getStringExtra("rideId")
        intent.removeExtra("rideId")
        setContent { AppV4(notificationRideId.value) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationRideId.value = intent.getStringExtra("rideId")
        intent.removeExtra("rideId")
    }
}

private val WasalnyBrandColors = lightColorScheme(
    primary = Color(0xFF0D7C3E),
    onPrimary = Color.White,
    secondary = Color(0xFF114B3A),
    onSecondary = Color.White,
    tertiary = Color(0xFF00A45A),
    background = Color(0xFFF6F9F6),
    onBackground = Color(0xFF12211A),
    surface = Color.White,
    onSurface = Color(0xFF12211A),
    error = Color(0xFFB3261E),
    onError = Color.White,
    outline = Color(0xFFDBE7DF)
)

@Composable
private fun WasalnyAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WasalnyBrandColors,
        typography = MaterialTheme.typography,
        content = content
    )
}

@Composable
fun AppV4(notificationRideId: String? = null) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var firebaseUser by remember { mutableStateOf(FirebaseAuth.getInstance().currentUser) }
    var role by remember { mutableStateOf<String?>(null) }
    var adminMode by remember { mutableStateOf(false) }
    var showAdminLogin by remember { mutableStateOf(false) }
    var showDriverRegistration by remember { mutableStateOf(false) }
    var showCustomerProfile by remember { mutableStateOf(false) }
    var driverApproved by remember { mutableStateOf<Boolean?>(null) }
    var driverPhone by remember { mutableStateOf("") }
    var driverAdminMessage by remember { mutableStateOf("") }
    var termsAccepted by remember { mutableStateOf(false) }
    var termsLoaded by remember { mutableStateOf(false) }
    var openedNotificationRideId by remember { mutableStateOf<String?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(firebaseUser?.uid) {
        termsLoaded = false
        val uid = firebaseUser?.uid
        termsAccepted = uid != null && context.dataStore.data.first()[termsAcceptedKey(uid)] == TERMS_VERSION
        termsLoaded = true
    }

    LaunchedEffect(firebaseUser?.uid) {
        if (firebaseUser != null && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { firebaseUser = it.currentUser }
        FirebaseAuth.getInstance().addAuthStateListener(listener)
        onDispose { FirebaseAuth.getInstance().removeAuthStateListener(listener) }
    }

    DisposableEffect(firebaseUser?.uid, role) {
        val uid = firebaseUser?.uid
        if (uid == null || role != "driver") return@DisposableEffect onDispose { }
        val registration = FirebaseRidesRepository().listenDriverApplication(
            uid,
            { application ->
                if (application != null) {
                    driverApproved = application.approved
                    driverPhone = application.phone.ifBlank { driverPhone }
                    driverAdminMessage = if (application.needsMoreData) application.adminMessage else ""
                }
            },
            { error -> android.util.Log.w("WasalnyDriver", "تعذر تحديث حالة طلب السائق", error) }
        )
        onDispose { registration.remove() }
    }

    LaunchedEffect(firebaseUser?.uid, role) {
        val uid = firebaseUser?.uid ?: return@LaunchedEffect
        runCatching {
            val token = FirebaseMessaging.getInstance().token.await()
            FirebaseRidesRepository().saveFcmToken(uid, token)
        }
        val prefs = context.dataStore.data.first()
        if (role == null) role = prefs[stringPreferencesKey("role")]
        if (role == "driver") {
            val repository = FirebaseRidesRepository()
            driverApproved = repository.getDriverApproval(uid) ?: false
            driverPhone = firebaseUser?.phoneNumber ?: getUserPhone(context)
            driverAdminMessage = repository.getDriverApplication(uid)?.adminMessage.orEmpty()
        }
    }

    LaunchedEffect(
        notificationRideId,
        firebaseUser?.uid,
        termsAccepted,
        role,
        adminMode,
        showAdminLogin,
        showDriverRegistration,
        showCustomerProfile,
        driverApproved
    ) {
        val rideId = notificationRideId ?: return@LaunchedEffect
        if (rideId == openedNotificationRideId || firebaseUser == null || !termsAccepted || role == null ||
            adminMode || showAdminLogin || showDriverRegistration || showCustomerProfile ||
            (role == "driver" && driverApproved == false)
        ) return@LaunchedEffect

        openedNotificationRideId = rideId
        navController.navigate("rides?rideId=${Uri.encode(rideId)}") {
            launchSingleTop = true
        }
    }

    WasalnyAppTheme {
        when {
            firebaseUser == null -> PhoneAuthScreen { firebaseUser = FirebaseAuth.getInstance().currentUser }
            !termsLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            !termsAccepted -> TermsAndConditionsScreen(onAccept = {
                val uid = firebaseUser?.uid
                if (uid != null) {
                    scope.launch {
                        context.dataStore.edit { it[termsAcceptedKey(uid)] = TERMS_VERSION }
                        termsAccepted = true
                    }
                }
            })
            adminMode -> AdminPanel {
                FirebaseAuth.getInstance().signOut(); adminMode = false; role = null
            }
            showAdminLogin -> AdminLoginScreen(
                onBack = { showAdminLogin = false },
                onSuccess = { showAdminLogin = false; adminMode = true }
            )
            showDriverRegistration -> DriverRegistrationScreen(
                onBack = { showDriverRegistration = false },
                onComplete = { selectedRole -> showDriverRegistration = false; role = selectedRole; driverAdminMessage = "" }
            )
            showCustomerProfile -> CustomerProfileScreen(
                onBack = { showCustomerProfile = false },
                onComplete = { selectedRole -> showCustomerProfile = false; role = selectedRole }
            )
            role == null -> WelcomeV4(
                onSelect = { r ->
                    if (r == "driver") showDriverRegistration = true
                    else showCustomerProfile = true
                },
                onAdminRequest = { showAdminLogin = true }
            )
            role == "driver" && driverApproved == false -> DriverPendingApprovalScreen(
                phone = driverPhone,
                adminMessage = driverAdminMessage,
                onUpdateData = { showDriverRegistration = true },
                onLogout = {
                    scope.launch { context.dataStore.edit { it.remove(stringPreferencesKey("role")) } }
                    FirebaseAuth.getInstance().signOut(); role = null; driverApproved = null; driverPhone = ""
                }
            )
            else -> Scaffold(bottomBar = { BottomBarV4(navController, role!!) }) { padding ->
                NavHost(navController, startDestination = "home", modifier = Modifier.padding(padding)) {
                    composable("home") { HomeV4(navController, role!!) }
                    composable(
                        route = "map?destinationLat={destinationLat}&destinationLon={destinationLon}&destinationAddress={destinationAddress}",
                        arguments = listOf(
                            navArgument("destinationLat") { type = NavType.StringType; nullable = true; defaultValue = null },
                            navArgument("destinationLon") { type = NavType.StringType; nullable = true; defaultValue = null },
                            navArgument("destinationAddress") { type = NavType.StringType; nullable = true; defaultValue = null }
                        )
                    ) { entry ->
                        val lat = entry.arguments?.getString("destinationLat")?.toDoubleOrNull()
                        val lon = entry.arguments?.getString("destinationLon")?.toDoubleOrNull()
                        val address = entry.arguments?.getString("destinationAddress").orEmpty()
                        val destination = if (lat != null && lon != null) FavPlace("", address, lat, lon) else null
                        MapV4(role!!, destination) { rideId -> navController.navigate("rides?rideId=$rideId") }
                    }
                    composable("rides") { RidesV4(navController, role!!, null) }
                    composable(
                        route = "rides?rideId={rideId}",
                        arguments = listOf(navArgument("rideId") { type = NavType.StringType; nullable = true; defaultValue = null })
                    ) { entry ->
                        RidesV4(navController, role!!, entry.arguments?.getString("rideId"))
                    }
                    composable("account") { AccountV4(onAdminRequest = { showAdminLogin = true }) { navController.navigate("home"); role = null } }
                    composable("manage_favs") { ManageFavsV4() }
                }
            }
        }
    }
}

@Composable
fun WelcomeV4(onSelect: (String) -> Unit, onAdminRequest: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var secretTaps by remember { mutableIntStateOf(0) }
    var showTerms by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F8F5))
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "🕌 وصلني",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0D7C3E),
                    modifier = Modifier.clickable {
                        secretTaps++
                        if (secretTaps >= 7) {
                            secretTaps = 0
                            onAdminRequest()
                        }
                    }
                )
                Text("سيدي سالم - شبكة أمان", fontSize = 16.sp, color = Color(0xFF4D5C55), fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "تطبيق رحلات موثوق يربط الركاب مع السائقين بسرعة وأمان في نفس المنطقة.",
                    fontSize = 12.sp,
                    color = Color(0xFF64746A),
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RoleOptionCard(
                        title = "أنا راكب",
                        tag = "طلب رحلة الآن",
                        icon = "🚕",
                        onClick = {
                            scope.launch {
                                ctx.dataStore.edit { it[stringPreferencesKey("role")] = "customer" }
                                onSelect("customer")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    RoleOptionCard(
                        title = "أنا سائق",
                        tag = "استقبل الطلبات",
                        icon = "🚖",
                        onClick = { onSelect("driver") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { showTerms = true }) { Text("الشروط والأحكام", color = Color(0xFF0D7C3E), fontWeight = FontWeight.Bold) }
            }
        }
    }
    if (showTerms) {
        AlertDialog(
            onDismissRequest = { showTerms = false },
            title = { Text("الشروط والأحكام") },
            text = { TermsContent(Modifier.heightIn(max = 420.dp)) },
            confirmButton = { TextButton(onClick = { showTerms = false }) { Text("إغلاق") } }
        )
    }
}

@Composable
private fun RoleOptionCard(
    title: String,
    tag: String,
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F7F2)),
        onClick = onClick
    ) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(icon, fontSize = 28.sp)
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(tag, fontSize = 11.sp, color = Color(0xFF586C62))
        }
    }
}

private val TERMS_TEXT = """
شروط استخدام وصلني توكتوك

1. يربط التطبيق بين الراكب والسائق داخل نطاق الخدمة الظاهر في الخريطة.
2. الأجرة نقدية حاليًا، ويختار الراكب عرض السعر ووقت الوصول المناسبين قبل بدء الرحلة.
3. على الراكب تحديد نقطة ركوب ووجهة صحيحتين، وعلى الطرفين التأكد من تفاصيل الرحلة قبل التحرك.
4. إلغاء أكثر من ثلاث رحلات في اليوم بتوقيت القاهرة يؤدي إلى إيقاف طلب الرحلات لمدة 24 ساعة.
5. اشتراك السائق 100 جنيه لأول شهر و200 جنيه للتجديد، ويُفعّل بعد مراجعة إثبات التحويل.
6. يجب على السائق تقديم بيانات صحيحة والالتزام بقواعد المرور والسلامة واحترام الراكب.
7. تُستخدم بيانات الموقع أثناء البحث والرحلة لتقديم الخدمة، وتظهر بيانات التواصل للسائق المختار فقط وفق صلاحيات التطبيق.
8. ينبغي أن تكون التقييمات دقيقة ومحترمة وألا تتضمن بيانات شخصية أو محتوى مسيئًا.
9. في الطوارئ، تواصل مع خدمات الطوارئ المحلية؛ التطبيق ليس بديلًا عنها.
10. باستخدام التطبيق، يقر المستخدم بأنه قرأ هذه الشروط ويوافق عليها.
""".trimIndent()

@Composable
private fun TermsContent(modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        Text(TERMS_TEXT, fontSize = 13.sp, lineHeight = 21.sp)
    }
}

@Composable
private fun TermsAndConditionsScreen(onAccept: () -> Unit) {
    var checked by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("الشروط والأحكام", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color(0xFFF4F7F4))) {
            TermsContent(Modifier.fillMaxSize().padding(16.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { checked = it })
            Text("قرأت الشروط وأوافق عليها")
        }
        Button(onClick = onAccept, enabled = checked, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("متابعة")
        }
    }
}

@Composable
fun DriverPendingApprovalScreen(
    phone: String,
    adminMessage: String,
    onUpdateData: () -> Unit,
    onLogout: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("⏳ انتظار اعتماد الإدارة", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "تم تسجيل طلبك بنجاح.\nالرجاء انتظار موافقة الإدارة قبل استقبال طلبات الرحلات.",
            textAlign = TextAlign.Center,
            color = Color.Gray
        )
        if (adminMessage.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                Column(Modifier.padding(14.dp)) {
                    Text("مطلوب استكمال البيانات", fontWeight = FontWeight.Bold)
                    Text(adminMessage)
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onUpdateData, modifier = Modifier.fillMaxWidth()) { Text("تحديث بياناتي") }
        }
        Spacer(Modifier.height(20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
            Column(Modifier.padding(16.dp)) {
                Text("معلومات الطلب", fontWeight = FontWeight.Bold)
                Text("- رقم الهاتف: ${phone.ifBlank { "غير متوفر" }}")
                Text("- الحالة: بانتظار الموافقة")
                Text("- نوع الرخصة: حسب نموذج التسجيل")
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onLogout, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("تسجيل الخروج")
        }
    }
}

@Composable
fun DriverRegistrationScreen(
    onBack: () -> Unit,
    onComplete: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { FirebaseRidesRepository() }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var licenseType by remember { mutableStateOf("مرخص") }
    var vehicleType by remember { mutableStateOf("توك توك") }
    var idCardImage by remember { mutableStateOf<Uri?>(null) }
    var vehicleImage by remember { mutableStateOf<Uri?>(null) }
    var profileImage by remember { mutableStateOf<Uri?>(null) }
    var existingApplication by remember { mutableStateOf<DriverApplication?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val idCardPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { idCardImage = it }
    val vehiclePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { vehicleImage = it }
    val profilePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { profileImage = it }

    LaunchedEffect(Unit) {
        val savedName = getUserName(context)
        val savedPhone = getUserPhone(context)
        if (savedName != "مستخدم") name = savedName
        phone = FirebaseAuth.getInstance().currentUser?.phoneNumber ?: savedPhone
        FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
            existingApplication = runCatching { repository.getDriverApplication(uid) }.getOrNull()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("تسجيل السائق", style = MaterialTheme.typography.headlineSmall)
        Text("أدخل بياناتك قبل الموافقة. سيتم مراجعة الطلب من الإدارة.", color = Color.Gray)

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("الاسم") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("رقم الموبايل الموثق") },
            modifier = Modifier.fillMaxWidth(),
            readOnly = true
        )

        OutlinedTextField(
            value = licenseType,
            onValueChange = { licenseType = it },
            label = { Text("نوع الرخصة") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = vehicleType,
            onValueChange = { vehicleType = it },
            label = { Text("نوع المركبة") },
            modifier = Modifier.fillMaxWidth()
        )

        DriverImagePickerField(
            title = "صورة البطاقة (مطلوبة)",
            selected = idCardImage != null,
            saved = !existingApplication?.idCardImagePath.isNullOrBlank(),
            onPick = { idCardPicker.launch("image/*") }
        )
        DriverImagePickerField(
            title = "صورة المركبة (مطلوبة)",
            selected = vehicleImage != null,
            saved = !existingApplication?.vehicleImagePath.isNullOrBlank(),
            onPick = { vehiclePicker.launch("image/*") }
        )
        DriverImagePickerField(
            title = "صورة شخصية (اختيارية)",
            selected = profileImage != null,
            saved = !existingApplication?.profileImagePath.isNullOrBlank(),
            onPick = { profilePicker.launch("image/*") }
        )

        if (error != null) {
            Text(error!!, color = Color(0xFFB3261E))
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                enabled = !isSubmitting
            ) { Text("رجوع") }
            Button(
                onClick = {
                    scope.launch {
                        try {
                            isSubmitting = true
                            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: repository.signInAnonymously()
                            val safePhone = phone.trim()
                            if (safePhone.isBlank() || name.trim().isBlank()) {
                                throw IllegalStateException("الاسم ورقم الهاتف مطلوبان")
                            }
                            val currentApplication = repository.getDriverApplication(uid)
                            if (idCardImage == null && currentApplication?.idCardImagePath.isNullOrBlank()) {
                                throw IllegalStateException("ارفع صورة البطاقة لإرسال الطلب")
                            }
                            if (vehicleImage == null && currentApplication?.vehicleImagePath.isNullOrBlank()) {
                                throw IllegalStateException("ارفع صورة المركبة لإرسال الطلب")
                            }
                            val safeName = name.trim()
                            val safeLicenseType = licenseType.trim().ifBlank { "مرخص" }
                            val safeVehicleType = vehicleType.trim().ifBlank { "توك توك" }
                            repository.saveUserProfile(uid, "driver", safeName, safePhone)
                            repository.saveDriverApplication(
                                uid = uid,
                                name = safeName,
                                phone = safePhone,
                                licenseType = safeLicenseType,
                                vehicleType = safeVehicleType,
                                idCardImageUrl = currentApplication?.idCardImageUrl.orEmpty(),
                                vehicleImageUrl = currentApplication?.vehicleImageUrl.orEmpty(),
                                idCardImagePath = currentApplication?.idCardImagePath.orEmpty(),
                                vehicleImagePath = currentApplication?.vehicleImagePath.orEmpty(),
                                profileImagePath = currentApplication?.profileImagePath.orEmpty()
                            )
                            val idCardPath = idCardImage?.let {
                                repository.uploadDriverApplicationImage(uid, "id-card", it, context.contentResolver.getType(it).orEmpty())
                            } ?: currentApplication?.idCardImagePath.orEmpty()
                            val vehiclePath = vehicleImage?.let {
                                repository.uploadDriverApplicationImage(uid, "vehicle", it, context.contentResolver.getType(it).orEmpty())
                            } ?: currentApplication?.vehicleImagePath.orEmpty()
                            val profilePath = profileImage?.let {
                                repository.uploadDriverApplicationImage(uid, "profile", it, context.contentResolver.getType(it).orEmpty())
                            } ?: currentApplication?.profileImagePath.orEmpty()
                            repository.saveDriverApplication(
                                uid = uid,
                                name = safeName,
                                phone = safePhone,
                                licenseType = safeLicenseType,
                                vehicleType = safeVehicleType,
                                idCardImageUrl = currentApplication?.idCardImageUrl.orEmpty(),
                                vehicleImageUrl = currentApplication?.vehicleImageUrl.orEmpty(),
                                idCardImagePath = idCardPath,
                                vehicleImagePath = vehiclePath,
                                profileImagePath = profilePath
                            )
                            context.dataStore.edit {
                                it[stringPreferencesKey("role")] = "driver"
                                it[stringPreferencesKey("user_name")] = name.trim()
                                it[stringPreferencesKey("user_phone")] = safePhone
                            }
                            onComplete("driver")
                        } catch (e: Exception) {
                            error = e.localizedMessage ?: "تعذر حفظ بيانات السائق"
                        } finally {
                            isSubmitting = false
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isSubmitting
            ) {
                if (isSubmitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("تسجيل الطلب")
            }
        }
    }
}

@Composable
private fun DriverImagePickerField(title: String, selected: Boolean, saved: Boolean, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                when {
                    selected -> "تم اختيار صورة جديدة"
                    saved -> "صورة محفوظة مع الطلب"
                    else -> "لم تُرفق صورة"
                },
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
        OutlinedButton(onClick = onPick) { Text(if (selected || saved) "تغيير" else "اختيار") }
    }
}

@Composable
fun BottomBarV4(nav: NavController, role: String) {
    val items = if (role == "driver") {
        listOf(
            Triple("home", "الرئيسية", Icons.Default.Home),
            Triple("map", "الخريطة", Icons.Default.Map),
            Triple("rides", "طلبات", Icons.Default.List),
            Triple("account", "حسابي", Icons.Default.Person)
        )
    } else {
        listOf(
            Triple("home", "روحني", Icons.Default.Home),
            Triple("map", "الخريطة", Icons.Default.Map),
            Triple("rides", "رحلاتي", Icons.Default.History),
            Triple("account", "أماني", Icons.Default.Security)
        )
    }
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route?.substringBefore('?')
    NavigationBar {
        items.forEach { (route, label, icon) ->
            NavigationBarItem(
                selected = current == route,
                onClick = { nav.navigate(route) { launchSingleTop = true } },
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label, fontSize = 10.sp) }
            )
        }
    }
}

@Composable
fun HomeV4(nav: NavController, role: String) {
    val ctx = LocalContext.current
    var favs by remember { mutableStateOf<List<FavPlace>>(emptyList()) }
    var customerStats by remember { mutableStateOf<CustomerStats?>(null) }
    LaunchedEffect(role) {
        favs = getFavs(ctx)
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (role == "customer" && uid != null) {
            runCatching { customerStats = FirebaseRidesRepository().getCustomerStats(uid) }
        }
    }

    if (role == "driver") {
        DriverHomeV4(nav)
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Color(0xFFF6F9F6)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0D7C3E))
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("أهلاً 👋", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "سيدي سالم - رحلاتك داخل 5 كم فقط",
                        fontSize = 12.sp,
                        color = Color(0xFFE6F7ED)
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        enabled = customerStats?.isBanned != true,
                        onClick = { nav.navigate("map") },
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                    ) {
                        Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = Color(0xFF0D7C3E))
                        Spacer(Modifier.width(8.dp))
                        Text("اطلب رحلة دلوقت", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0D7C3E))
                    }
                }
            }
            if (customerStats?.isBanned == true) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "طلبات الرحلات موقوفة مؤقتًا حتى ${java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("ar"))
                        .format(java.util.Date(customerStats!!.banUntil!!))}",
                    color = Color(0xFFB3261E),
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("أو اختار وجهة محفوظة", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        if (favs.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("لسه محفظتش أماكن", fontWeight = FontWeight.Bold)
                        Text("روح للخريطة واحفظ البيت أو الشغل", fontSize = 12.sp, color = Color.Gray)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { nav.navigate("map") }) { Text("افتح الخريطة") }
                    }
                }
            }
        } else {
            items(favs) { fav ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            nav.navigate(
                                "map?destinationLat=${fav.lat}&destinationLon=${fav.lon}" +
                                        "&destinationAddress=${Uri.encode(fav.address)}"
                            )
                        },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Place, null, tint = Color(0xFF0D7C3E))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(fav.name, fontWeight = FontWeight.Bold)
                            Text(fav.address, fontSize = 11.sp, color = Color.Gray, maxLines = 1)
                        }
                        Text("استخدمها", color = Color(0xFF0D7C3E), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { nav.navigate("manage_favs") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("إدارة الأماكن المحفوظة") }
        }
    }
}

@Composable
fun DriverHomeV4(nav: NavController) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF6F9F6))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF114B3A))
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("🚖 وضع السائق", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("سيدي سالم - شبكة أمان", fontSize = 12.sp, color = Color(0xFFE6F7ED))
            }
        }
        Text("التحكم السريع", fontWeight = FontWeight.Bold)
        Button(
            onClick = { nav.navigate("rides") },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D7C3E))
        ) { Text("عرض الطلبات القريبة") }
        OutlinedButton(
            onClick = { nav.navigate("map") },
            modifier = Modifier.fillMaxWidth()
        ) { Text("فتح الخريطة") }
    }
}

@Composable
private fun OpenStreetMapView(
    modifier: Modifier,
    pickup: Coordinate?,
    dropoff: Coordinate?,
    pickupAddress: String,
    dropoffAddress: String,
    mapCenter: Coordinate,
    mapZoom: Float,
    onMapClick: (Coordinate) -> Unit
) {
    val latestOnMapClick by rememberUpdatedState(onMapClick)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            Configuration.getInstance().userAgentValue = context.packageName
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(mapZoom.toDouble())
                controller.setCenter(OsmGeoPoint(mapCenter.latitude, mapCenter.longitude))
                tag = mapCenter to mapZoom
                overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(point: OsmGeoPoint?): Boolean {
                        point ?: return false
                        latestOnMapClick(Coordinate(point.latitude, point.longitude))
                        return true
                    }

                    override fun longPressHelper(point: OsmGeoPoint?): Boolean = false
                }))
                overlays.add(CopyrightOverlay(context))
                onResume()
            }
        },
        update = { map ->
            val target = mapCenter to mapZoom
            if (map.tag != target) {
                map.controller.setCenter(OsmGeoPoint(mapCenter.latitude, mapCenter.longitude))
                map.controller.setZoom(mapZoom.toDouble())
                map.tag = target
            }
            map.overlays.removeAll(map.overlays.filter {
                it is Marker || it is Polyline || it is Polygon
            })

            val serviceArea = Polygon().apply {
                points = Polygon.pointsAsCircle(
                    OsmGeoPoint(Config.CENTER.latitude, Config.CENTER.longitude),
                    Config.RADIUS_KM * 1000
                )
                fillPaint.color = android.graphics.Color.argb(34, 13, 124, 62)
                outlinePaint.color = android.graphics.Color.rgb(13, 124, 62)
                outlinePaint.strokeWidth = 2f
            }
            map.overlays.add(serviceArea)

            if (pickup != null && dropoff != null) {
                map.overlays.add(Polyline().apply {
                    setPoints(
                        listOf(
                            OsmGeoPoint(pickup.latitude, pickup.longitude),
                            OsmGeoPoint(dropoff.latitude, dropoff.longitude)
                        )
                    )
                    outlinePaint.color = android.graphics.Color.rgb(13, 124, 62)
                    outlinePaint.strokeWidth = 8f
                })
            }
            pickup?.let { point ->
                map.overlays.add(Marker(map).apply {
                    position = OsmGeoPoint(point.latitude, point.longitude)
                    title = "من هنا"
                    snippet = pickupAddress
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
            }
            dropoff?.let { point ->
                map.overlays.add(Marker(map).apply {
                    position = OsmGeoPoint(point.latitude, point.longitude)
                    title = "إلى هنا"
                    snippet = dropoffAddress
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
            }
            map.invalidate()
        },
        onRelease = { map ->
            map.onPause()
            map.onDetach()
        }
    )
}

@Composable
fun MapV4(
    role: String,
    initialDestination: FavPlace? = null,
    onRideCreated: (String) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val fused = remember { LocationServices.getFusedLocationProviderClient(ctx) }
    var pickup by remember { mutableStateOf<Coordinate?>(null) }
    var dropoff by remember(initialDestination) {
        mutableStateOf(initialDestination?.let { Coordinate(it.lat, it.lon) })
    }
    var mapCenter by remember { mutableStateOf(Config.CENTER) }
    var mapZoom by remember { mutableFloatStateOf(14.5f) }
    var pickupAddr by remember { mutableStateOf("") }
    var dropoffAddr by remember(initialDestination) {
        mutableStateOf(initialDestination?.address.orEmpty())
    }
    var selectingPickup by remember(initialDestination) {
        mutableStateOf(initialDestination == null)
    }
    var femaleMode by remember { mutableStateOf(false) }
    var withLuggage by remember { mutableStateOf(false) }
    var bookingType by remember { mutableStateOf("now") }
    var scheduledAt by remember { mutableStateOf<Long?>(null) }
    var scheduledLabel by remember { mutableStateOf("") }
    var showSave by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    var showConfirm by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var resultMsg by remember { mutableStateOf<String?>(null) }
    val hasLocationPermission =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    val permLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) {
                scope.launch {
                    try {
                        val loc = fused.getCurrentLocation(
                            Priority.PRIORITY_HIGH_ACCURACY,
                            CancellationTokenSource().token
                        ).await() ?: fused.lastLocation.await()
                        loc?.let {
                            if (pickup == null) {
                                val ll = Coordinate(it.latitude, it.longitude)
                                pickup = ll
                                pickupAddr = geocode(ctx, ll)
                                mapCenter = ll
                                mapZoom = 16f
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }
    val km =
        if (pickup != null && dropoff != null)
            distKm(
                pickup!!.latitude, pickup!!.longitude,
                dropoff!!.latitude, dropoff!!.longitude
            )
        else 0.0
    val isInside = pickup?.let { inside(it.latitude, it.longitude) } ?: true

    Box(Modifier.fillMaxSize()) {
        OpenStreetMapView(
            modifier = Modifier.fillMaxSize(),
            pickup = pickup,
            dropoff = dropoff,
            pickupAddress = pickupAddr,
            dropoffAddress = dropoffAddr,
            mapCenter = mapCenter,
            mapZoom = mapZoom,
            onMapClick = { point ->
                if (selectingPickup) {
                    pickup = point
                    pickupAddr = "جاري تحديد العنوان..."
                    selectingPickup = false
                    scope.launch { pickupAddr = geocode(ctx, point) }
                } else {
                    dropoff = point
                    dropoffAddr = "جاري تحديد العنوان..."
                    scope.launch { dropoffAddr = geocode(ctx, point) }
                }
            }
        )

        Text(
            "© OpenStreetMap contributors",
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 230.dp),
            color = Color.DarkGray,
            fontSize = 10.sp
        )

        // Top controls
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .align(Alignment.TopCenter)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.95f)),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectingPickup,
                            onClick = { selectingPickup = true },
                            label = { Text("📍 من", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = !selectingPickup,
                            onClick = { selectingPickup = false },
                            label = { Text("🏁 إلى", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = femaleMode,
                            onClick = { femaleMode = !femaleMode },
                            label = { Text("👩 وضع الستات", fontSize = 10.sp) }
                        )
                    }
                    Text(
                        if (selectingPickup) "اضغط على الخريطة لاختيار نقطة البداية"
                        else "اضغط على الخريطة لاختيار الوجهة",
                        fontSize = 11.sp,
                        color = Color(0xFF0D7C3E),
                        fontWeight = FontWeight.Medium
                    )
                    if (pickupAddr.isNotEmpty())
                        Text("من: $pickupAddr", fontSize = 10.sp, maxLines = 1)
                    if (dropoffAddr.isNotEmpty())
                        Text("إلى: $dropoffAddr", fontSize = 10.sp, maxLines = 1)
                    if (!isInside)
                        Text(
                            "⚠️ خارج نطاق 5 كم",
                            color = Color.Red,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    FilterChip(
                        selected = withLuggage,
                        onClick = { withLuggage = !withLuggage },
                        label = { Text("📦 حمولة", fontSize = 10.sp) }
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = bookingType == "now", onClick = { bookingType = "now"; scheduledAt = null; scheduledLabel = "" }, label = { Text("🚖 الآن") })
                        FilterChip(selected = bookingType == "school", onClick = { bookingType = "school" }, label = { Text("🏫 حجز مدارس") })
                    }
                    if (bookingType == "school") {
                        OutlinedButton(
                            onClick = {
                                val now = Calendar.getInstance().apply { add(Calendar.MINUTE, 30) }
                                DatePickerDialog(ctx, { _, y, m, d ->
                                    TimePickerDialog(ctx, { _, hour, minute ->
                                        val chosen = Calendar.getInstance().apply { set(y, m, d, hour, minute, 0); set(Calendar.MILLISECOND, 0) }
                                        if (chosen.timeInMillis > System.currentTimeMillis() + 5 * 60_000) { scheduledAt = chosen.timeInMillis; scheduledLabel = String.format(Locale.US, "%02d/%02d %02d:%02d", d, m + 1, hour, minute) }
                                        else { resultMsg = "اختار موعدًا بعد 5 دقائق على الأقل" }
                                    }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
                                }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
                            }, Modifier.fillMaxWidth()
                        ) { Text(if (scheduledLabel.isBlank()) "اختيار موعد الحجز" else "الموعد: $scheduledLabel") }
                    }
                }
            }
        }

        // Bottom card
        Card(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                if (pickup != null && dropoff != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("المسافة: %.2f كم تقريباً".format(km), fontWeight = FontWeight.Bold)
                        Text("السائقون يرسلون السعر", color = Color(0xFF0D7C3E), fontSize = 12.sp)
                    }
                    if (femaleMode)
                        Text(
                            "سيتم إرسال تفضيل وضع السيدات مع الطلب",
                            fontSize = 10.sp,
                            color = Color(0xFF880E4F)
                        )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { showConfirm = true },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        enabled = isInside && !isLoading && role == "customer" && (bookingType == "now" || scheduledAt != null)
                    ) {
                        if (isLoading) CircularProgressIndicator(
                            Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        else Text(if (bookingType == "school") "🏫 احجز رحلة المدرسة" else "✅ اطلب التوكتوك حالاً")
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { showSave = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("💾 احفظ كـ بيت") }
                } else {
                    Text(
                        "👆 اضغط على الخريطة لتحديد نقطة البداية والوجهة\n💾 احفظ الوجهات المتكررة لاختيارها بسهولة لاحقاً",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { if (!isLoading) showConfirm = false },
            title = { Text("تأكيد طلب المشوار") },
            text = {
                Text(
                    if (bookingType == "school") {
                        "من: $pickupAddr\nإلى: $dropoffAddr\nالمسافة: %.2f كم تقريباً\nموعد الحجز: ${scheduledLabel.ifBlank { "غير محدد" }}\nسيتم إرسال الطلب للسائقين تلقائياً وقت الموعد.".format(km)
                    } else {
                        "من: $pickupAddr\nإلى: $dropoffAddr\nالمسافة: %.2f كم تقريباً\nالسائقون سيرسلون عروض السعر ووقت الوصول، وبعدها تختار العرض المناسب.".format(km)
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pickup == null || dropoff == null) return@Button
                        isLoading = true
                        scope.launch {
                            try {
                                val phone = getUserPhone(ctx)
                                if (phone.isBlank()) {
                                    resultMsg = "أضف رقم هاتفك من شاشة الحساب قبل طلب الرحلة."
                                    return@launch
                                }
                                val uid = FirebaseAuth.getInstance().currentUser?.uid
                                    ?: error("انتهت جلسة Firebase")
                                val name = getUserName(ctx)
                                val rideId = FirebaseRidesRepository().createRide(
                                    customerId = uid,
                                    customerName = name,
                                    customerPhone = phone,
                                    fromAddress = pickupAddr,
                                    toAddress = dropoffAddr,
                                    from = pickup!!,
                                    to = dropoff!!,
                                    distanceKm = km,
                                    femaleMode = femaleMode,
                                    withLuggage = withLuggage,
                                    bookingType = bookingType,
                                    scheduledAt = scheduledAt
                                )
                                onRideCreated(rideId)
                            } catch (_: Exception) {
                                resultMsg = "تعذر إنشاء الطلب. تحقق من الاتصال وإعداد Firebase ثم حاول مرة أخرى."
                            } finally {
                                isLoading = false
                                showConfirm = false
                            }
                        }
                    },
                    enabled = !isLoading
                ) {
                    if (isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("تأكيد وإرسال")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }, enabled = !isLoading) {
                    Text("إلغاء")
                }
            }
        )
    }

    if (resultMsg != null) {
        AlertDialog(
            onDismissRequest = { resultMsg = null },
            title = { Text("نتيجة الطلب") },
            text = { Text(resultMsg!!) },
            confirmButton = {
                Button(onClick = { resultMsg = null }) { Text("حسناً") }
            }
        )
    }

    if (showSave) {
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("احفظ مكانك") },
            text = {
                Column {
                    OutlinedTextField(
                        value = saveName,
                        onValueChange = { saveName = it },
                        label = { Text("البيت / الشغل / مدرسة العيال") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "محفوظ على هذا الجهاز فقط",
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (saveName.isNotEmpty() && dropoff != null) {
                        scope.launch {
                            saveFav(
                                ctx,
                                FavPlace(
                                    saveName,
                                    dropoffAddr,
                                    dropoff!!.latitude,
                                    dropoff!!.longitude
                                )
                            )
                            showSave = false
                            saveName = ""
                        }
                    }
                }) { Text("حفظ") }
            },
            dismissButton = {
                TextButton(onClick = { showSave = false }) { Text("إلغاء") }
            }
        )
    }
}

@Composable
fun AccountV4(onAdminRequest: () -> Unit, onRoleChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var secretTaps by remember { mutableIntStateOf(0) }
    var role by remember { mutableStateOf("customer") }
    var subscription by remember { mutableStateOf<DriverSubscription?>(null) }

    LaunchedEffect(Unit) {
        role = ctx.dataStore.data.first()[stringPreferencesKey("role")] ?: "customer"
        name = getUserName(ctx)
        phone = FirebaseAuth.getInstance().currentUser?.phoneNumber ?: getUserPhone(ctx)
        if (name == "مستخدم") name = ""
        if (role == "driver") {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@LaunchedEffect
            subscription = runCatching { FirebaseRidesRepository().getDriverSubscription(uid) }.getOrNull()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF6F9F6))
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D7C3E))
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    "🛡️ أماني وحسابي",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.clickable {
                        secretTaps++
                        if (secretTaps >= 7) {
                            secretTaps = 0
                            onAdminRequest()
                        }
                    }
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (role == "driver") "وضع السائق" else "وضع الراكب",
                    color = Color(0xFFE9F7EE),
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("الاسم") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = FirebaseAuth.getInstance().currentUser?.phoneNumber ?: phone,
            onValueChange = { },
            label = { Text("رقم الموبايل الموثق") },
            readOnly = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                scope.launch {
                    ctx.dataStore.edit {
                        it[stringPreferencesKey("user_name")] = name.ifBlank { "مستخدم" }
                        it[stringPreferencesKey("user_phone")] = phone
                    }
                    saved = true
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D7C3E))
        ) { Text("حفظ البيانات") }
        if (saved) {
            Text("✅ تم الحفظ", color = Color(0xFF0D7C3E), fontSize = 12.sp)
        }

        if (role == "driver") {
            Spacer(Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("اشتراك السائق", fontWeight = FontWeight.Bold)
                    val planText = when (subscription?.plan) {
                        "month" -> "اشتراك شهري"
                        else -> "غير مفعل"
                    }
                    Text("الحالة: ${if (subscription?.active == true) "نشط" else "غير نشط"}")
                    Text("الخطة: $planText")
                    Text(
                        text = if (subscription?.active == true && subscription?.expiresAt != null)
                            "متبقي ${subscription!!.daysLeft} يوم"
                        else "الاشتراك يحتاج مراجعة أو تجديد"
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("رقم الطوارئ", fontWeight = FontWeight.Bold)
                Text("الطوارئ: 122", fontSize = 13.sp)
                Text("أرقام الدعم: 01069631050", fontSize = 12.sp, color = Color.Gray)
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    ctx.dataStore.edit {
                        it.remove(stringPreferencesKey("role"))
                    }
                    onRoleChanged()
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text("تغيير الدور (راكب / سائق)") }
    }
}

@Composable
fun ManageFavsV4() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var favs by remember { mutableStateOf<List<FavPlace>>(emptyList()) }
    LaunchedEffect(Unit) { favs = getFavs(ctx) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("الأماكن المحفوظة", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (favs.isEmpty()) {
            Text("مفيش أماكن محفوظة لسه", color = Color.Gray)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(favs) { fav ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(fav.name, fontWeight = FontWeight.Bold)
                                Text(fav.address, fontSize = 11.sp, color = Color.Gray, maxLines = 2)
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    deleteFav(ctx, fav.name)
                                    favs = getFavs(ctx)
                                }
                            }) {
                                Icon(Icons.Default.Delete, null, tint = Color.Red)
                            }
                        }
                    }
                }
            }
        }
    }
}
