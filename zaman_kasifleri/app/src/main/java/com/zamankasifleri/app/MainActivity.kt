package com.zamankasifleri.app

import android.content.ContentValues
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import coil.compose.AsyncImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

private val Navy = Color(0xFF142744)
private val Gold = Color(0xFFFFC857)
private val Cream = Color(0xFFFFF8EC)
private val gson = Gson()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Navy, secondary = Gold, background = Cream)) { ZamanApp() } }
    }
}

data class StoryRequest(val world: String, val mission: String, val minutes: Int = 5, val age: Int = 5)
data class StoryPage(
    val title: String,
    val narration: String,
    val dialogue: String = "",
    val speaker: String = "Alp",
    @SerializedName("image_url") val imageUrl: String? = null,
    @SerializedName("image_data") val imageData: String? = null
)
data class StoryResponse(val title: String, val introduction: String, val pages: List<StoryPage>)
data class SavedStory(val id: String, val savedAt: Long, val story: StoryResponse)

interface StoryApi { @POST("story") suspend fun createStory(@Body request: StoryRequest): StoryResponse }

private fun historyFile(context: android.content.Context) = File(context.filesDir, "story_history.json")

private fun loadSavedStories(context: android.content.Context): List<SavedStory> {
    return try {
        val file = historyFile(context)
        if (!file.exists()) emptyList()
        else gson.fromJson<List<SavedStory>>(file.readText(), object : TypeToken<List<SavedStory>>() {}.type) ?: emptyList()
    } catch (_: Exception) { emptyList() }
}

private fun saveStoryOnDevice(context: android.content.Context, response: StoryResponse): SavedStory {
    val id = UUID.randomUUID().toString()
    val storyDir = File(context.filesDir, "stories/$id").apply { mkdirs() }
    val localPages = response.pages.mapIndexed { index, page ->
        val encoded = page.imageData
        if (!encoded.isNullOrBlank()) {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            val imageFile = File(storyDir, "page_${index + 1}.png")
            imageFile.writeBytes(bytes)
            page.copy(imageUrl = imageFile.absolutePath, imageData = null)
        } else {
            page.copy(imageData = null)
        }
    }
    val localStory = response.copy(pages = localPages)
    val saved = SavedStory(id = id, savedAt = System.currentTimeMillis(), story = localStory)
    val all = loadSavedStories(context).toMutableList()
    all.add(0, saved)
    historyFile(context).writeText(gson.toJson(all))
    return saved
}

private fun saveImageToGallery(context: android.content.Context, imagePath: String): Boolean {
    val source = File(imagePath)
    if (!source.exists()) return false
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "ZamanKasifleri_${source.name}")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Zaman Kasifleri")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return try {
        context.contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
        true
    } catch (_: Exception) {
        context.contentResolver.delete(uri, null, null)
        false
    }
}

