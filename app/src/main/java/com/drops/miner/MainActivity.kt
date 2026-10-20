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

    // Черный список стримеров (где дропс уже получен или недоступен)
    private val blacklist = mutableSetOf<String>()

    // Скрипт поддержания видео в активном состоянии без звука
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

    // Скрипт проверки: отдает ли ЭТОТ стример дропс прямо сейчас
    private val checkStreamDropStatusScript = """
        (function() {
            var pageText = (document.body.innerText || "").toLowerCase();
            
            // Если Twitch пишет, что награды уже получены или недоступны
            var isAlreadyCompleted = pageText.includes("вы уже получили все") 
                                  || pageText.includes("you have already earned")
                                  || pageText.includes("you've earned all")
                                  || pageText.includes("все доступные награды получены");

            var isOffline = document.querySelector('.channel-status-info--offline') !== null;

            return JSON.stringify({
                isAlreadyCompleted: isAlreadyCompleted,
                isOffline: isOffline
            });
        })();
    """.trimIndent()

    // Сканирование инвентаря: сбор готовых наград и поиск завершенных стримеров
    private fun getInventoryScannerScript(): String {
        return """
            (function() {
                // 1. Забираем все готовые дропсы
                var claimButtons = document.querySelectorAll('button');
                var claimedCount = 0;
                claimButtons.forEach(function(b) {
                    var t = (b.innerText || '').toLowerCase();
                    if (t.includes('claim') || t.includes('получить')) {
                        b.click();
                        claimedCount++;
                    }
                });

                // 2. Ищем стримеров в завершенных карточках, чтобы сразу отправить в бан
                var completedStreamers = [];
                var cards = document.querySelectorAll('div[data-a-target="drops-inventory-card"], div');
                cards.forEach(function(c) {
                    var text = (c.innerText || '').toLowerCase();
                    if (text.includes('получено') || text.includes('claimed') || text.includes('100%')) {
                        var links = c.querySelectorAll('a');
                        links.forEach(function(a) {
                            var href = a.getAttribute('href') || '';
                            if (href.startsWith('/') && !href.includes('/directory') && !href.includes('/drops')) {
                                var name = href.replace('/', '').split('?')[0].toLowerCase();
                                if (name.length > 2) completedStreamers.push(name);
                            }
                        });
                    }
                });

                return JSON.stringify({
                    claimed: claimedCount,
                    completedStreamers: completedStreamers
                });
            })();
        """.trimIndent()
    }

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

        // РЯД 1: Ввод игры и кнопки
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

        // РЯД 2: Кнопка ручного пропуска и Drops
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
                    Toast.makeText(applicationContext, "Пропускаем $currentStreamer...", Toast.LENGTH_SHORT).show()
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

    private fun startSmartFarmingCycle() {
        isFarming = true
        minutesWatched = 0
        statusText.text = "🔍 Проверяем инвентарь и исключаем полученные Drops..."
        syncNotification()
        webView.loadUrl("https://www.twitch.tv/drops/inventory")

        handler.postDelayed({
            if (!isFarming) return@postDelayed
            
            webView.evaluateJavascript(getInventoryScannerScript()) { result ->
                try {
                    val cleanJson = result.removeSurrounding("\"").replace("\\\"", "\"")
                    val json = JSONObject(cleanJson)

                    val claimedCount = json.optInt("claimed", 0)
                    if (claimedCount > 0) {
                        Toast.makeText(this, "🎉 Забран готовый дропс ($claimedCount шт)!", Toast.LENGTH_LONG).show()
                    }

                    // Добавляем завершенных стримеров в черный список
                    val completedArr = json.optJSONArray("completedStreamers")
                    if (completedArr != null) {
                        for (i in 0 until completedArr.length()) {
                            blacklist.add(completedArr.getString(i).lowercase())
                        }
                    }
                } catch (e: Exception) {
                    // Игнорируем ошибки парсинга
                }

                // Переходим к выбору стримера
                openDirectoryAndPickValidStreamer()
            }
        }, 5000)
    }

    private fun openDirectoryAndPickValidStreamer() {
        statusText.text = "🔍 Ищем активного стримера по $targetGame (пропуская полученные)..."
        val directoryUrl = "https://www.twitch.tv/directory/category/$targetGame?tl=DropsEnabled"
        webView.loadUrl(directoryUrl)

        handler.postDelayed({
            if (isFarming) pickStreamerExcludingBlacklist()
        }, 7000)
    }

    private fun pickStreamerExcludingBlacklist() {
        val blacklistJson = "[" + blacklist.joinToString(",") { "\"$it\"" } + "]"

        val clickScript = """
            (function(blacklisted) {
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

                        // Пропускаем тех, кто в черном списке!
                        if (!blacklisted.includes(channel)) {
                            links[i].click();
                            return channel;
                        }
                    }
                }
                return "not_found";
            })($blacklistJson);
        """.trimIndent()

        webView.evaluateJavascript(clickScript) { result ->
            val channel = result?.replace("\"", "") ?: "not_found"
            if (channel != "not_found" && channel.isNotEmpty()) {
                currentStreamer = channel
                minutesWatched = 0
                statusText.text = "🟢 Стрим: $currentStreamer | Проверяем статус Drops..."
                activeDropTitle = "Фарминг $targetGame"
                syncNotification()

                // Через 6 секунд проверяем, не пишет ли Twitch на стриме "дропс уже получен"
                handler.postDelayed({
                    verifyStreamerDropsOnLive()
                }, 6000)
            } else {
                statusText.text = "⚠️ Все найденные стримеры уже в черном списке или оффлайн."
            }
        }
    }

    // Проверка стримера на странице трансляции
    private fun verifyStreamerDropsOnLive() {
        if (!isFarming) return

        webView.evaluateJavascript(checkStreamDropStatusScript) { result ->
            try {
                val cleanJson = result.removeSurrounding("\"").replace("\\\"", "\"")
                val json = JSONObject(cleanJson)

                val alreadyCompleted = json.optBoolean("isAlreadyCompleted", false)
                val isOffline = json.optBoolean("isOffline", false)

                if (alreadyCompleted) {
                    // Twitch говорит, что с этого канала дропс уже получен!
                    blacklist.add(currentStreamer.lowercase())
                    statusText.text = "⚠️ У $currentStreamer дропс уже получен! Ищем следующего..."
                    Toast.makeText(this, "$currentStreamer уже завершен. Переключаемся...", Toast.LENGTH_SHORT).show()
                    openDirectoryAndPickValidStreamer()
                    return@evaluateJavascript
                }

                if (isOffline) {
                    statusText.text = "⚠️ Стример ушел в оффлайн! Ищем другого..."
                    openDirectoryAndPickValidStreamer()
                    return@evaluateJavascript
                }

                // Всё отлично, дропс идет!
                statusText.text = "🟢 Стрим: $currentStreamer | ▶️ Дропс активен (0 мин)"
                syncNotification()
            } catch (e: Exception) {
                // Если не удалось проверить, продолжаем просмотр
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

    // Фоновый цикл
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

                        // Периодическая проверка: если стример закончил или дропс получен
                        if (minutesWatched % 4 == 0) {
                            verifyStreamerDropsOnLive()
                        }

                        // Каждые 12 минут забираем готовые награды в инвентаре
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
