package com.conduit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import okhttp3.logging.HttpLoggingInterceptor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import android.net.TrafficStats
import androidx.room.Room
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class MainActivity : ComponentActivity() {
    private var activePhase by mutableStateOf("IDLE")
    private var liveSpeedUp by mutableLongStateOf(0L)
    private var liveSpeedDown by mutableLongStateOf(0L)

    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .eventListener(object : EventListener() {
            override fun dnsStart(call: Call, domainName: String) { activePhase = "DNS: $domainName" }
            override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) { activePhase = "TCP: CONNECTING" }
            override fun secureConnectStart(call: Call) { activePhase = "TLS: HANDSHAKE" }
            override fun requestHeadersStart(call: Call) { activePhase = "TX: HEADERS" }
            override fun requestBodyStart(call: Call) { activePhase = "TX: BODY_STREAM" }
            override fun responseHeadersStart(call: Call) { activePhase = "RX: HEADERS" }
            override fun responseBodyStart(call: Call) { activePhase = "RX: BODY_STREAM" }
            override fun callEnd(call: Call) { activePhase = "IDLE" }
            override fun callFailed(call: Call, ioe: java.io.IOException) { activePhase = "ERR: FAILED" }
        })
        .connectionPool(ConnectionPool(1, 5, java.util.concurrent.TimeUnit.MINUTES))
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 1 })
        .addNetworkInterceptor { chain ->
            val original = chain.request()
            val stripped = original.newBuilder()
                .removeHeader("User-Agent")
                .removeHeader("Accept-Language")
                .header("Connection", "keep-alive")
                .header("Keep-Alive", "timeout=300")
                .header("Accept-Encoding", "identity")
                .build()
            chain.proceed(stripped)
        }
        .build()

    private val uid = (0..254).random().toByte()
    private lateinit var db: GhostDatabase
    private lateinit var squeezer: GhostSqueezer
    private val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inh2bGRmc214c2toZW1rc2xzYnltIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjI2ODgxNzksImV4cCI6MjA3ODI2NDE3OX0.5arqrx8Tt7v-hpXpo_ncoK4IX8th9IibxAuv93SSoOU"

        private fun storeLog(msg: String) {
        try {
            val file = java.io.File(filesDir, "ghost.log")
            file.appendText("\n" + msg)
        } catch (e: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // CRASH HANDLER: Save trace to disk before dying
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            storeLog("FATAL: ${throwable.message}\n${throwable.stackTraceToString()}")
            oldHandler?.uncaughtException(thread, throwable)
        }

        db = Room.databaseBuilder(applicationContext, GhostDatabase::class.java, "ghost-db")
            .createFromAsset("ghost_dict.db")
            .fallbackToDestructiveMigration()
            .build()
        squeezer = GhostSqueezer(db.dictionaryDao())

        setContent {
            var logs by remember { mutableStateOf(listOf<String>()) }
            var debugLogs = remember { mutableStateListOf<String>() }
            var showDebug by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                try {
                    val logFile = java.io.File(filesDir, "ghost.log")
                    if (logFile.exists()) {
                        logFile.readLines().takeLast(50).forEach { debugLogs.add(0, it) }
                    }
                } catch (e: Exception) {}
            }

            fun dLog(msg: String) {
                val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                val formatted = "[$time] $msg"
                debugLogs.add(0, formatted)
                storeLog(formatted)
            }
            var input by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()

            LaunchedEffect(Unit) {
                var lastUp = TrafficStats.getUidTxBytes(android.os.Process.myUid())
                var lastDown = TrafficStats.getUidRxBytes(android.os.Process.myUid())
                while(true) {
                    delay(1000)
                    val currUp = TrafficStats.getUidTxBytes(android.os.Process.myUid())
                    val currDown = TrafficStats.getUidRxBytes(android.os.Process.myUid())
                    liveSpeedUp = (currUp - lastUp).coerceAtLeast(0L)
                    liveSpeedDown = (currDown - lastDown).coerceAtLeast(0L)
                    lastUp = currUp
                    lastDown = currDown
                }
            }

            Column(modifier = Modifier.fillMaxSize().background(Color(0xFF020202)).padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column {
                        Text("CONDUIT // GHOST-MODE", color = Color(0xFF00FF41), fontSize = 12.sp)
                        Text("UID: ${uid.toInt() and 0xFF}", color = Color(0xFF004411), fontSize = 8.sp)
                    }
                    Row {
                        androidx.compose.material3.TextButton(onClick = { showDebug = !showDebug }) {
                            Text(if(showDebug) "HIDE LOGS" else "DEBUG", color = Color.Yellow, fontSize = 10.sp)
                        }
                        androidx.compose.material3.TextButton(onClick = {
                            scope.launch { 
                                dLog("SYS: INITIATING REMOTE WIPE")
                                wipeBurst { dLog("SYS: WIPE COMPLETE") }
                                logs = listOf("SESSION CLEARED")
                            }
                        }) {
                            Text("WIPE", color = Color.Red, fontSize = 10.sp)
                        }
                    }
                }

                // THE GUTS: Live Telemetry
                Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF111111)).padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("NET_PHASE: $activePhase", color = Color.White, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Text("STORAGE: ghost-db.sqlite", color = Color.Gray, fontSize = 8.sp)
                    }
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                        Text("↑ ${liveSpeedUp / 1024} KB/s", color = if(liveSpeedUp > 0) Color.Cyan else Color.DarkGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("↓ ${liveSpeedDown / 1024} KB/s", color = if(liveSpeedDown > 0) Color.Green else Color.DarkGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (showDebug) {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(200.dp).background(Color(0xEE050505)).padding(4.dp)) {
                        items(debugLogs) { line ->
                            Text(line, color = if(line.contains("ERR")) Color.Red else Color.Cyan, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }
                }
                
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(logs) { log ->
                        Text(log, color = Color(0xFF00FF41), fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }

                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                        modifier = Modifier.weight(1f).background(Color(0xFF111111)).padding(12.dp)
                    )
                    
                    androidx.compose.material3.Button(
                        onClick = {
                            val textToSend = input
                            if (textToSend.isBlank()) return@Button
                            input = ""
                            scope.launch(Dispatchers.IO) {
                                try {
                                    dLog("TX: RAW_LEN=${textToSend.length}")
                                    val compressed = squeezer.compress(textToSend)
                                    dLog("TX: SQZ_LEN=${compressed.size} (RATIO: ${String.format("%.1f", compressed.size.toFloat()/textToSend.length*100)}%)")
                                    sendBurst(compressed, ::dLog)
                                    withContext(Dispatchers.Main) {
                                        logs = logs + "> $textToSend"
                                    }
                                } catch (e: Exception) {
                                    dLog("TX-ERR: ${e.message}")
                                    e.printStackTrace()
                                }
                            }
                        },
                        modifier = Modifier.padding(start = 4.dp)
                    ) { Text("SEND") }

                    androidx.compose.material3.Button(
                        onClick = {
                            dLog("RX: POLLING...")
                            scope.launch(Dispatchers.IO) {
                                fetchBurst(::dLog) { incoming -> 
                                    kotlinx.coroutines.MainScope().launch { logs = logs + "RX: $incoming" }
                                }
                            }
                        },
                        modifier = Modifier.padding(start = 4.dp)
                    ) { Text("FETCH") }
                }
            }
        }
    }

    private fun wipeBurst(onDone: () -> Unit) {
        val url = "https://xvldfsmxskhemkslsbym.supabase.co/functions/v1/ghost-wipe"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .post("".toRequestBody(null))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { }
            override fun onResponse(call: Call, response: Response) { 
                response.close()
                runOnUiThread { onDone() }
            }
        })
    }

    private fun fetchBurst(dLog: (String) -> Unit, onMsg: (String) -> Unit) {
        val url = "https://xvldfsmxskhemkslsbym.supabase.co/functions/v1/ghost-pull"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .post("".toRequestBody(null))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                runOnUiThread { dLog("RX-ERR: ${e.message}") }
            }
            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val bodyBytes = response.body?.bytes() ?: byteArrayOf()
                runOnUiThread { dLog("RX-RES: HTTP $code | BODY=${bodyBytes.size}b") }
                
                if (bodyBytes.isNotEmpty()) {
                    kotlinx.coroutines.MainScope().launch {
                        val decrypted = squeezer.decompress(bodyBytes)
                        runOnUiThread { 
                            dLog("RX-DEC: SUCCESS")
                            onMsg(decrypted) 
                        }
                    }
                }
                response.close()
            }
        })
    }

    private fun sendBurst(data: ByteArray, dLog: (String) -> Unit) {
        val url = "https://xvldfsmxskhemkslsbym.supabase.co/functions/v1/ghost-handler"
        val packet = ByteArray(data.size + 1)
        packet[0] = uid
        System.arraycopy(data, 0, packet, 1, data.size)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .addHeader("Content-Type", "application/octet-stream")
            .post(packet.toRequestBody("application/octet-stream".toMediaType()))
            .build()

        dLog("TX-REQ: POST ${packet.size}b")
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                runOnUiThread { dLog("TX-ERR: ${e.message}") }
            }
            override fun onResponse(call: Call, response: Response) { 
                val code = response.code
                runOnUiThread { dLog("TX-RES: HTTP $code") }
                response.body?.source()?.skip(Long.MAX_VALUE)
                response.close() 
            }
        })
    }

    // HIGH-EFFICIENCY UDP MODE (NTP DISGUISE)
    private fun sendGhostUdp(data: ByteArray, serverIp: String) {
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val socket = java.net.DatagramSocket()
                val address = java.net.InetAddress.getByName(serverIp)
                
                // NTP Header (48 bytes) + Ghost Payload
                val ntpPacket = ByteArray(48 + data.size + 1)
                ntpPacket[0] = 0x1B // LI = 0, VN = 3, Mode = 3 (Client)
                ntpPacket[48] = uid
                System.arraycopy(data, 0, ntpPacket, 49, data.size)
                
                val packet = java.net.DatagramPacket(ntpPacket, ntpPacket.size, address, 123)
                socket.send(packet)
                socket.close()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
}