@Composable
fun ZamanApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var world by remember { mutableStateOf("Korsanlar") }
    var mission by remember { mutableStateOf("Kayıp hazineyi bul") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var story by remember { mutableStateOf<StoryResponse?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var custom by remember { mutableStateOf("") }
    var showArchive by remember { mutableStateOf(false) }
    var returnToArchive by remember { mutableStateOf(false) }
    var savedStories by remember { mutableStateOf(loadSavedStories(context)) }
    var galleryMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val api = remember {
        val client = OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
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
            if (story == null && !showArchive) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Explore, null, tint = Gold, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(8.dp)); Text("ZAMAN KAŞİFLERİ", fontSize = 25.sp, fontWeight = FontWeight.Black, color = Navy)
                }
                Text("Alp maceraları", color = Navy.copy(alpha = .7f), fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                Text("Bugün nereye gidelim?", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                Text("Bir dünya seç, macerayı başlatalım!", color = Color.DarkGray)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth()) {
                    worlds.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { item ->
                                val label = item.substringAfter(" ")
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = if (world == label) Gold.copy(alpha = .45f) else Color.White),
                                    modifier = Modifier.weight(1f).padding(vertical = 4.dp).clickable { world = label },
                                    shape = RoundedCornerShape(16.dp)
                                ) { Text(item, modifier = Modifier.fillMaxWidth().padding(13.dp), fontWeight = FontWeight.Bold, color = Navy, textAlign = TextAlign.Center) }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp)); Text("Macera görevi", fontWeight = FontWeight.Bold, color = Navy)
                missions.forEach { m -> FilterChip(selected = mission == m, onClick = { mission = m }, label = { Text(m) }) }
                OutlinedTextField(value = custom, onValueChange = { custom = it }, label = { Text("İstersen kendi fikrini yaz") }, placeholder = { Text("Örn. konuşan bir papağanla tanışsın") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Spacer(Modifier.height(14.dp))
                Button(onClick = {
                    loading = true; error = ""; galleryMessage = ""
                    scope.launch {
                        try {
                            val generated = api.createStory(StoryRequest(world, if (custom.isBlank()) mission else "$mission. Ek fikir: $custom", 5, 5))
                            val saved = withContext(Dispatchers.IO) { saveStoryOnDevice(context, generated) }
                            story = saved.story
                            savedStories = loadSavedStories(context)
                            page = 0
                            returnToArchive = false
                        } catch (e: Exception) {
                            error = "Hikâye kaydedilemedi. İnternet ve sunucu bağlantısını kontrol edin. (${e.localizedMessage ?: "bağlantı hatası"})"
                        } finally { loading = false }
                    }
                }, enabled = !loading, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Navy), shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Default.AutoStories, null); Spacer(Modifier.width(8.dp)); Text(if (loading) "Hikâye ve resimler telefona alınıyor…" else "MACERAYI BAŞLAT", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { savedStories = loadSavedStories(context); showArchive = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("📚 Eski Maceralarım (${savedStories.size})", fontWeight = FontWeight.Bold)
                }
                if (error.isNotBlank()) Text(error, color = Color(0xFF9A3B32), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Text("Hikâyeler ve resimler bu telefonda saklanır • İnternetsiz tekrar açılabilir", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 14.dp))
            } else if (story == null && showArchive) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showArchive = false }) { Icon(Icons.Default.ArrowBack, "Geri") }
                    Text("Eski Maceralarım", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy)
                }
                if (savedStories.isEmpty()) {
                    Text("Henüz kayıtlı macera yok. İlk hikâyeni oluşturduğunda burada saklanacak.", color = Color.Gray, modifier = Modifier.padding(24.dp))
                } else {
                    savedStories.sortedByDescending { it.savedAt }.forEach { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable {
                                story = item.story
                                page = 0
                                returnToArchive = true
                                showArchive = false
                                galleryMessage = ""
                            },
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(item.story.title, color = Navy, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text(SimpleDateFormat("dd.MM.yyyy • HH:mm", Locale("tr", "TR")).format(Date(item.savedAt)), color = Color.Gray, fontSize = 12.sp)
                                Text("${item.story.pages.size} sayfa • Telefonda kayıtlı", color = Navy.copy(alpha = .7f), fontSize = 12.sp)
                            }
                        }
                    }
                }
            } else {
                val current = story!!.pages.getOrNull(page)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        story = null
                        showArchive = returnToArchive
                        returnToArchive = false
                    }) { Icon(Icons.Default.ArrowBack, "Geri") }
                    Text(story!!.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Navy)
                }
                Text("Alp maceraları • Telefona kaydedildi", color = Color.Gray)
                Spacer(Modifier.height(18.dp))
                Text(current?.title ?: "Macera", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                val imagePath = current?.imageUrl
                if (!imagePath.isNullOrBlank()) {
                    AsyncImage(
                        model = if (imagePath.startsWith("http")) imagePath else File(imagePath),
                        contentDescription = current.title,
                        modifier = Modifier.fillMaxWidth().height(230.dp)
                    )
                    if (!imagePath.startsWith("http")) {
                        TextButton(onClick = {
                            galleryMessage = if (saveImageToGallery(context, imagePath)) "Resim galerine kaydedildi." else "Resim galeriye kaydedilemedi."
                        }) { Text("⬇ Resmi telefon galerisine kaydet") }
                        if (galleryMessage.isNotBlank()) Text(galleryMessage, color = Navy, fontSize = 12.sp)
                    }
                } else {
                    Text("Bu sayfanın görseli kaydedilemedi.", color = Color.Gray, modifier = Modifier.padding(12.dp))
                }
                Text(current?.narration ?: story!!.introduction, fontSize = 19.sp, lineHeight = 29.sp, modifier = Modifier.padding(vertical = 20.dp))
                if (!current?.dialogue.isNullOrBlank()) Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Text("${current!!.speaker}: “${current.dialogue}”", modifier = Modifier.padding(16.dp), fontSize = 18.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { if (page > 0) { page--; galleryMessage = "" } }, enabled = page > 0) { Text("Önceki") }
                    Text("${page + 1} / ${story!!.pages.size}")
                    TextButton(onClick = {
                        if (page < story!!.pages.lastIndex) { page++; galleryMessage = "" }
                        else {
                            story = null
                            showArchive = returnToArchive
                            returnToArchive = false
                        }
                    }) { Text(if (page < story!!.pages.lastIndex) "Sonraki" else "Bitir") }
                }
            }
        }
    }
}
