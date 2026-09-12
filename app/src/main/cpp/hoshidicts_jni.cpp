#include <jni.h>
#include <utf8.h>

#include <algorithm>
#include <cstdint>
#include <cctype>
#include <exception>
#include <fstream>
#include <filesystem>
#include <limits>
#include <memory>
#include <iterator>
#include <map>
#include <mutex>
#include <optional>
#include <sstream>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

#include "hoshidicts/importer.hpp"
#include "hoshidicts/lookup.hpp"

extern "C" {
}

namespace {
constexpr uint32_t kMaxJavaMediaBytes = 8u * 1024u * 1024u;

struct MediaIndexEntry {
  uint64_t offset = 0;
  uint32_t size = 0;
};

struct LookupContext {
  explicit LookupContext(std::vector<std::string> dictionary_paths)
      : dictionary_paths(std::move(dictionary_paths)), lookup(query, deinflector) {
    for (const auto& path : this->dictionary_paths) {
      query.add_term_dict(path);
      query.add_freq_dict(path);
      query.add_pitch_dict(path);
    }
  }

  std::vector<std::string> dictionary_paths;
  DictionaryQuery query;
  Deinflector deinflector;
  Lookup lookup;
  std::unordered_map<std::string, std::unordered_map<std::string, MediaIndexEntry>> media_indexes;
  std::mutex mutex;
};

std::string jstring_to_string(JNIEnv* env, jstring value) {
  if (value == nullptr) return {};
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (chars == nullptr) return {};
  std::string out(chars);
  env->ReleaseStringUTFChars(value, chars);
  return out;
}

std::vector<std::string> jstring_array_to_vector(JNIEnv* env, jobjectArray values) {
  std::vector<std::string> out;
  if (values == nullptr) return out;
  const jsize count = env->GetArrayLength(values);
  if (count <= 0) return out;
  out.reserve(static_cast<size_t>(count));
  for (jsize i = 0; i < count; ++i) {
    auto* raw = static_cast<jstring>(env->GetObjectArrayElement(values, i));
    out.push_back(jstring_to_string(env, raw));
    if (raw != nullptr) {
      env->DeleteLocalRef(raw);
    }
  }
  return out;
}

uint16_t read_u16_le(const char* data) {
  const auto* bytes = reinterpret_cast<const unsigned char*>(data);
  return static_cast<uint16_t>(bytes[0]) |
         static_cast<uint16_t>(bytes[1] << 8);
}

uint32_t read_u32_le(const char* data) {
  const auto* bytes = reinterpret_cast<const unsigned char*>(data);
  return static_cast<uint32_t>(bytes[0]) |
         (static_cast<uint32_t>(bytes[1]) << 8) |
         (static_cast<uint32_t>(bytes[2]) << 16) |
         (static_cast<uint32_t>(bytes[3]) << 24);
}

uint64_t read_u64_le(const char* data) {
  const auto* bytes = reinterpret_cast<const unsigned char*>(data);
  uint64_t value = 0;
  for (int i = 7; i >= 0; --i) {
    value = (value << 8) | bytes[i];
  }
  return value;
}

std::unordered_map<std::string, MediaIndexEntry> read_media_index(const std::filesystem::path& root) {
  std::unordered_map<std::string, MediaIndexEntry> index;
  std::ifstream input(root / "media_index.bin", std::ios::binary);
  if (!input) return index;

  while (input) {
    char path_len_buf[sizeof(uint16_t)];
    input.read(path_len_buf, sizeof(path_len_buf));
    if (!input) break;

    const uint16_t path_len = read_u16_le(path_len_buf);
    if (path_len == 0) break;

    std::string media_path(path_len, '\0');
    input.read(media_path.data(), path_len);
    if (!input) break;

    char offset_buf[sizeof(uint64_t)];
    char size_buf[sizeof(uint32_t)];
    input.read(offset_buf, sizeof(offset_buf));
    input.read(size_buf, sizeof(size_buf));
    if (!input) break;

    const uint64_t offset = read_u64_le(offset_buf);
    const uint32_t size = read_u32_le(size_buf);
    if (size > 0) {
      index.emplace(std::move(media_path), MediaIndexEntry{offset, size});
    }
  }
  return index;
}

std::vector<char> read_media_blob(const std::filesystem::path& root, const MediaIndexEntry& entry) {
  if (entry.size == 0 || entry.size > kMaxJavaMediaBytes ||
      entry.size > static_cast<uint32_t>(std::numeric_limits<jsize>::max())) {
    return {};
  }

  std::ifstream input(root / "media.bin", std::ios::binary);
  if (!input) return {};
  input.seekg(static_cast<std::streamoff>(entry.offset), std::ios::beg);
  if (!input) return {};

  std::vector<char> data(entry.size);
  input.read(data.data(), static_cast<std::streamsize>(data.size()));
  if (input.gcount() != static_cast<std::streamsize>(data.size())) {
    return {};
  }
  return data;
}

std::vector<char> get_imported_media_file(LookupContext* obj,
                                          const std::string& root,
                                          const std::string& media_path) {
  const auto root_path = std::filesystem::path(root);
  auto index_it = obj->media_indexes.find(root);
  if (index_it == obj->media_indexes.end()) {
    index_it = obj->media_indexes.emplace(root, read_media_index(root_path)).first;
  }

  const auto media_it = index_it->second.find(media_path);
  if (media_it == index_it->second.end()) return {};
  return read_media_blob(root_path, media_it->second);
}

bool path_starts_with(const std::filesystem::path& root, const std::filesystem::path& child) {
  auto root_it = root.begin();
  auto child_it = child.begin();
  for (; root_it != root.end(); ++root_it, ++child_it) {
    if (child_it == child.end() || *root_it != *child_it) return false;
  }
  return true;
}

std::optional<std::filesystem::path> safe_relative_media_path(std::string_view raw) {
  std::string normalized(raw);
  std::replace(normalized.begin(), normalized.end(), '\\', '/');
  if (normalized.empty() || normalized.front() == '/') return std::nullopt;

  std::filesystem::path out;
  for (const auto& component : std::filesystem::path(normalized)) {
    if (component == "." || component.empty()) continue;
    if (component == ".." || component == "/" || component.has_root_path()) {
      return std::nullopt;
    }
    out /= component;
  }
  if (out.empty()) return std::nullopt;
  return out;
}

std::optional<std::filesystem::path> safe_media_candidate(const std::string& root,
                                                          const std::string& media_path) {
  const auto relative = safe_relative_media_path(media_path);
  if (!relative.has_value()) return std::nullopt;

  std::error_code ec;
  auto root_path = std::filesystem::weakly_canonical(std::filesystem::path(root), ec);
  if (ec) {
    root_path = std::filesystem::absolute(std::filesystem::path(root)).lexically_normal();
  }
  const auto candidate = (root_path / relative.value()).lexically_normal();
  auto canonical_candidate = std::filesystem::weakly_canonical(candidate, ec);
  if (ec) {
    canonical_candidate = std::filesystem::absolute(candidate).lexically_normal();
  }
  if (!path_starts_with(root_path, canonical_candidate)) return std::nullopt;
  return canonical_candidate;
}

void append_json_string(std::ostringstream& out, std::string_view value) {
  out << '"';
  for (unsigned char ch : value) {
    switch (ch) {
      case '\"':
        out << "\\\"";
        break;
      case '\\':
        out << "\\\\";
        break;
      case '\b':
        out << "\\b";
        break;
      case '\f':
        out << "\\f";
        break;
      case '\n':
        out << "\\n";
        break;
      case '\r':
        out << "\\r";
        break;
      case '\t':
        out << "\\t";
        break;
      default:
        if (ch < 0x20) {
          static constexpr const char* HEX = "0123456789abcdef";
          out << "\\u00" << HEX[(ch >> 4) & 0x0F] << HEX[ch & 0x0F];
        } else {
          out << static_cast<char>(ch);
        }
        break;
    }
  }
  out << '"';
}

std::string json_error(std::string_view message) {
  std::ostringstream out;
  out << "{\"success\":false,\"error\":";
  append_json_string(out, message);
  out << "}";
  return out.str();
}

std::string build_import_json(const ImportResult& result) {
  std::ostringstream out;
  out << '{';
  out << "\"success\":" << (result.success ? "true" : "false");
  out << ",\"title\":";
  append_json_string(out, result.title);
  out << ",\"termCount\":" << result.term_count;
  out << ",\"metaCount\":" << result.meta_count;
  out << ",\"frequencyCount\":" << result.freq_count;
  out << ",\"pitchCount\":" << result.pitch_count;
  out << ",\"mediaCount\":" << result.media_count;

  out << ",\"dictPath\":";
  append_json_string(out, result.success ? result.dict_path : std::string{});

  out << ",\"errors\":[";
  for (size_t i = 0; i < result.errors.size(); ++i) {
    if (i > 0) out << ',';
    append_json_string(out, result.errors[i]);
  }
  out << "]";
  out << '}';
  return out.str();
}

jstring to_jstring(JNIEnv* env, const std::string& value) { return env->NewStringUTF(value.c_str()); }

jstring new_string(JNIEnv* env, const std::string& value) { return to_jstring(env, value); }

jobject new_frequency(JNIEnv* env, const Frequency& frequency) {
  jclass cls = env->FindClass("de/manhhao/hoshi/Frequency");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;)V");
  jstring display_value = new_string(env, frequency.display_value);
  jobject out = env->NewObject(cls, ctor, static_cast<jint>(frequency.value), display_value);
  env->DeleteLocalRef(display_value);
  return out;
}

