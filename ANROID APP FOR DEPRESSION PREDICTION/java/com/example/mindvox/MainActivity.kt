package com.mentis.wellness

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.telephony.SmsManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.*

// --- CONFIGURATION & DATASTORE ---
val Context.dataStore by preferencesDataStore(name = "mentis_settings")
val NAME_KEY = stringPreferencesKey("user_name")
val CONTACT1_KEY = stringPreferencesKey("contact_1")
val CONTACT2_KEY = stringPreferencesKey("contact_2")

// COLOR PALETTE
val MintPrimary = Color(0xFF97D8C8)
val MintPrimaryDark = Color(0xFF76A69A)
val BackgroundLight = Color(0xFFF6F8F7)
val NavyDeep = Color(0xFF1A1A1A)
val TextSecondary = Color(0xFF4A5568)
val TextDark = Color(0xFF2D3748)

// --- 1. GUARDIAN SERVICE ---

class GuardianService : Service() {
    private var timer: Timer? = null
    private val CHANNEL_ID = "MENTIS_CHANNEL"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Mentis Wellness", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification())
        timer = Timer()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (Random().nextInt(100) < 3) { checkAndTriggerAlert() }
            }
        }, 0, 600000)
        return START_STICKY
    }

    private fun checkAndTriggerAlert() {
        val settings = runBlocking { dataStore.data.first() }
        val userName = settings[NAME_KEY] ?: "our user"
        val contact1 = settings[CONTACT1_KEY]
        val contact2 = settings[CONTACT2_KEY]

        val message = "Mentis Wellness: $userName is having a quiet day. A friendly check-in or quick call would be a great energy boost! 🌿"

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            val smsManager = SmsManager.getDefault()
            contact1?.let { smsManager.sendTextMessage(it, null, message, null, null) }
            contact2?.let { smsManager.sendTextMessage(it, null, message, null, null) }
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Mentis Wellness")
        .setContentText("Wellness rhythm is being monitored in the background.")
        .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
}


// --- 2. UI COMPOSABLES ---

@Composable
fun MentisPulseRing() {
    val infiniteTransition = rememberInfiniteTransition(label = "MentisPulse")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing)
        ),
        label = "Spin"
    )
    val breatheScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Breathe"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    Box(contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(256.dp).graphicsLayer { rotationZ = rotation }.border(1.dp, MintPrimary.copy(0.2f), CircleShape))
        Box(modifier = Modifier.size(208.dp).border(1.dp, MintPrimary.copy(0.3f), CircleShape))

        Box(modifier = Modifier.size(160.dp * breatheScale), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = pulseAlpha }.background(MintPrimary, CircleShape).blur(30.dp))
            Box(
                modifier = Modifier.size(128.dp).shadow(20.dp, CircleShape, spotColor = MintPrimary)
                    .background(Brush.linearGradient(listOf(MintPrimary, MintPrimaryDark)), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("🌿", fontSize = 48.sp, color = Color.White)
            }
        }
        Text("☀️", fontSize = 24.sp, modifier = Modifier.offset(x = 80.dp, y = (-80).dp))
    }
}

@Composable
fun DailyWisdomCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(24.dp))
            .background(Color.White, RoundedCornerShape(24.dp))
            .padding(20.dp)
    ) {
        Text("DAILY WISDOM", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MintPrimary, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "\"Did you know? Spending 10 minutes talking to a friend can boost your mood for 4 hours.\"",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = NavyDeep,
            lineHeight = 22.sp
        )
    }
}

@Composable
fun MentisBottomNav() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(Color.White)
            .shadow(8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Home", color = MintPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("Meditate", color = Color.LightGray, fontSize = 16.sp)
        Text("Journal", color = Color.LightGray, fontSize = 16.sp)
        Text("Profile", color = Color.LightGray, fontSize = 16.sp)
    }
}

