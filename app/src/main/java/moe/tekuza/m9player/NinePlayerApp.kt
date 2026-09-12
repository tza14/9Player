package moe.tekuza.m9player

import android.app.Application
import android.webkit.WebView

class NinePlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WebViewPreloader.warmup(this)
        // 崩溃诊断：只在崩溃那一刻写一份堆栈 + 最后一段日志到应用私有目录（无开关、无常驻采集）
        installCrashDiagnostics(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        clearDictionaryMediaPayloadCache()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        clearDictionaryMediaPayloadCache()
    }
}

private object WebViewPreloader {
    private var dummy: WebView? = null

    fun warmup(app: Application) {
        if (dummy != null) return
        runCatching {
            dummy = WebView(app).apply {
                // Keep one preloaded instance alive to reduce first real WebView startup cost.
                loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
            }
        }
    }
}
