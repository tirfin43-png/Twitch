package com.drops.miner

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var targetGame = "world-of-tanks"

    private val optimizeScript = """
        (function() {
            var videos = document.getElementsByTagName('video');
            for(var i=0; i<videos.length; i++){
                videos[i].muted = true;
                videos[i].volume = 0;
            }
        })();
    """.trimIndent()

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

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#18181B"))
        }

        val inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#1F1F23"))
        }

        val gameInput = EditText(this).apply {
            hint = "игра (напр. rust, dota-2)"
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
            text = "Старт"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#9146FF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 12
            }
            setOnClickListener {
                // Автоматически заменяем пробелы на дефисы
                targetGame = gameInput.text.toString().trim().lowercase().replace(" ", "-")
                startFarmingDirectory()
            }
        }

        val inventoryBtn = Button(this).apply {
            text = "Drops"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3D"))
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

        val serviceIntent = Intent(this, DropFarmerService::class.java)
        startForegroundService(serviceIntent)

        startAutomationLoop()
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
                webView.evaluateJavascript(optimizeScript, null)
            }
        }

        // Загружаем сразу главную страницу
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

    private fun startFarmingDirectory() {
        // ИСПРАВЛЕНО: используем /category/ вместо устаревшего /game/
        val directoryUrl = "https://www.twitch.tv/directory/category/$targetGame?tl=DropsEnabled"
        webView.loadUrl(directoryUrl)

        Toast.makeText(this, "Ищем стримеров с Drops...", Toast.LENGTH_SHORT).show()

        handler.postDelayed({
            pickFirstStreamerWithDrops()
        }, 7000)
    }

    private fun pickFirstStreamerWithDrops() {
        val clickStreamerScript = """
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
                    streamLink.click();
                    return "clicked";
                }
                return "not_found";
            })();
        """.trimIndent()

        webView.evaluateJavascript(clickStreamerScript) { result ->
            if (result != null && result.contains("clicked")) {
                Toast.makeText(this, "Стрим запущен!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startAutomationLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val currentUrl = webView.url ?: ""

                if (!currentUrl.contains("/directory/") && !currentUrl.contains("/drops/")) {
                    webView.evaluateJavascript(checkStreamOnlineScript) { status ->
                        if (status != null && status.contains("offline")) {
                            startFarmingDirectory()
                        }
                    }
                    webView.evaluateJavascript(optimizeScript, null)
                }

                webView.evaluateJavascript(claimDropsScript) { count ->
                    if (count != null && count != "null" && count != "0") {
                        Toast.makeText(applicationContext, "Дропс забран!", Toast.LENGTH_SHORT).show()
                    }
                }

                handler.postDelayed(this, 180000)
            }
        }, 180000)
    }
}
