package com.zamankasifleri.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import coil.compose.AsyncImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

private val Navy = Color(0xFF142744)
private val Gold = Color(0xFFFFC857)
private val Cream = Color(0xFFFFF8EC)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Navy, secondary = Gold, background = Cream)) { ZamanApp() } }
    }
}

data class StoryRequest(val world: String, val mission: String, val minutes: Int = 5, val age: Int = 5)
data class StoryPage(val title: String, val narration: String, val dialogue: String = "", val speaker: String = "Alp", @SerializedName("image_url") val imageUrl: String? = null)
data class StoryResponse(val title: String, val introduction: String, val pages: List<StoryPage>)
interface StoryApi { @POST("story") suspend fun createStory(@Body request: StoryRequest): StoryResponse }

@Composable
fun ZamanApp() {
    var world by remember { mutableStateOf("Korsanlar") }
    var mission by remember { mutableStateOf("Kayıp hazineyi bul") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var story by remember { mutableStateOf<StoryResponse?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var custom by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val api = remember {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder().baseUrl(BuildConfig.API_BASE_URL).client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(StoryApi::class.java)
    }
    val worlds = listOf("🏴‍☠️ Korsanlar", "🤠 Kovboylar", "🏰 Şövalyeler", "⚔️ Samuraylar", "🥷 Ninjalar", "⛵ Amerika'nın keşfi", "🦖 Dinozorlar", "🏺 Antik Mısır", "🚀 Gelecek", "🐉 Fantastik dünya")
    val missions = listOf("Kayıp hazineyi bul", "Bir arkadaşını kurtar", "Gizemli bir haritayı çöz", "Yeni bir yer keşfet")
    Scaffold(containerColor = Cream) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (story == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Explore, null, tint = Gold, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(8.dp)); Text("ZAMAN KAŞİFLERİ", fontSize = 25.sp, fontWeight = FontWeight.Black, color = Navy)
                }
                Text("Alp maceraları", color = Navy.copy(alpha = .7f), fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                Text("Bugün nereye gidelim?", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                Text("Bir dünya seç, macerayı başlatalım!", color = Color.DarkGray)
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxWidth().height(300.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), userScrollEnabled = false) {
                    items(worlds) { item ->
                        val label = item.substringAfter(" ")
                        Card(colors = CardDefaults.cardColors(containerColor = if (world == label) Gold.copy(alpha = .45f) else Color.White), modifier = Modifier.fillMaxWidth().clickable { world = label }, shape = RoundedCornerShape(16.dp)) {
                            Text(item, modifier = Modifier.padding(13.dp), fontWeight = FontWeight.Bold, color = Navy, textAlign = TextAlign.Center)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp)); Text("Macera görevi", fontWeight = FontWeight.Bold, color = Navy)
                missions.forEach { m -> FilterChip(selected = mission == m, onClick = { mission = m }, label = { Text(m) }) }
                OutlinedTextField(value = custom, onValueChange = { custom = it }, label = { Text("İstersen kendi fikrini yaz") }, placeholder = { Text("Örn. konuşan bir papağanla tanışsın") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Spacer(Modifier.height(14.dp))
                Button(onClick = {
                    loading = true; error = ""
                    scope.launch {
                        try { story = api.createStory(StoryRequest(world, if (custom.isBlank()) mission else "$mission. Ek fikir: $custom", 5, 5)); page = 0 }
                        catch (e: Exception) { error = "Hikâye oluşturulamadı. İnternet ve sunucu bağlantısını kontrol edin. (${e.localizedMessage ?: "bağlantı hatası"})" }
                        finally { loading = false }
                    }
                }, enabled = !loading, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Navy), shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Default.AutoStories, null); Spacer(Modifier.width(8.dp)); Text(if (loading) "Hikâye ve resimler hazırlanıyor…" else "MACERAYI BAŞLAT", fontWeight = FontWeight.Bold)
                }
                if (error.isNotBlank()) Text(error, color = Color(0xFF9A3B32), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Text("4-6 yaş için güvenli • Resimli hikâyeler", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 14.dp))
            } else {
                val current = story!!.pages.getOrNull(page)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { story = null }) { Icon(Icons.Default.ArrowBack, "Geri") }
                    Text(story!!.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Navy)
                }
                Text("Alp maceraları", color = Color.Gray)
                Spacer(Modifier.height(24.dp))
                Text(current?.title ?: "Macera", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                if (!current?.imageUrl.isNullOrBlank()) AsyncImage(model = current!!.imageUrl, contentDescription = current.title, modifier = Modifier.fillMaxWidth().height(230.dp))
                Text(current?.narration ?: story!!.introduction, fontSize = 19.sp, lineHeight = 29.sp, modifier = Modifier.padding(vertical = 20.dp))
                if (!current?.dialogue.isNullOrBlank()) Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Text("${current!!.speaker}: “${current.dialogue}”", modifier = Modifier.padding(16.dp), fontSize = 18.sp) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { if (page > 0) page-- }) { Text("Önceki") }
                    Text("${page + 1} / ${story!!.pages.size}")
                    TextButton(onClick = { if (page < story!!.pages.lastIndex) page++ else { story = null; page = 0 } }) { Text(if (page < story!!.pages.lastIndex) "Sonraki" else "Bitir") }
                }
            }
        }
    }
}
