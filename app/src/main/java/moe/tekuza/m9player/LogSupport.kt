package moe.tekuza.m9player

import android.util.Log

/**
 * verbose 日志门控：release 构建里完全不执行（**连消息字符串都不拼**，因为消息是 lambda）。
 * `Log.w` / `Log.e` 不经过这里，永远保留——它们是崩溃文件与诊断报告里唯一有价值的上下文。
 *
 * 开关来自各变体的 `VERBOSE_LOGS`：debug 与 debugSuffix（⑨debug 测试包）= true，release = false。
 * 注意不要用 BuildConfig.DEBUG 代替：debugSuffix 是 debuggable = false，那会连带把 ⑨debug
 * 的日志一起关掉。
 */
internal inline fun logDebug(tag: String, message: () -> String) {
    if (BuildConfig.VERBOSE_LOGS) {
        Log.d(tag, message())
    }
}
