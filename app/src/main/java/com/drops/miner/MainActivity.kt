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
    
    // Переменные проверки реального прогресса
    private var lastRecordedProgress = -1
    private var streamWatchMinutes = 0
    private val blacklist = mutableSetOf<String>()

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

    // Скрипт быстрого считывания текущего процента дропса из инвентаря
    private val getExactProgressScript = """
        (function() {
            var claimBtn = document.querySelector('button');
            var buttons = document.querySelectorAll('button');
            buttons.forEach(function(b) {
                var t = (b.innerText || '').toLowerCase();
                if (t.includes('claim') || t.includes('получить')) b.click();
            });

            var text = document.body.innerText || "";
            var matches = text.match(/(\d+)%/g);
            var maxProgress = -1;

            if (matches) {
                matches.forEach(function(m) {
                    var val = parseInt(m.replace('%', ''));
                    // Ищем активный прогресс от 0 до 99%
                    if (val >= 0 && val < 100 && val > maxProgress) {
                        maxProgress = val;
                    }
                });
            }

            return maxProgress;
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

        // РЯД 1: Поле игры и кнопка Старт
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
                blacklist.clear()
                lastRecordedProgress = -1
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
                skipCurrentStreamer("вручную")
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

        startProgressWatchdogLoop()
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
        streamWatchMinutes = 0
        statusText.text = "🔍 Проверяем текущий процент в инвентаре..."
        syncNotification()
        webView.loadUrl("https://www.twitch.tv/drops/inventory")

        // 4 секунды на считывание процента
        handler.postDelayed({
            if (!isFarming) return@postDelayed
            
            webView.evaluateJavascript(getExactProgressScript) { res ->
                val progressVal = res?.replace("\"", "")?.toIntOrNull() ?: -1
                if (progressVal != -1) {
                    lastRecordedProgress = progressVal
                }
                openDirectoryAndPickStreamer()
            }
        }, 4000)
    }

    private fun openDirectoryAndPickStreamer() {
        statusText.text = "🔍 Ищем доступного стримера по $targetGame..."
        val directoryUrl = "https://www.twitch.tv/directory/category/$targetGame?tl=DropsEnabled"
        webView.loadUrl(directoryUrl)

        handler.postDelayed({
            if (isFarming) pickNextStreamer()
        }, 6000)
    }

    private fun pickNextStreamer() {
        val blacklistJson = "[" + blacklist.joinToString(",") { "\"$it\"" } + "]"

        val clickScript = """
            (function(blacklisted) {
                var links = document.querySelectorAll('a[data-a-target="preview-card-image-link"], article a, div[role="list"] a');
                if (!links || links.length === 0) links = document.querySelectorAll('a');

                for (var i = 0; i < links.length; i++) {
                    var href = links[i].getAttribute('href') || '';
                    if (href.startsWith('/') && href.length > 2 && 
                        !href.includes('/directory') && 
                        !href.includes('/p/') && 
                        !href.includes('/downloads') &&
                        !href.includes('/login')) {
                        
                        var channel = href.replace('/', '').toLowerCase().split('?')[0];

                        // Пропускаем заблокированных
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
                streamWatchMinutes = 0
                val progStr = if (lastRecordedProgress >= 0) "$lastRecordedProgress%" else "..."
                statusText.text = "🟢 Стрим: $currentStreamer | Дропс: $progStr | Проверка прогресса..."
                syncNotification()
                Toast.makeText(this, "Включили $currentStreamer. Проверяем начисление...", Toast.LENGTH_SHORT).show()
            } else {
                statusText.text = "⚠️ Все стримеры с Drops в черном списке или оффлайн."
            }
        }
    }

    private fun skipCurrentStreamer(reason: String) {
        if (currentStreamer.isNotEmpty()) {
            blacklist.add(currentStreamer.lowercase())
            Toast.makeText(applicationContext, "$currentStreamer пропущен ($reason)", Toast.LENGTH_SHORT).show()
        }
        statusText.text = "⏭️ Пропуск $currentStreamer ($reason), ищем следующего..."
        streamWatchMinutes = 0
        openDirectoryAndPickStreamer()
    }

    private fun syncNotification() {
        val progStr = if (lastRecordedProgress >= 0) "$lastRecordedProgress%" else "Фарминг"
        DropFarmerService.updateProgress(
            this,
            "Drops $targetGame",
            progStr,
            currentStreamer,
            minutesWatched
        )
    }

    // Сторожевой таймер: каждую минуту следит за стримом, каждые 2.5 минуты проверяет, идет ли прогресс
    private fun startProgressWatchdogLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isFarming) {
                    val currentUrl = webView.url ?: ""

                    if (!currentUrl.contains("/directory/") && !currentUrl.contains("/drops/")) {
                        minutesWatched += 1
                        streamWatchMinutes += 1
                        
                        val progStr = if (lastRecordedProgress >= 0) "$lastRecordedProgress%" else "..."
                        statusText.text = "🟢 Стрим: $currentStreamer | Дропс: $progStr | Смотрим: ${streamWatchMinutes}м | Блок: ${blacklist.size}"
                        webView.evaluateJavascript(keepAliveScript, null)
                        syncNotification()

                        // КОНТРОЛЬ: если смотрим стримера уже 2.5 минуты — быстро проверяем, сдвинулся ли прогресс
                        if (streamWatchMinutes == 2) {
                            statusText.text = "🔎 Проверяем: идут ли проценты на стриме $currentStreamer..."
                            checkIfProgressActuallyIncreased()
                            return
                        }

                        // Каждые 12 минут плановый сбор наград
                        if (streamWatchMinutes >= 12) {
                            startSmartFarmingCycle()
                            return
                        }
                    }
                }

                handler.postDelayed(this, 60000)
            }
        }, 60000)
    }

    // Быстрая фоновая проверка инвентаря
    private fun checkIfProgressActuallyIncreased() {
        val savedStreamer = currentStreamer
        webView.loadUrl("https://www.twitch.tv/drops/inventory")

        handler.postDelayed({
            if (!isFarming) return@postDelayed

            webView.evaluateJavascript(getExactProgressScript) { res ->
                val newProgress = res?.replace("\"", "")?.toIntOrNull() ?: -1

                // Сравниваем: если процент НЕ изменился или дропса вообще нет
                if (newProgress != -1 && lastRecordedProgress != -1 && newProgress <= lastRecordedProgress) {
                    // ПРОГРЕСС НЕ ИДЕТ! Стример пустышка!
                    blacklist.add(savedStreamer.lowercase())
                    Toast.makeText(applicationContext, "⚠️ На $savedStreamer дропс не идет! Меняем стримера...", Toast.LENGTH_LONG).show()
                    openDirectoryAndPickStreamer()
                } else {
                    // Прогресс идет! Запоминаем новый процент и возвращаемся на стрим
                    if (newProgress != -1) lastRecordedProgress = newProgress
                    statusText.text = "✅ Дропс начисляется! Продолжаем $savedStreamer..."
                    webView.loadUrl("https://www.twitch.tv/$savedStreamer")
                }
            }
        }, 4000)
    }
}
