package com.wasalny.sidisalem

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
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
    val repository = remember { FirebaseRidesRepository() }
    val scope = rememberCoroutineScope()
    var drivers by remember { mutableStateOf<List<DriverCandidate>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedDriver by remember { mutableStateOf<DriverCandidate?>(null) }
    var savingUid by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val registration = repository.listenPendingDrivers(
            { drivers = it },
            { error = it.localizedMessage ?: "تعذر تحميل طلبات السائقين" }
        )
        onDispose { registration.remove() }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("لوحة المشرف", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onLogout) { Text("خروج") }
        }
        Text("طلبات السائقين المنتظرين: ${drivers.size}", color = Color.Gray)
        if (error != null) Text(error!!, color = Color(0xFFB3261E))
        Spacer(Modifier.size(12.dp))
        if (drivers.isEmpty()) {
            Text("لا توجد طلبات سائقين جديدة.", color = Color.Gray)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                    }
                                ) { Text("مراجعة وقبول") }
                                OutlinedButton(
                                    enabled = savingUid == null,
                                    onClick = {
                                        savingUid = driver.uid
                                        scope.launch {
                                            try {
                                                repository.setDriverApproval(driver.uid, false)
                                            } catch (e: Exception) {
                                                error = e.localizedMessage ?: "تعذر حفظ الرفض"
                                            } finally {
                                                savingUid = null
                                            }
                                        }
                                    }
                                ) { Text("إبقاء مرفوض") }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedDriver?.let { driver ->
        AlertDialog(
            onDismissRequest = { if (savingUid == null) selectedDriver = null },
            title = { Text("اعتماد السائق") },
            text = { Text("هل تريد اعتماد ${driver.displayName} لاستقبال طلبات الرحلات؟") },
            confirmButton = {
                Button(
                    enabled = savingUid == null,
                    onClick = {
                        savingUid = driver.uid
                        scope.launch {
                            try {
                                repository.setDriverApproval(driver.uid, true)
                                selectedDriver = null
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
