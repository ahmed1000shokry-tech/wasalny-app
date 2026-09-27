package com.wasalny.sidisalem

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlin.math.*

val Context.dataStore by preferencesDataStore(name = "wasalny_v4")

object Config {
    const val LAT = 31.27133
    const val LON = 30.786165
    const val RADIUS_KM = 5.0
    const val PHONE = "01069631950"
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppV4() }
    }
}

@Composable
fun AppV4() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var role by remember { mutableStateOf<String?>(null) }
    var adminMode by remember { mutableStateOf(false) }
    var showAdminLogin by remember { mutableStateOf(false) }
    var showDriverRegistration by remember { mutableStateOf(false) }
    var driverApproved by remember { mutableStateOf<Boolean?>(null) }
    var driverPhone by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var authAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(authAttempt) {
        try {
            FirebaseRidesRepository().signInAnonymously()
            val prefs = context.dataStore.data.first()
            role = prefs[stringPreferencesKey("role")]
            if (role == "driver") {
                val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@LaunchedEffect
                driverApproved = FirebaseRidesRepository().getDriverApproval(uid) ?: false
            }
            loaded = true
        } catch (e: Exception) {
            authError = e.localizedMessage ?: "تعذر الاتصال بـ Firebase"
        }
    }

    LaunchedEffect(role) {
        if (role == "driver") {
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid != null) {
                driverApproved = FirebaseRidesRepository().getDriverApproval(uid) ?: false
                driverPhone = getUserPhone(context)
            }
        }
    }
    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (authError == null) {
                CircularProgressIndicator()
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(authError!!, color = Color.Red, textAlign = TextAlign.Center)
                    Button(onClick = {
                        authError = null
                        authAttempt++
                    }) { Text("إعادة المحاولة") }
                }
            }
        }
        return
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0D7C3E))) {
        if (adminMode) {
            AdminPanel {
                FirebaseAuth.getInstance().signOut()
                adminMode = false
                role = null
            }
        } else if (showAdminLogin) {
            AdminLoginScreen(
                onBack = { showAdminLogin = false },
                onSuccess = {
                    showAdminLogin = false
                    adminMode = true
                }
            )
        } else if (showDriverRegistration) {
            DriverRegistrationScreen(
                onBack = { showDriverRegistration = false },
                onComplete = { selectedRole ->
                    showDriverRegistration = false
                    role = selectedRole
                }
            )
        } else if (role == null) {
            WelcomeV4(
                onSelect = { r ->
                    if (r == "driver") {
                        showDriverRegistration = true
                    } else {
                        role = r
                    }
                },
                onAdminRequest = { showAdminLogin = true }
            )
        } else if (role == "driver" && driverApproved == false) {
            DriverPendingApprovalScreen(
                phone = driverPhone,
                onLogout = {
                    scope.launch {
                        context.dataStore.edit {
                            it.remove(stringPreferencesKey("role"))
                        }
                    }
                    FirebaseAuth.getInstance().signOut()
                    role = null
                    driverApproved = null
                    driverPhone = ""
                }
            )
        } else {
            Scaffold(bottomBar = { BottomBarV4(navController, role!!) }) { padding ->
                NavHost(
                    navController,
                    startDestination = "home",
                    modifier = Modifier.padding(padding)
                ) {
                    composable("home") { HomeV4(navController, role!!) }
                    composable(
                        route = "map?destinationLat={destinationLat}&destinationLon={destinationLon}&destinationAddress={destinationAddress}",
                        arguments = listOf(
                            navArgument("destinationLat") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                            navArgument("destinationLon") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                            navArgument("destinationAddress") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            }
                        )
                    ) { entry ->
                        val lat = entry.arguments?.getString("destinationLat")?.toDoubleOrNull()
                        val lon = entry.arguments?.getString("destinationLon")?.toDoubleOrNull()
                        val address = entry.arguments?.getString("destinationAddress").orEmpty()
                        val destination = if (lat != null && lon != null) {
                            FavPlace("", address, lat, lon)
                        } else null
                        MapV4(role!!, destination) { rideId ->
                            navController.navigate("rides?rideId=$rideId")
                        }
                    }
                    composable(
                        route = "rides?rideId={rideId}",
                        arguments = listOf(
                            navArgument("rideId") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            }
                        )
                    ) { entry ->
                        RidesV4(navController, role!!, entry.arguments?.getString("rideId"))
                    }
                    composable("wallet") { WalletV4() }
                    composable("account") {
                        AccountV4(
                            onAdminRequest = { showAdminLogin = true }
                        ) {
                            navController.navigate("home") {
                                popUpTo("home") { inclusive = false }
                                launchSingleTop = true
                            }
                            role = null
                        }
                    }
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
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "🕌 وصلني",
            fontSize = 36.sp,
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
        Text("سيدي سالم - شبكة أمان", fontSize = 16.sp, color = Color.Gray)
        Spacer(Modifier.height(8.dp))
        Text(
            "التوكتوك اللي جارك ضامنه - فكرة متعملتش",
            fontSize = 11.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Text("اختر نوع الحساب", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                scope.launch {
                    ctx.dataStore.edit { it[stringPreferencesKey("role")] = "customer" }
                    onSelect("customer")
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("أنا راكب") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                onSelect("driver")
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("أنا سائق") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                onAdminRequest()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("أنا مشرف") }
        Spacer(Modifier.height(20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
            Column(Modifier.padding(12.dp)) {
                Text("✨ مميزات جديدة تنافس أوبر:", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(
                    "• وضع الستات الآمن\n• باقة العيلة\n• توكتوك بيشيل حمولة\n• بدون نت SMS\n• ثواب المسجد",
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
fun DriverPendingApprovalScreen(phone: String, onLogout: () -> Unit) {
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
    var idCardImageUrl by remember { mutableStateOf("") }
    var vehicleImageUrl by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val savedName = getUserName(context)
        val savedPhone = getUserPhone(context)
        if (savedName != "مستخدم") name = savedName
        phone = savedPhone
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
            label = { Text("رقم الموبايل") },
            modifier = Modifier.fillMaxWidth()
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

        OutlinedTextField(
            value = idCardImageUrl,
            onValueChange = { idCardImageUrl = it },
            label = { Text("رابط صورة البطاقة (اختياري حالياً)") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = vehicleImageUrl,
            onValueChange = { vehicleImageUrl = it },
            label = { Text("رابط صورة المركبة (اختياري حالياً)") },
            modifier = Modifier.fillMaxWidth()
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
                            repository.saveUserProfile(uid, "driver", name.trim(), safePhone)
                            repository.saveDriverApplication(
                                uid = uid,
                                name = name.trim(),
                                phone = safePhone,
                                licenseType = licenseType.trim().ifBlank { "مرخص" },
                                vehicleType = vehicleType.trim().ifBlank { "توك توك" },
                                idCardImageUrl = idCardImageUrl.trim(),
                                vehicleImageUrl = vehicleImageUrl.trim()
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
fun BottomBarV4(nav: NavController, role: String) {
    val items = if (role == "driver") {
        listOf(
            Triple("home", "الرئيسية", Icons.Default.Home),
            Triple("map", "الخريطة", Icons.Default.Map),
            Triple("rides", "طلبات", Icons.Default.List),
            Triple("wallet", "محفظتي", Icons.Default.AccountBalanceWallet),
            Triple("account", "حسابي", Icons.Default.Person)
        )
    } else {
        listOf(
            Triple("home", "روحني", Icons.Default.Home),
            Triple("map", "الخريطة", Icons.Default.Map),
            Triple("rides", "رحلاتي", Icons.Default.History),
            Triple("wallet", "محفظتي", Icons.Default.AccountBalanceWallet),
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
    LaunchedEffect(Unit) { favs = getFavs(ctx) }

    if (role == "driver") {
        DriverHomeV4(nav)
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("أهلاً 👋", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "📍 سيدي سالم - نطاق الخدمة 5 كم",
                fontSize = 11.sp,
                color = Color(0xFF0D7C3E)
            )
            Spacer(Modifier.height(8.dp))
                Text("🧭 اختار وجهتك من الأماكن المحفوظة", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("🚖 وضع السائق", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("سيدي سالم - شبكة أمان", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(16.dp))
        Text("طلبات الرحلات", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { nav.navigate("rides") },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("عرض الطلبات القريبة") }
        Spacer(Modifier.height(8.dp))
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
                            val ll = Coordinate(it.latitude, it.longitude)
                            pickup = ll
                            pickupAddr = geocode(ctx, ll)
                            selectingPickup = false
                            mapCenter = ll
                            mapZoom = 16f
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
                scope.launch {
                    if (selectingPickup) {
                        pickup = point
                        pickupAddr = geocode(ctx, point)
                        selectingPickup = false
                    } else {
                        dropoff = point
                        dropoffAddr = geocode(ctx, point)
                    }
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
                        enabled = isInside && !isLoading && role == "customer"
                    ) {
                        if (isLoading) CircularProgressIndicator(
                            Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        else Text("✅ اطلب التوكتوك حالاً")
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showSave = true },
                            modifier = Modifier.weight(1f)
                        ) { Text("💾 احفظ كـ بيت", fontSize = 11.sp) }
                        OutlinedButton(
                            onClick = {
                                val sms = Intent(
                                    Intent.ACTION_SENDTO,
                                    Uri.parse("smsto:${Config.PHONE}")
                                )
                                sms.putExtra(
                                    "sms_body",
                                    "طلب مشوار من $pickupAddr إلى $dropoffAddr"
                                )
                                ctx.startActivity(sms)
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("📱 طلب عبر SMS", fontSize = 11.sp) }
                    }
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
                    "من: $pickupAddr\nإلى: $dropoffAddr\nالمسافة: %.2f كم تقريباً\nالسائقون سيرسلون عروض السعر ووقت الوصول، وبعدها تختار العرض المناسب.".format(
                        km
                    )
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
                                    withLuggage = withLuggage
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
fun WalletV4() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("💳 محفظتي ونقاطي", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF4F4F4)),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("المحفظة غير مفعلة حالياً", fontWeight = FontWeight.Bold)
                Text("لن يظهر رصيد أو نقاط قبل ربط خدمة الدفع.", fontSize = 12.sp, color = Color.Gray)
            }
        }
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

    LaunchedEffect(Unit) {
        name = getUserName(ctx)
        phone = getUserPhone(ctx)
        if (name == "مستخدم") name = ""
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "🛡️ أماني وحسابي",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable {
                secretTaps++
                if (secretTaps >= 7) {
                    secretTaps = 0
                    onAdminRequest()
                }
            }
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("الاسم") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("رقم الموبايل (مطلوب لطلب الرحلة)") },
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
            modifier = Modifier.fillMaxWidth()
        ) { Text("حفظ البيانات") }
        if (saved) {
            Text("✅ تم الحفظ", color = Color(0xFF0D7C3E), fontSize = 12.sp)
        }
        Spacer(Modifier.height(20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
            Column(Modifier.padding(12.dp)) {
                Text("أرقام الطوارئ", fontWeight = FontWeight.Bold)
                Text("الدعم: ${Config.PHONE}", fontSize = 13.sp)
                Text("الطوارئ: 122", fontSize = 13.sp)
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
            modifier = Modifier.fillMaxWidth()
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
