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
    private var targetGame = "rust" // Значение по умолчанию (slug игры в Twitch)

    // JS-скрипт для отключения качества видео и экономии трафика/батареи
    private val optimizeScript = """
        (function() {
            var videos = document.getElementsByTagName('video');
            for(var i=0; i<videos.length; i++){
                videos[i].muted = true;
                videos[i].volume = 0;
            }
        })();
    """.trimIndent()

    // JS-скрипт для авто-клейма наград в инвентаре Drops
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

    // Проверка, идет ли стрим, или он офлайн
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

        // Разметка интерфейса программно (без XML файлов верстки)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val gameInput = EditText(this).apply {
            hint = "Введите slug игры (напр. rust, apex-legends)"
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
            text = "Drops"
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

        // Запуск Foreground сервиса для стабильной работы
        val serviceIntent = Intent(this, DropFarmerService::class.java)
        startForegroundService(serviceIntent)

        // Фоновый цикл (каждые 4 минуты проверяет состояние и клеймит дропсы)
        startAutomationLoop()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        // Используем Desktop UserAgent для корректной навигации по тегам Drops
        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webView.evaluateJavascript(optimizeScript, null)
            }
        }

        // Открываем Twitch (потребуется один раз вручную залогиниться)
        webView.loadUrl("https://www.twitch.tv/login")
        Toast.makeText(this, "Войдите в аккаунт Twitch для старта", Toast.LENGTH_LONG).show()
    }

    private fun startFarmingDirectory() {
        // Переход в каталог игры с фильтром по тегу Drops
        val directoryUrl = "https://www.twitch.tv/directory/game/$targetGame?tl=DropsEnabled"
        webView.loadUrl(directoryUrl)

        // Через 7 секунд выбираем первого стримера
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
            if (result.contains("clicked")) {
                Toast.makeText(this, "Переключились на стримера", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startAutomationLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val currentUrl = webView.url ?: ""

                // 1. Если смотрим стрим — проверяем, не офлайн ли он
                if (!currentUrl.contains("/directory/") && !currentUrl.contains("/drops/")) {
                    webView.evaluateJavascript(checkStreamOnlineScript) { status ->
                        if (status.contains("offline")) {
                            // Стример выключил трансляцию — ищем следующего
                            startFarmingDirectory()
                        }
                    }
                    webView.evaluateJavascript(optimizeScript, null)
                }

                // 2. Периодически клеймим дропсы
                webView.evaluateJavascript(claimDropsScript) { count ->
                    if (count != null && count != "null" && count != "0") {
                        Toast.makeText(applicationContext, "Забрали $count Drops!", Toast.LENGTH_SHORT).show()
                    }
                }

                handler.postDelayed(this, 180000) // Повтор каждые 3 минуты
            }
        }, 180000)
    }
}
