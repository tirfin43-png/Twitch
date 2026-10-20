package com.drops.miner

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var targetGame = "world-of-tanks"
    private var currentStreamer = ""
    private var minutesWatched = 0
    private var isFarming = false
    private var activeDropTitle = "Поиск активного Drops..."
    private var activeDropProgress = "0%"

    // Черный список стримеров
    private val blacklist = mutableSetOf<String>()
    // Текст всех завершенных наград из инвентаря
    private var completedRewardsText = ""

    private val keepAliveScript = """
        (function() {
            var v = document.querySelector('video');
            if (v) {
                v.muted = true;
                v.volume = 0;
                if (v.paused) v.play();
            }
        })();
    """.trimIndent()

    // Сканер инвентаря: забирает награды и вытягивает текст ВСЕХ завершенных дропсов
    private val scanCompletedDropsScript = """
        (function() {
            // 1. Забираем готовые дропсы
            var claimButtons = document.querySelectorAll('button');
            var claimedCount = 0;
            claimButtons.forEach(function(b) {
                var t = (b.innerText || '').toLowerCase();
                if (t.includes('claim') || t.includes('получить')) {
                    b.click();
                    claimedCount++;
                }
            });

            // 2. Собираем весь текст из карточек со статусом "Получено" / "100%"
            var completedText = "";
            var allElements = document.querySelectorAll('div, section, article');
            allElements.forEach(function(el) {
                var txt = (el.innerText || '').toLowerCase();
                if ((txt.includes('получено') || txt.includes('claimed') || txt.includes('100%')) && txt.length < 600) {
                    completedText += " " + txt;
                }
            });

            return JSON.stringify({
                claimed: claimedCount,
                completedBlob: completedText
            });
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#18181B"))
        }

        // РЯД 1: Поле игры и кнопка Авто-Старт
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 16, 24, 8)
            setBackgroundColor(Color.parseColor("#1F1F23"))
        }

        val gameInput = EditText(this).apply {
            hint = "игра (world-of-tanks, rust)"
            setHintTextColor(Color.parseColor("#ADADB8"))
            setTextColor(Color.WHITE)
            setText(targetGame)
            setBackgroundColor(Color.parseColor("#26262C"))
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 12
            }
        }

        val startBtn = Button(this).apply {
            text = "АВТО-СТАРТ"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#9146FF"))
            setOnClickListener {
                targetGame = gameInput.text.toString().trim().lowercase().replace(" ", "-")
                startSmartFarmingCycle()
            }
        }

        topBar.addView(gameInput)
        topBar.addView(startBtn)
        mainLayout.addView(topBar)

        // РЯД 2: Ручной пропуск и Drops
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 4, 24, 12)
            setBackgroundColor(Color.parseColor("#1F1F23"))
        }

        val skipBtn = Button(this).apply {
            text = "⏭️ Пропустить стримера"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3D"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8
            }
            setOnClickListener {
                if (currentStreamer.isNotEmpty()) {
                    blacklist.add(currentStreamer.lowercase())
                    Toast.makeText(applicationContext, "$currentStreamer заблокирован вручную", Toast.LENGTH_SHORT).show()
                }
                openDirectoryAndPickValidStreamer()
            }
        }

        val inventoryBtn = Button(this).apply {
            text = "🎁 Drops"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#26262C"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                isFarming = false
                statusText.text = "Инвентарь наград"
                webView.loadUrl("https://www.twitch.tv/drops/inventory")
            }
        }

        row2.addView(skipBtn)
        row2.addView(inventoryBtn)
        mainLayout.addView(row2)

        // Статус-бар
        statusText = TextView(this).apply {
            text = "⚪ Нажмите «АВТО-СТАРТ»"
            setTextColor(Color.parseColor("#00FF7F"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(24, 12, 24, 12)
            setBackgroundColor(Color.parseColor("#0E0E10"))
        }
        mainLayout.addView(statusText)

        webView = WebView(this)
        mainLayout.addView(webView, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        setContentView(mainLayout)

        setupWebView()

        val serviceIntent = Intent(this, DropFarmerService::class.java)
        startForegroundService(serviceIntent)

        startAutoPilotLoop()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.javaScriptCanOpenWindowsAutomatically = true

        val originalUserAgent = settings.userAgentString
        settings.userAgentString = originalUserAgent
            .replace("; wv", "")
            .replace("Version/4.0 ", "")

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val currentUrl = url ?: ""
                if (!currentUrl.contains("/directory/") && !currentUrl.contains("/drops/")) {
                    currentStreamer = currentUrl.substringAfterLast("/").substringBefore("?").lowercase()
                }
                webView.evaluateJavascript(keepAliveScript, null)
            }
        }

        webView.loadUrl("https://www.twitch.tv")
    }

    override fun onPause() {
        super.onPause()
        webView.resumeTimers()
    }

    override fun onStop() {
        super.onStop()
        webView.resumeTimers()
    }

    // 1. Сканируем инвентарь и собираем имена всех завершенных стримеров
    private fun startSmartFarmingCycle() {
        isFarming = true
        minutesWatched = 0
        statusText.text = "🔍 Сканируем инвентарь: ищем завершенные Drops..."
        syncNotification()
        webView.loadUrl("https://www.twitch.tv/drops/inventory")

        handler.postDelayed({
            if (!isFarming) return@postDelayed
            
            webView.evaluateJavascript(scanCompletedDropsScript) { result ->
                try {
                    val cleanJson = result.removeSurrounding("\"").replace("\\\"", "\"")
                    val json = JSONObject(cleanJson)

                    val claimedCount = json.optInt("claimed", 0)
                    if (claimedCount > 0) {
                        Toast.makeText(this, "🎉 Забран готовый дропс ($claimedCount шт)!", Toast.LENGTH_LONG).show()
                    }

                    // Сохраняем весь текст завершенных наград
                    completedRewardsText = json.optString("completedBlob", "").lowercase()
                } catch (e: Exception) {
                    completedRewardsText = ""
                }

                // Идем в каталог выбирать стримера
                openDirectoryAndPickValidStreamer()
            }
        }, 5000)
    }

    private fun openDirectoryAndPickValidStreamer() {
        statusText.text = "🔍 Ищем стримера по $targetGame с доступным дропсом..."
        val directoryUrl = "https://www.twitch.tv/directory/category/$targetGame?tl=DropsEnabled"
        webView.loadUrl(directoryUrl)

        handler.postDelayed({
            if (isFarming) pickStreamerExcludingCompleted()
        }, 7000)
    }

    // 2. Проверяем каждого стримера: если он есть в тексте полученных наград — ПРОПУСКАЕМ!
    private fun pickStreamerExcludingCompleted() {
        val blacklistJson = "[" + blacklist.joinToString(",") { "\"$it\"" } + "]"
        val completedEscaped = completedRewardsText.replace("\"", "\\\"").replace("\n", " ")

        val clickScript = """
            (function(blacklisted, completedBlob) {
                var links = document.querySelectorAll('a[data-a-target="preview-card-image-link"], article a, div[role="list"] a');

                if (!links || links.length === 0) {
                    links = document.querySelectorAll('a');
                }

                for (var i = 0; i < links.length; i++) {
                    var href = links[i].getAttribute('href') || '';
                    if (href.startsWith('/') && href.length > 2 && 
                        !href.includes('/directory') && 
                        !href.includes('/p/') && 
                        !href.includes('/downloads') &&
                        !href.includes('/login')) {
                        
                        var channel = href.replace('/', '').toLowerCase().split('?')[0];

                        // 1. Проверяем ручной черный список
                        if (blacklisted.includes(channel)) {
                            continue;
                        }

                        // 2. ГЛАВНАЯ ПРОВЕРКА: упоминается ли стример в тексте забранных наград?
                        if (completedBlob.length > 0 && completedBlob.includes(channel)) {
                            // Этот стример УЖЕ ПОЛУЧЕН! Пропускаем его!
                            return "auto_skip:" + channel;
                        }

                        // Нашли живого стримера, где награда еще НЕ получена!
                        links[i].click();
                        return channel;
                    }
                }
                return "not_found";
            })($blacklistJson, "$completedEscaped");
        """.trimIndent()

        webView.evaluateJavascript(clickScript) { result ->
            val res = result?.replace("\"", "") ?: "not_found"

            if (res.startsWith("auto_skip:")) {
                val skippedChannel = res.substringAfter("auto_skip:")
                blacklist.add(skippedChannel.lowercase())
                statusText.text = "⏭️ $skippedChannel уже получен в инвентаре! Берем следующего..."
                Toast.makeText(this, "$skippedChannel уже завершен. Пропускаем...", Toast.LENGTH_SHORT).show()
                // Сразу ищем дальше без этого стримера
                pickStreamerExcludingCompleted()
                return@evaluateJavascript
            }

            if (res != "not_found" && res.isNotEmpty()) {
                currentStreamer = res
                minutesWatched = 0
                activeDropTitle = "Фарминг $targetGame"
                statusText.text = "🟢 Стрим: $currentStreamer | ▶️ Дропс активен (0 мин) | В блоке: ${blacklist.size}"
                syncNotification()
            } else {
                statusText.text = "⚠️ Все активные стримеры уже завершены или оффлайн."
            }
        }
    }

    private fun syncNotification() {
        DropFarmerService.updateProgress(
            this,
            activeDropTitle,
            activeDropProgress,
            currentStreamer,
            minutesWatched
        )
    }

    private fun startAutoPilotLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isFarming) {
                    val currentUrl = webView.url ?: ""

                    if (!currentUrl.contains("/directory/") && !currentUrl.contains("/drops/")) {
                        minutesWatched += 1
                        statusText.text = "🟢 Стрим: $currentStreamer | ▶️ Время: $minutesWatched мин | В блоке: ${blacklist.size}"
                        webView.evaluateJavascript(keepAliveScript, null)
                        syncNotification()

                        // Каждые 12 минут проверяем инвентарь и забираем готовые награды
                        if (minutesWatched % 12 == 0) {
                            startSmartFarmingCycle()
                            return
                        }
                    }
                }

                handler.postDelayed(this, 60000)
            }
        }, 60000)
    }
}
