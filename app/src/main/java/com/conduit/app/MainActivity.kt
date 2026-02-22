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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.Room
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class MainActivity : ComponentActivity() {
    private val client = OkHttpClient.Builder()
        .addNetworkInterceptor { chain ->
            // STRIP HEADERS TO THE ABSOLUTE LIMIT
            val original = chain.request()
            val stripped = original.newBuilder()
                .removeHeader("User-Agent")
                .removeHeader("Accept-Language")
                .removeHeader("Connection")
                .build()
            chain.proceed(stripped)
        }
        .build()

    private val uid = (0..254).random().toByte()
    private lateinit var db: GhostDatabase
    private lateinit var squeezer: GhostSqueezer
    private var webSocket: WebSocket? = null
    private val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inh2bGRmc214c2toZW1rc2xzYnltIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjI2ODgxNzksImV4cCI6MjA3ODI2NDE3OX0.5arqrx8Tt7v-hpXpo_ncoK4IX8th9IibxAuv93SSoOU"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Room.databaseBuilder(applicationContext, GhostDatabase::class.java, "ghost-db")
            .createFromAsset("ghost_dict.db")
            .fallbackToDestructiveMigration()
            .build()
        squeezer = GhostSqueezer(db.dictionaryDao())

        setContent {
            var logs by remember { mutableStateOf(listOf("GHOST-PROTOCOL INITIALIZED")) }
            var input by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()

            Column(modifier = Modifier.fillMaxSize().background(Color(0xFF020202)).padding(16.dp)) {
                Text("CONDUIT // ABSOLUTE LIMIT", color = Color(0xFF00FF41), fontSize = 12.sp)
                
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
                        modifier = Modifier.weight(1f).background(Color(0xFF111111)).padding(12.dp),
                        decorationBox = { innerTextField ->
                            if (input.isEmpty()) Text("CMD...", color = Color.Gray)
                            innerTextField()
                        }
                    )
                    
                    androidx.compose.material3.Button(
                        onClick = {
                            val textToSend = input
                            input = ""
                            scope.launch {
                                val compressed = squeezer.compress(textToSend)
                                sendBurst(compressed)
                                logs = logs + "> $textToSend [${compressed.size}b]"
                            }
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    ) { Text("SEND") }
                }
            }
            
            LaunchedEffect(Unit) {
                initGhostEar { incoming ->
                    logs = logs + incoming
                }
            }
        }
    }

    private fun initGhostEar(onMsg: (String) -> Unit) {
        val wsUrl = "wss://xvldfsmxskhemkslsbym.supabase.co/realtime/v1/websocket?apikey=$SUPABASE_KEY&vsn=1.0.0"
        val request = Request.Builder().url(wsUrl).build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Join the ghost_stream channel
                val joinMsg = "{\"topic\":\"realtime:public:ghost_stream\",\"event\":\"phx_join\",\"payload\":{},\"ref\":\"1\"}"
                webSocket.send(joinMsg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Supabase Realtime sends JSON. We extract the payload.
                if (text.contains("\"event\":\"INSERT\"")) {
                    try {
                        val rawPayload = text.substringAfter("\"payload\":\\\"\\\\x").substringBefore("\\\"")
                        val bytes = rawPayload.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        
                        kotlinx.coroutines.MainScope().launch {
                            val decrypted = squeezer.decompress(bytes)
                            onMsg("RX: $decrypted")
                        }
                    } catch (e: Exception) {
                        // Handle parse error
                    }
                }
                
                // Handle Heartbeat to stay alive
                if (text.contains("phx_reply")) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        webSocket.send("{\"topic\":\"phoenix\",\"event\":\"heartbeat\",\"payload\":{},\"ref\":\"h\"}")
                    }, 30000)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ initGhostEar(onMsg) }, 5000)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ initGhostEar(onMsg) }, 5000)
            }
        })
    }

    private fun sendBurst(data: ByteArray) {
        val url = "https://xvldfsmxskhemkslsbym.supabase.co/functions/v1/ghost-handler"
        val key = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inh2bGRmc214c2toZW1rc2xzYnltIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjI2ODgxNzksImV4cCI6MjA3ODI2NDE3OX0.5arqrx8Tt7v-hpXpo_ncoK4IX8th9IibxAuv93SSoOU"
        
        // PROTOCOL: [UID] + [DATA]
        val packet = ByteArray(data.size + 1)
        packet[0] = uid
        System.arraycopy(data, 0, packet, 1, data.size)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            // We use null media type to prevent OkHttp from adding Content-Type/Length headers if possible
            .post(packet.toRequestBody(null))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }
}