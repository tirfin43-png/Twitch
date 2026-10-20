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
    private var activeDropTitle = "Поиск наград..."
    private var activeDropProgress = "0%"

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

    private fun getAnalyzeInventoryScript(game: String): String {
        return """
            (function(gameQuery) {
                var claimButtons = document.querySelectorAll('button');
                var claimedCount = 0;
                claimButtons.forEach(function(b) {
                    var t = (b.innerText || '').toLowerCase();
                    if (t.includes('claim') || t.includes('получить')) {
                        b.click();
                        claimedCount++;
                    }
                });

                var gameClean = gameQuery.toLowerCase().replace(/-/g, ' ');
                var allLinks = document.querySelectorAll('a');
                var targetChannelUrl = null;
                var dropName = "";
                var progressText = "";
                var allCompleted = true;
                var foundAnyForGame = false;

                for (var i = 0; i < allLinks.length; i++) {
                    var a = allLinks[i];
                    var href = a.getAttribute('href') || '';
                    var text = (a.innerText || '').toLowerCase();

                    if (href.includes('/directory/category/') || href.includes('tl=DropsEnabled') || text.includes('участвующ') || text.includes('participating')) {
                        var card = a.closest('div[style*="border"], div[class*="card"], div[class*="inventory"]') || a.parentElement.parentElement;
                        var cardText = card ? (card.innerText || '').toLowerCase() : '';

                        if (cardText.includes(gameClean) || href.toLowerCase().includes(gameQuery.toLowerCase())) {
                            foundAnyForGame = true;

                            if (cardText.includes('получено') || cardText.includes('claimed') || cardText.includes('100%')) {
                                continue;
                            }

                            allCompleted = false;
                            targetChannelUrl = a.href;
                            
                            var titleElem = card ? card.querySelector('h3, h4, p, strong') : null;
                            dropName = titleElem ? titleElem.innerText : 'Награда Drops';

                            var match = cardText.match(/(\d+)%/);
                            if (match) {
                                progressText = match[1] + '%';
                            }
                            break;
                        }
                    }
                }

                return JSON.stringify({
                    claimed: claimedCount,
                    hasPending: targetChannelUrl !== null,
                    targetUrl: targetChannelUrl,
                    dropName: dropName,
                    progress: progressText,
                    allCompleted: foundAnyForGame && allCompleted
                });
            })('$game');
        """.trimIndent()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Запрашиваем разрешение на показ уведомлений для Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#18181B"))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 16, 24, 16)
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
                marginEnd = 16
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

        val inventoryBtn = Button(this).apply {
            text = "Drops"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3D"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8 }
            setOnClickListener {
                isFarming = false
                statusText.text = "Инвентарь наград"
                webView.loadUrl("https://www.twitch.tv/drops/inventory")
            }
        }

        topBar.addView(gameInput)
        topBar.addView(startBtn)
        topBar.addView(inventoryBtn)
        mainLayout.addView(topBar)

        statusText = TextView(this).apply {
            text = "⚪ Нажмите «АВТО-СТАРТ» для автоматического фарма"
            setTextColor(Color.parseColor("#00FF7F"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(24, 14, 24, 14)
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
        statusText.text = "🔍 Сканируем инвентарь: ищем активные Drops..."
        syncNotification()
        webView.loadUrl("https://www.twitch.tv/drops/inventory")

        handler.postDelayed({
            if (!isFarming) return@postDelayed
            evaluateInventoryAndSelectStreamer()
        }, 6000)
    }

    private fun evaluateInventoryAndSelectStreamer() {
        webView.evaluateJavascript(getAnalyzeInventoryScript(targetGame)) { result ->
            try {
                val cleanJson = result.removeSurrounding("\"").replace("\\\"", "\"")
                val json = JSONObject(cleanJson)

                val claimedCount = json.optInt("claimed", 0)
                if (claimedCount > 0) {
                    Toast.makeText(this, "🎉 Забран готовый дропс ($claimedCount шт)!", Toast.LENGTH_LONG).show()
                }

                val allCompleted = json.optBoolean("allCompleted", false)
                if (allCompleted) {
                    statusText.text = "🏆 Все доступные Drops по $targetGame получены!"
                    activeDropTitle = "Все награды получены!"
                    activeDropProgress = "100%"
                    syncNotification()
                    isFarming = false
                    return@evaluateJavascript
                }

                val hasPending = json.optBoolean("hasPending", false)
                if (hasPending) {
                    val targetUrl = json.getString("targetUrl")
                    activeDropTitle = json.optString("dropName", "Награда")
                    activeDropProgress = json.optString("progress", "0%")

                    statusText.text = "🎯 Награда: $activeDropTitle ($activeDropProgress). Открываем стримеров..."
                    syncNotification()
                    webView.loadUrl(targetUrl)

                    handler.postDelayed({
                        if (isFarming) pickStreamerFromParticipatingList()
                    }, 7000)
                } else {
                    statusText.text = "🔍 Награда не начата. Открываем каталог $targetGame..."
                    val fallbackUrl = "https://www.twitch.tv/directory/category/$targetGame?tl=DropsEnabled"
                    webView.loadUrl(fallbackUrl)

                    handler.postDelayed({
                        if (isFarming) pickStreamerFromParticipatingList()
                    }, 7000)
                }
            } catch (e: Exception) {
                val fallbackUrl = "https://www.twitch.tv/directory/category/$targetGame?tl=DropsEnabled"
                webView.loadUrl(fallbackUrl)
                handler.postDelayed({
                    if (isFarming) pickStreamerFromParticipatingList()
                }, 7000)
            }
        }
    }

    private fun pickStreamerFromParticipatingList() {
        val clickScript = """
            (function() {
                var streamLink = document.querySelector('a[data-a-target="preview-card-image-link"]') 
                              || document.querySelector('article a')
                              || document.querySelector('div[role="list"] a');

                if (!streamLink) {
                    var links = document.querySelectorAll('a');
                    for (var i = 0; i < links.length; i++) {
                        var href = links[i].getAttribute('href') || '';
                        if (href.startsWith('/') && href.length > 2 && 
                            !href.includes('/directory') && 
                            !href.includes('/p/') && 
                            !href.includes('/downloads') &&
                            !href.includes('/login')) {
                            if (links[i].querySelector('img') || links[i].innerText.length > 0) {
                                streamLink = links[i];
                                break;
                            }
                        }
                    }
                }

                if (streamLink) {
                    var channel = streamLink.getAttribute('href').replace('/', '').split('?')[0];
                    streamLink.click();
                    return channel;
                }
                return "not_found";
            })();
        """.trimIndent()

        webView.evaluateJavascript(clickScript) { result ->
            val channel = result?.replace("\"", "") ?: "not_found"
            if (channel != "not_found" && channel.isNotEmpty()) {
                currentStreamer = channel
                minutesWatched = 0
                statusText.text = "🟢 Стрим: $currentStreamer | $activeDropTitle ($activeDropProgress) | ▶️ 0 мин"
                syncNotification()
            } else {
                statusText.text = "⚠️ Стримеры с активными Drops сейчас не найдены."
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
                        statusText.text = "🟢 Стрим: $currentStreamer | $activeDropTitle ($activeDropProgress) | ▶️ $minutesWatched мин"
                        webView.evaluateJavascript(keepAliveScript, null)

                        // Синхронизируем статус в шторке уведомлений
                        syncNotification()

                        // Каждые 12 минут обновляем точный процент из инвентаря
                        if (minutesWatched % 12 == 0) {
                            statusText.text = "🔄 Проверка прогресса Drops..."
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
