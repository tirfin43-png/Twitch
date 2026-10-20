package com.drops.miner

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var targetGame = "rust" // Игра по умолчанию

    // Скрипт для заглушки звука (чтобы не перебивал TikTok/игры)
    private val optimizeScript = """
        (function() {
            var videos = document.getElementsByTagName('video');
            for(var i=0; i<videos.length; i++){
                videos[i].muted = true;
                videos[i].volume = 0;
            }
        })();
    """.trimIndent()

    // Скрипт для автоматического нажатия на кнопку "Получить" (Claim)
    private val claimDropsScript = """
        (function() {
            var buttons = document.querySelectorAll('button');
            var claimed = 0;
            buttons.forEach(function(btn) {
                if (btn.innerText.includes('Claim') || btn.innerText.includes('Получить')) {
                    btn.click();
                    claimed++;
                }
            });
            return claimed;
        })();
    """.trimIndent()

    // Скрипт проверки статуса стримера (онлайн или оффлайн)
    private val checkStreamOnlineScript = """
        (function() {
            var offlineIndicator = document.querySelector('.channel-status-info--offline');
            var videoPlayer = document.querySelector('video');
            if (offlineIndicator !== null) return "offline";
            if (!videoPlayer) return "loading";
            return "online";
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Создаем интерфейс без необходимости лезть в XML файлы
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val gameInput = EditText(this).apply {
            hint = "slug игры (напр. rust, dota-2)"
            setText(targetGame)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val startBtn = Button(this).apply {
            text = "Старт"
            setOnClickListener {
                targetGame = gameInput.text.toString().trim().lowercase()
                startFarmingDirectory()
            }
        }

        val inventoryBtn = Button(this).apply {
            text = "Инвентарь"
            setOnClickListener {
                webView.loadUrl("https://www.twitch.tv/drops/inventory")
            }
        }

        inputLayout.addView(gameInput)
        inputLayout.addView(startBtn)
        inputLayout.addView(inventoryBtn)
        layout.addView(inputLayout)

        webView = WebView(this)
        layout.addView(webView, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        setContentView(layout)

        setupWebView()

        // Запуск службы в шторке Android
        val serviceIntent = Intent(this, DropFarmerService::class.java)
        startForegroundService(serviceIntent)

        // Запуск бесконечного цикла проверки
        startAutomationLoop()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Разрешаем видео играть в фоне без нажатия по экрану:
        settings.mediaPlaybackRequiresUserGesture = false
        // Притворяемся компьютером, чтобы был доступен весь функционал Twitch
        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webView.evaluateJavascript(optimizeScript, null)
            }
        }

        // Открываем страницу авторизации
        webView.loadUrl("https://www.twitch.tv/login")
        Toast.makeText(this, "Войдите в свой Twitch аккаунт!", Toast.LENGTH_LONG).show()
    }

    // Защита от засыпания при переходе в TikTok / другие приложения:
    override fun onPause() {
        super.onPause()
        webView.resumeTimers() // Не даем таймерам страницы уснуть
    }

    override fun onStop() {
        super.onStop()
        webView.resumeTimers() // Принудительно держим страницу бодрой в фоне
    }

    private fun startFarmingDirectory() {
        val directoryUrl = "https://www.twitch.tv/directory/game/$targetGame?tl=DropsEnabled"
        webView.loadUrl(directoryUrl)

        // Ждем 7 секунд для прогрузки списка и кликаем на первого стримера
        handler.postDelayed({
            pickFirstStreamerWithDrops()
        }, 7000)
    }

    private fun pickFirstStreamerWithDrops() {
        val clickStreamerScript = """
            (function() {
                var streamLink = document.querySelector('a[data-a-target="preview-card-image-link"]');
                if (streamLink) {
                    streamLink.click();
                    return "clicked";
                }
                return "not_found";
            })();
        """.trimIndent()

        webView.evaluateJavascript(clickStreamerScript) { result ->
            if (result != null && result.contains("clicked")) {
                Toast.makeText(this, "Включили стрим с дропсами", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startAutomationLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val currentUrl = webView.url ?: ""

                // 1. Проверяем, не выключил ли стример трансляцию
                if (!currentUrl.contains("/directory/") && !currentUrl.contains("/drops/")) {
                    webView.evaluateJavascript(checkStreamOnlineScript) { status ->
                        if (status != null && status.contains("offline")) {
                            // Если оффлайн — ищем нового стримера
                            startFarmingDirectory()
                        }
                    }
                    // Повторно выключаем звук на случай, если плеер обновился
                    webView.evaluateJavascript(optimizeScript, null)
                }

                // 2. Проверяем наличие кнопки забрать дропс
                webView.evaluateJavascript(claimDropsScript) { count ->
                    if (count != null && count != "null" && count != "0") {
                        Toast.makeText(applicationContext, "Дропс забран!", Toast.LENGTH_SHORT).show()
                    }
                }

                // Запуск каждые 3 минуты (180000 миллисекунд)
                handler.postDelayed(this, 180000)
            }
        }, 180000)
    }
}
