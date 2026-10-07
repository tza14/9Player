package moe.tekuza.m9player

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * 用 [ZipFile]（读**中央目录**）打开 EPUB 内容流：先把流落到临时文件。
 *
 * 为什么不能用 [java.util.zip.ZipInputStream]：它的流式读法只看**本地头**。有的 EPUB（例：KADOKAWA
 * 的《あのね、じつは、はじめてなんだ。》）所有条目都是 stored、本地头里 csize/usize 写 0、真实尺寸只在
 * 中央目录（bit 3 数据描述符）——`ZipInputStream` 会**静默**给出 0 字节，并在第一个条目之后判定"没有更多
 * 条目"。缓存因此只落下一个 0 字节的 mimetype，索引却照样写成功，于是这本书被解析成 1 页空白、而且因为
 * stamp 命中会永远复用那份坏缓存（真机排查记录：`reader_epub_cache/<key>/` 里只有 mimetype + index）。
 */
internal fun <T> withEpubZipFile(context: Context, uri: Uri, block: (ZipFile) -> T): T {
    val input = context.contentResolver.openInputStream(uri)
        ?: error("Unable to open EPUB stream: $uri")
    return withZipFileFromStream(input, context.cacheDir, block)
}

/**
 * [withEpubZipFile] 的内核：把 [input]（**本函数负责关闭**）落到 [tempDir] 下的临时文件再按中央目录打开。
 * 只依赖 JVM，所以能在真实 EPUB 样本上直接验证读法。
 */
internal fun <T> withZipFileFromStream(input: InputStream, tempDir: File, block: (ZipFile) -> T): T {
    val temp = File.createTempFile("epub-source-", ".zip", tempDir)
    try {
        input.use { source ->
            temp.outputStream().use { output -> source.copyTo(output) }
        }
        return ZipFile(temp).use(block)
    } finally {
        runCatching { temp.delete() }
    }
}
