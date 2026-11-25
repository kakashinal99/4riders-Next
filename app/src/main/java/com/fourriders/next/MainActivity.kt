package com.fourriders.next

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.fourriders.next.data.TripOffer
import com.fourriders.next.scanner.OfferParser
import com.fourriders.next.scanner.ScreenCaptureService
import com.fourriders.next.scanner.TextScanner
import kotlinx.coroutines.launch
import java.util.Locale

private val Night = Color(0xFF080D18)
private val Panel = Color(0xFF111B2B)
private val Cyan = Color(0xFF45E0D0)
private val Muted = Color(0xFF9EADC2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Night, surface = Panel)) {
                RiderHome()
            }
        }
    }
}

@Composable
private fun RiderHome() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var offer by remember { mutableStateOf(TripOffer()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Pilih screenshot atau mula imbas skrin.") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            busy = true
            status = "Sedang membaca dan menganalisis imej…"
            runCatching { TextScanner().scan(context, uri) }
                .onSuccess { text ->
                    offer = OfferParser.parse(text)
                    status = if (text.isBlank()) "Tiada teks dikesan. Cuba imej yang lebih jelas."
                    else "Analisis selesai. Sahkan butiran sebelum digunakan."
                }
                .onFailure { status = "Imej tidak dapat dibaca. Cuba pilih imej lain." }
            busy = false
        }
    }

    val screenCapture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            busy = true
            status = "Mendapatkan satu tangkapan skrin dengan kebenaran Android…"
            val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            busy = false
            status = "Kebenaran tangkapan skrin tidak diberikan."
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val text = intent?.getStringExtra(ScreenCaptureService.EXTRA_OCR_TEXT).orEmpty()
                offer = OfferParser.parse(text)
                busy = false
                status = if (text.isBlank()) "Tiada teks dapat dibaca pada skrin semasa."
                else "Tangkapan skrin dianalisis. Semak nilai sebelum digunakan."
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ScreenCaptureService.ACTION_OCR_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    Column(
        Modifier.fillMaxSize().background(Night).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("4RIDERS", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text("NEXT", color = Cyan, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            }
            Surface(color = Color(0xFF12333A), shape = RoundedCornerShape(50)) {
                Text("SCANNER LAB", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bolt, null, tint = Cyan)
                    Spacer(Modifier.width(8.dp))
                    Text("TRIP INTELLIGENCE", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Text("Kenal pasti tawaran secara automatik", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Baca tambang, jarak, tempoh dan lokasi daripada screenshot atau satu tangkapan skrin yang anda benarkan melalui Android.",
                    color = Muted, fontSize = 13.sp
                )
                Button(
                    onClick = { screenCapture.launch((context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent()) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Night),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.PhoneAndroid, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) "SCANNING…" else "SCAN CURRENT SCREEN", fontWeight = FontWeight.Black)
                }
                OutlinedButton(
                    onClick = { picker.launch("image/*") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                ) {
                    Icon(Icons.Default.AddAPhoto, null)
                    Spacer(Modifier.width(8.dp))
                    Text("PILIH SCREENSHOT")
                }
                Text("Privasi: Android akan meminta kebenaran setiap kali. 4riders Next hanya mengambil satu frame dan memproses OCR pada peranti; tiada rakaman berterusan.", color = Muted, fontSize = 11.sp)
                Text(status, color = Muted, fontSize = 12.sp)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cyan)
            }
        }

        if (offer.rawText.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("SOURCE DETECTION", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(offer.platform.uppercase(), color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("DATA COMPLETENESS  •  ${offer.dataCompleteness}%", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    LinearProgressIndicator(
                        progress = { offer.dataCompleteness / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Cyan,
                        trackColor = Color(0xFF263449)
                    )
                    Text("Skor ini mengukur medan yang berjaya diekstrak, bukan jaminan ketepatan OCR.", color = Muted, fontSize = 11.sp)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Route, null, tint = Cyan)
            Spacer(Modifier.width(8.dp))
            Text("OFFER BREAKDOWN", color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("FARE", offer.fare?.let { "RM ${money(it)}" } ?: "—", Modifier.weight(1f))
            MetricCard("DISTANCE", offer.distanceKm?.let { "${number(it)} km" } ?: "—", Modifier.weight(1f))
            MetricCard("TIME", offer.durationMin?.let { "$it min" } ?: "—", Modifier.weight(1f))
        }

        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("ROUTE RECOGNITION", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                RouteLine("PICKUP", offer.pickup ?: "Belum dikenal pasti", Cyan)
                HorizontalDivider(color = Color(0xFF263449))
                RouteLine("DROP-OFF", offer.dropoff ?: "Belum dikenal pasti", Color(0xFFFFB86B))
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("EARNING RATE", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Per kilometre", color = Muted, fontSize = 12.sp)
                        Text(offer.perKm?.let { "RM ${money(it)}" } ?: "—", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Per hour (estimated)", color = Muted, fontSize = 12.sp)
                        Text(offer.perHour?.let { "RM ${money(it)}" } ?: "—", color = Cyan, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text("Kiraan sejam berdasarkan tempoh tawaran sahaja; belum mengambil kira perjalanan ke pickup, menunggu, tol atau kos operasi.", color = Color(0xFF8292A8), fontSize = 11.sp)
            }
        }

        if (offer.confidenceNotes.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2418)), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("PERLU SEMAK", color = Color(0xFFFFD18A), fontWeight = FontWeight.Bold)
                    offer.confidenceNotes.forEach { Text("• $it", color = Color(0xFFE8D7B8), fontSize = 12.sp) }
                }
            }
        }

        if (offer.rawText.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("OCR SOURCE TEXT", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(offer.rawText, color = Color(0xFFB8C5D8), fontSize = 12.sp)
                }
            }
        }
        Text("4riders Next • Scanner engine 0.4.0", color = Color(0xFF58677C), fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun RouteLine(label: String, value: String, accent: Color) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = accent.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp)) {
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                Text(if (label == "PICKUP") "A" else "B", color = accent, fontWeight = FontWeight.Black)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = Color(0xFF91A2B9), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun money(v: Double) = String.format(Locale.US, "%.2f", v)
private fun number(v: Double) = String.format(Locale.US, "%.1f", v)
