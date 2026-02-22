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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Room.databaseBuilder(applicationContext, GhostDatabase::class.java, "ghost-db").build()
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
        }
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