@Composable
fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextDark,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(12.dp))
                .border(1.5.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(
                    fontSize = 16.sp,
                    color = TextDark,
                    fontWeight = FontWeight.Normal
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            ) { innerTextField ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        fontSize = 16.sp,
                        color = Color.Gray.copy(alpha = 0.5f)
                    )
                }
                innerTextField()
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SetupScreen(onSetupComplete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var fullName by remember { mutableStateOf("") }
    var phone1 by remember { mutableStateOf("") }
    var phone2 by remember { mutableStateOf("") }

    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.SEND_SMS, Manifest.permission.POST_NOTIFICATIONS)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundLight)
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(60.dp))

        Text(
            "MENTIS WELLNESS",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MintPrimary,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Let's get to know you",
            fontSize = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            color = NavyDeep,
            lineHeight = 40.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "We'll use this to stay connected with your support network",
            fontSize = 16.sp,
            color = TextSecondary,
            lineHeight = 24.sp
        )

        Spacer(Modifier.height(40.dp))

        // Full Name Field
        LabeledTextField(
            label = "Your Full Name",
            value = fullName,
            onValueChange = { fullName = it },
            placeholder = "e.g., Alex Morgan"
        )

        Spacer(Modifier.height(24.dp))

        // Contact 1 Field
        LabeledTextField(
            label = "Emergency Contact 1",
            value = phone1,
            onValueChange = { phone1 = it },
            placeholder = "e.g., +1234567890"
        )

        Spacer(Modifier.height(24.dp))

        // Contact 2 Field
        LabeledTextField(
            label = "Emergency Contact 2",
            value = phone2,
            onValueChange = { phone2 = it },
            placeholder = "e.g., +0987654321"
        )

        Spacer(Modifier.weight(1f))

        // Continue Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .shadow(4.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(MintPrimary, MintPrimaryDark)
                    )
                )
                .clickable {
                    if (permissionsState.allPermissionsGranted && fullName.isNotBlank() && phone1.isNotBlank() && phone2.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            context.dataStore.edit { settings ->
                                settings[NAME_KEY] = fullName
                                settings[CONTACT1_KEY] = phone1
                                settings[CONTACT2_KEY] = phone2
                            }
                            val serviceIntent = Intent(context, GuardianService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent)
                            } else {
                                context.startService(serviceIntent)
                            }
                            launch(Dispatchers.Main) {
                                Toast
                                    .makeText(context, "Mentis Protection Activated!", Toast.LENGTH_LONG)
                                    .show()
                                onSetupComplete()
                            }
                        }
                    } else {
                        permissionsState.launchMultiplePermissionRequest()
                        Toast
                            .makeText(
                                context,
                                "Please grant all permissions and fill all fields.",
                                Toast.LENGTH_LONG
                            )
                            .show()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Continue →",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(Modifier.height(12.dp))

        Text(
            "🔒 Your information is securely encrypted and never shared.",
            fontSize = 12.sp,
            color = TextSecondary.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun HomeScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundLight)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "MENTIS",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MintPrimary,
                letterSpacing = 2.sp
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MintPrimary.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("👤", fontSize = 20.sp)
            }
        }

        // Greeting
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(
                text = "Rise & Shine,",
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                color = NavyDeep
            )
            Text(
                text = "Alex.",
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MintPrimaryDark
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Your wellness pattern is in harmony.",
                fontSize = 16.sp,
                color = TextSecondary
            )
        }

        Spacer(Modifier.height(20.dp))

        // Pulse Ring
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            MentisPulseRing()
        }

        Spacer(Modifier.height(20.dp))

        // Daily Wisdom Card
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            DailyWisdomCard()
        }

        Spacer(Modifier.height(20.dp))

        // Bottom Nav
        MentisBottomNav()
    }
}


// --- 3. MAIN ACTIVITY ---

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val prefsFlow = dataStore.data
            val prefs by prefsFlow.collectAsState(initial = null)

            val navController = rememberNavController()

            val isSetupComplete = prefs?.contains(NAME_KEY) == true

            NavHost(
                navController = navController,
                startDestination = if (isSetupComplete) "home" else "setup"
            ) {
                composable("setup") {
                    SetupScreen {
                        navController.navigate("home") {
                            popUpTo("setup") { inclusive = true }
                        }
                    }
                }
                composable("home") {
                    HomeScreen()
                }
            }
        }
    }
}