jobjectArray new_frequency_array(JNIEnv* env, const std::vector<Frequency>& frequencies) {
  jclass cls = env->FindClass("de/manhhao/hoshi/Frequency");
  jobjectArray array =
      env->NewObjectArray(static_cast<jsize>(frequencies.size()), cls, nullptr);
  for (size_t i = 0; i < frequencies.size(); ++i) {
    jobject item = new_frequency(env, frequencies[i]);
    env->SetObjectArrayElement(array, static_cast<jsize>(i), item);
    env->DeleteLocalRef(item);
  }
  return array;
}

jobject new_glossary_entry(JNIEnv* env, const GlossaryEntry& entry) {
  jclass cls = env->FindClass("de/manhhao/hoshi/GlossaryEntry");
  jmethodID ctor = env->GetMethodID(
      cls, "<init>",
      "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
  jstring dict_name = new_string(env, entry.dict_name);
  jstring glossary = new_string(env, entry.glossary);
  jstring definition_tags = new_string(env, entry.definition_tags);
  jstring term_tags = new_string(env, entry.term_tags);
  jobject out = env->NewObject(cls, ctor, dict_name, glossary, definition_tags, term_tags);
  env->DeleteLocalRef(dict_name);
  env->DeleteLocalRef(glossary);
  env->DeleteLocalRef(definition_tags);
  env->DeleteLocalRef(term_tags);
  return out;
}

jobjectArray new_glossary_entry_array(JNIEnv* env, const std::vector<GlossaryEntry>& entries) {
  jclass cls = env->FindClass("de/manhhao/hoshi/GlossaryEntry");
  jobjectArray array = env->NewObjectArray(static_cast<jsize>(entries.size()), cls, nullptr);
  for (size_t i = 0; i < entries.size(); ++i) {
    jobject item = new_glossary_entry(env, entries[i]);
    env->SetObjectArrayElement(array, static_cast<jsize>(i), item);
    env->DeleteLocalRef(item);
  }
  return array;
}

jobject new_frequency_entry(JNIEnv* env, const FrequencyEntry& entry) {
  jclass cls = env->FindClass("de/manhhao/hoshi/FrequencyEntry");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;[Lde/manhhao/hoshi/Frequency;)V");
  jstring dict_name = new_string(env, entry.dict_name);
  jobjectArray frequencies = new_frequency_array(env, entry.frequencies);
  jobject out = env->NewObject(cls, ctor, dict_name, frequencies);
  env->DeleteLocalRef(dict_name);
  env->DeleteLocalRef(frequencies);
  return out;
}

jobjectArray new_frequency_entry_array(JNIEnv* env, const std::vector<FrequencyEntry>& entries) {
  jclass cls = env->FindClass("de/manhhao/hoshi/FrequencyEntry");
  jobjectArray array = env->NewObjectArray(static_cast<jsize>(entries.size()), cls, nullptr);
  for (size_t i = 0; i < entries.size(); ++i) {
    jobject item = new_frequency_entry(env, entries[i]);
    env->SetObjectArrayElement(array, static_cast<jsize>(i), item);
    env->DeleteLocalRef(item);
  }
  return array;
}

jobject new_pitch_entry(JNIEnv* env, const PitchEntry& entry) {
  jclass cls = env->FindClass("de/manhhao/hoshi/PitchEntry");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;[I)V");
  jstring dict_name = new_string(env, entry.dict_name);
  jintArray positions = env->NewIntArray(static_cast<jsize>(entry.pitch_positions.size()));
  if (!entry.pitch_positions.empty()) {
    env->SetIntArrayRegion(positions, 0, static_cast<jsize>(entry.pitch_positions.size()),
                           reinterpret_cast<const jint*>(entry.pitch_positions.data()));
  }
  jobject out = env->NewObject(cls, ctor, dict_name, positions);
  env->DeleteLocalRef(dict_name);
  env->DeleteLocalRef(positions);
  return out;
}

jobjectArray new_pitch_entry_array(JNIEnv* env, const std::vector<PitchEntry>& entries) {
  jclass cls = env->FindClass("de/manhhao/hoshi/PitchEntry");
  jobjectArray array = env->NewObjectArray(static_cast<jsize>(entries.size()), cls, nullptr);
  for (size_t i = 0; i < entries.size(); ++i) {
    jobject item = new_pitch_entry(env, entries[i]);
    env->SetObjectArrayElement(array, static_cast<jsize>(i), item);
    env->DeleteLocalRef(item);
  }
  return array;
}

jobject new_term_result(JNIEnv* env, const TermResult& term) {
  jclass cls = env->FindClass("de/manhhao/hoshi/TermResult");
  jmethodID ctor = env->GetMethodID(
      cls, "<init>",
      "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Lde/manhhao/hoshi/GlossaryEntry;[Lde/manhhao/hoshi/FrequencyEntry;[Lde/manhhao/hoshi/PitchEntry;)V");
  jstring expression = new_string(env, term.expression);
  jstring reading = new_string(env, term.reading);
  jstring rules = new_string(env, term.rules);
  jobjectArray glossaries = new_glossary_entry_array(env, term.glossaries);
  jobjectArray frequencies = new_frequency_entry_array(env, term.frequencies);
  jobjectArray pitches = new_pitch_entry_array(env, term.pitches);
  jobject out = env->NewObject(cls, ctor, expression, reading, rules, glossaries, frequencies,
                               pitches);
  env->DeleteLocalRef(expression);
  env->DeleteLocalRef(reading);
  env->DeleteLocalRef(rules);
  env->DeleteLocalRef(glossaries);
  env->DeleteLocalRef(frequencies);
  env->DeleteLocalRef(pitches);
  return out;
}

jobject new_lookup_result(JNIEnv* env, const LookupResult& result) {
  jclass cls = env->FindClass("de/manhhao/hoshi/LookupResult");
  jmethodID ctor = env->GetMethodID(
      cls, "<init>",
      "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Lde/manhhao/hoshi/TermResult;I)V");
  jstring matched = new_string(env, result.matched);
  jstring deinflected = new_string(env, result.deinflected);
  jclass stringClass = env->FindClass("java/lang/String");
  jobjectArray process = env->NewObjectArray(static_cast<jsize>(result.trace.size()), stringClass, nullptr);
  for (size_t i = 0; i < result.trace.size(); ++i) {
    jstring step = new_string(env, result.trace[i].name);
    env->SetObjectArrayElement(process, static_cast<jsize>(i), step);
    env->DeleteLocalRef(step);
  }
  jobject term = new_term_result(env, result.term);
  jobject out = env->NewObject(cls, ctor, matched, deinflected, process, term,
                               static_cast<jint>(result.preprocessor_steps));
  env->DeleteLocalRef(matched);
  env->DeleteLocalRef(deinflected);
  env->DeleteLocalRef(process);
  env->DeleteLocalRef(term);
  return out;
}

jobjectArray new_lookup_result_array(JNIEnv* env, const std::vector<LookupResult>& results) {
  jclass cls = env->FindClass("de/manhhao/hoshi/LookupResult");
  jobjectArray array = env->NewObjectArray(static_cast<jsize>(results.size()), cls, nullptr);
  for (size_t i = 0; i < results.size(); ++i) {
    jobject item = new_lookup_result(env, results[i]);
    env->SetObjectArrayElement(array, static_cast<jsize>(i), item);
    env->DeleteLocalRef(item);
  }
  return array;
}

jobject new_dictionary_style(JNIEnv* env, const DictionaryStyle& style) {
  jclass cls = env->FindClass("de/manhhao/hoshi/DictionaryStyle");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");
  jstring dict_name = new_string(env, style.dict_name);
  jstring styles = new_string(env, style.styles);
  jobject out = env->NewObject(cls, ctor, dict_name, styles);
  env->DeleteLocalRef(dict_name);
  env->DeleteLocalRef(styles);
  return out;
}

jobjectArray new_dictionary_style_array(JNIEnv* env, const std::vector<DictionaryStyle>& styles) {
  jclass cls = env->FindClass("de/manhhao/hoshi/DictionaryStyle");
  jobjectArray array = env->NewObjectArray(static_cast<jsize>(styles.size()), cls, nullptr);
  for (size_t i = 0; i < styles.size(); ++i) {
    jobject item = new_dictionary_style(env, styles[i]);
    env->SetObjectArrayElement(array, static_cast<jsize>(i), item);
    env->DeleteLocalRef(item);
  }
  return array;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_de_manhhao_hoshi_HoshiDicts_createLookupObject(JNIEnv*, jobject) {
  return reinterpret_cast<jlong>(new LookupContext(std::vector<std::string>{}));
}

extern "C" JNIEXPORT void JNICALL
Java_de_manhhao_hoshi_HoshiDicts_destroyLookupObject(JNIEnv*, jobject, jlong session) {
  delete reinterpret_cast<LookupContext*>(session);
}

extern "C" JNIEXPORT void JNICALL
Java_de_manhhao_hoshi_HoshiDicts_rebuildQuery(JNIEnv* env,
                                              jobject,
                                              jlong session,
                                              jobjectArray term_paths,
                                              jobjectArray freq_paths,
                                              jobjectArray pitch_paths) {
  auto* obj = reinterpret_cast<LookupContext*>(session);
  if (obj == nullptr) return;
  std::lock_guard<std::mutex> lock(obj->mutex);
  obj->dictionary_paths.clear();
  obj->media_indexes.clear();
  obj->query = DictionaryQuery{};
  for (const auto& path : jstring_array_to_vector(env, term_paths)) {
    obj->dictionary_paths.push_back(path);
    obj->query.add_term_dict(path);
  }
  for (const auto& path : jstring_array_to_vector(env, freq_paths)) {
    obj->dictionary_paths.push_back(path);
    obj->query.add_freq_dict(path);
  }
  for (const auto& path : jstring_array_to_vector(env, pitch_paths)) {
    obj->dictionary_paths.push_back(path);
    obj->query.add_pitch_dict(path);
  }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_de_manhhao_hoshi_HoshiDicts_lookup(JNIEnv* env,
                                        jobject,
                                        jlong session,
                                        jstring text,
                                        jint max_results,
                                        jint scan_length) {
  try {
    auto* obj = reinterpret_cast<LookupContext*>(session);
    if (obj == nullptr) return new_lookup_result_array(env, {});
    const std::string text_str = jstring_to_string(env, text);
    if (text_str.empty()) return new_lookup_result_array(env, {});
    const auto result = obj->lookup.lookup(text_str, static_cast<int>(max_results),
                                           static_cast<size_t>(std::max(static_cast<int>(scan_length), 1)));
    return new_lookup_result_array(env, result);
  } catch (...) {
    return new_lookup_result_array(env, {});
  }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_de_manhhao_hoshi_HoshiDicts_getStyles(JNIEnv* env, jobject, jlong session) {
  auto* obj = reinterpret_cast<LookupContext*>(session);
  if (obj == nullptr) return new_dictionary_style_array(env, {});
  return new_dictionary_style_array(env, obj->query.get_styles());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_de_manhhao_hoshi_HoshiDicts_getMediaFile(JNIEnv* env,
                                              jobject,
                                              jlong session,
                                              jstring dict_name,
                                              jstring media_path) {
  auto* obj = reinterpret_cast<LookupContext*>(session);
  if (obj == nullptr) return nullptr;
  const std::string dict_name_str = jstring_to_string(env, dict_name);
  const std::string media_path_str = jstring_to_string(env, media_path);
  if (media_path_str.empty()) return nullptr;
  std::lock_guard<std::mutex> lock(obj->mutex);

  const std::vector<char> current_data = obj->query.get_media_file(dict_name_str, media_path_str);
  if (!current_data.empty()) {
    if (current_data.size() > kMaxJavaMediaBytes ||
        current_data.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
      return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(current_data.size()));
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(current_data.size()),
                            reinterpret_cast<const jbyte*>(current_data.data()));
    return result;
  }

  std::vector<std::string> roots;
  roots.reserve(obj->dictionary_paths.size());
  if (!dict_name_str.empty()) {
    for (const auto& root : obj->dictionary_paths) {
      if (std::filesystem::path(root).filename().string() == dict_name_str) {
        roots.push_back(root);
      }
    }
  }
  for (const auto& root : obj->dictionary_paths) {
    if (std::find(roots.begin(), roots.end(), root) == roots.end()) {
      roots.push_back(root);
    }
  }

  for (const auto& root : roots) {
    std::vector<char> imported_data = get_imported_media_file(obj, root, media_path_str);
    if (!imported_data.empty()) {
      jbyteArray result = env->NewByteArray(static_cast<jsize>(imported_data.size()));
      env->SetByteArrayRegion(result, 0, static_cast<jsize>(imported_data.size()),
                              reinterpret_cast<const jbyte*>(imported_data.data()));
      return result;
    }

    const auto candidate = safe_media_candidate(root, media_path_str);
    if (!candidate.has_value()) continue;
    if (!std::filesystem::is_regular_file(candidate.value())) continue;
    std::error_code size_error;
    const auto file_size = std::filesystem::file_size(candidate.value(), size_error);
    if (size_error || file_size == 0 ||
        file_size > static_cast<uintmax_t>(std::numeric_limits<jsize>::max())) {
      continue;
    }
    std::ifstream input(candidate.value(), std::ios::binary);
    if (!input) continue;
    std::vector<char> data((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
    if (data.empty()) continue;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(data.size()));
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(data.size()),
                            reinterpret_cast<const jbyte*>(data.data()));
    return result;
  }
  return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_moe_tekuza_m9player_HoshiNativeBridge_nativeImportZip(JNIEnv* env,
                                                            jclass,
                                                            jstring j_zip_path,
                                                            jstring j_output_dir,
                                                            jboolean j_low_ram) {
  try {
    const std::string zip_path = jstring_to_string(env, j_zip_path);
    const std::string output_dir = jstring_to_string(env, j_output_dir);
    if (zip_path.empty() || output_dir.empty()) {
      return to_jstring(env, json_error("invalid import path"));
    }
    const ImportResult result = dictionary_importer::import(zip_path, output_dir, j_low_ram == JNI_TRUE);
    return to_jstring(env, build_import_json(result));
  } catch (const std::exception& e) {
    return to_jstring(env, json_error(e.what()));
  } catch (...) {
    return to_jstring(env, json_error("unknown native import error"));
  }
}

