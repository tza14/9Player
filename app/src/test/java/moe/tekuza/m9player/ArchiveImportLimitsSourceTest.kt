package moe.tekuza.m9player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 词典导入链路的**资源上限 tripwire**：条目数 / 字节上限 / 路径安全 / 越界检查都散在
 * C++ 与 JNI 里，native 侧目前没有测试目标（真测试需要一个 ctest target，见
 * `app/src/main/cpp/CMakeLists.txt` 里被 `CMAKE_DISABLE_TESTING` 挡住的那些），
 * 所以这里只钉"上限常量还在、调用点还在用带限读法"。
 * 不写按名字的负断言（换个变量名就失效，属于假守卫）。
 */
class ArchiveImportLimitsSourceTest {
    @Test
    fun nativeHoshiImporterLimitsEntriesAndStreamsMediaWrites() {
        val source = File("src/main/cpp/hoshidicts/src/importer.cpp").readText()
        val zipSource = File("src/main/cpp/hoshidicts/src/zip/zip.cpp").readText()
        val zipHeader = File("src/main/cpp/hoshidicts/src/zip/zip.hpp").readText()

        assertTrue(zipHeader.contains("kMaxZipEntries = 20000"))
        assertTrue(zipHeader.contains("kMaxBankBytes = 64u * 1024u * 1024u"))
        assertTrue(zipHeader.contains("kMaxZipPathBytes = 512"))
        assertTrue(zipHeader.contains("kMaxMediaEntryBytes = 32u * 1024u * 1024u"))
        assertTrue(zipHeader.contains("kMaxTotalMediaBytes = 256ull * 1024ull * 1024ull"))
        assertTrue(zipSource.contains("is_safe_zip_entry_name"))
        assertTrue(zipSource.contains("e.compressed_size > file.size - e.data_offset"))
        assertTrue(source.contains("zip.read(index_idx, Zip::kMaxIndexBytes)"))
        assertTrue(source.contains("zip.read(styles_idx, Zip::kMaxStyleBytes)"))
        assertTrue(source.contains("media.write(buf.data()"))
    }

    @Test
    fun dictionaryImportAndMediaResponsesHaveExplicitByteLimits() {
        val store = File("src/main/java/moe/tekuza/m9player/DictionarySqlStore.kt").readText()
        val media = File("src/main/java/moe/tekuza/m9player/DictionaryMediaBytes.kt").readText()
        val jni = File("src/main/cpp/hoshidicts_jni.cpp").readText()

        assertTrue(store.contains("HOSHI_IMPORT_ARCHIVE_MAX_BYTES"))
        assertTrue(media.contains("DICTIONARY_MEDIA_RESPONSE_MAX_BYTES"))
        assertTrue(media.contains("readDictionaryMediaBytesLimited"))
        assertTrue(jni.contains("kMaxJavaMediaBytes"))
        assertTrue(jni.contains("entry.size > kMaxJavaMediaBytes"))
        assertTrue(jni.contains("obj->query.get_media_file(dict_name_str, media_path_str)"))
        assertTrue(jni.indexOf("obj->query.get_media_file(dict_name_str, media_path_str)") < jni.indexOf("get_imported_media_file(obj, root, media_path_str)"))
        assertTrue(jni.contains("read_media_index(root_path)"))
    }
}
