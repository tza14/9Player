#include "zip.hpp"

#include <libdeflate.h>

#include <cstdint>
#include <cstring>
#include <cctype>
#include <string_view>

#include "../memory/memory.hpp"

namespace {
template <typename T>
T read_at(const uint8_t* base, size_t offset) {
  T val;
  std::memcpy(&val, base + offset, sizeof(T));
  return val;
}

bool is_safe_zip_entry_name(std::string_view name) {
  if (name.empty() || name.size() > Zip::kMaxZipPathBytes || name.front() == '/' || name.front() == '\\') return false;
  if (name.size() >= 2 && std::isalpha(static_cast<unsigned char>(name[0])) && name[1] == ':') return false;
  size_t start = 0;
  while (start <= name.size()) {
    const size_t end = name.find_first_of("/\\", start);
    const auto component = name.substr(start, end == std::string_view::npos ? name.size() - start : end - start);
    if (component == "..") return false;
    if (end == std::string_view::npos) break;
    start = end + 1;
  }
  return true;
}

bool decompress_entry_into(const uint8_t* file_data, const ZipEntry& e, char* out) {
  const uint8_t* src = file_data + e.data_offset;
  if (e.compression_method == 0) {
    std::memcpy(out, src, e.uncompressed_size);
    return true;
  }
  if (e.compression_method == 8) {
    thread_local auto* d = libdeflate_alloc_decompressor();
    return libdeflate_deflate_decompress(d, src, e.compressed_size, out, e.uncompressed_size, nullptr) ==
           LIBDEFLATE_SUCCESS;
  }
  return false;
}
}

Zip::~Zip() {
  memory::unmap(file);
}

bool Zip::open(const std::string& path) {
  file = memory::map_rd(path);
  if (!file) {
    return false;
  }

  return parse_central_directory();
}

int Zip::find(const std::string& name) const {
  for (int i = 0; i < static_cast<int>(entries.size()); ++i) {
    if (entries[i].name == name) {
      return i;
    }
  }
  return -1;
}

std::string Zip::read(int index, size_t max_bytes) const {
  if (index < 0 || static_cast<size_t>(index) >= entries.size()) return "";
  const auto& e = entries[index];
  if (e.uncompressed_size > max_bytes || e.data_offset > file.size ||
      e.compressed_size > file.size - e.data_offset) return "";
  if (e.uncompressed_size == 0) {
    return "";
  }

  std::string result;
  result.resize(e.uncompressed_size);
  if (!decompress_entry_into(file.data, e, result.data())) {
    return "";
  }
  return result;
}

std::optional<Zip::MediaResult> Zip::read_media(int index) const {
  if (index < 0 || static_cast<size_t>(index) >= entries.size()) return std::nullopt;
  const auto& e = entries[index];
  if (e.uncompressed_size > kMaxMediaEntryBytes || e.data_offset > file.size ||
      e.compressed_size > file.size - e.data_offset) return std::nullopt;
  MediaResult out;
  out.path = e.name;
  out.blob.resize(e.uncompressed_size);
  if (e.uncompressed_size == 0) {
    return out;
  }

  if (!decompress_entry_into(file.data, e, out.blob.data())) {
    return std::nullopt;
  }
  return out;
}

// https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT
bool Zip::parse_central_directory() {
  const auto* base = file.data;
  if (file.size < 22) {
    return false;
  }

  size_t eocd = file.size - 22;
  while (eocd > 0 && read_at<uint32_t>(base, eocd) != 0x06054b50) {
    eocd--;
  }
  if (read_at<uint32_t>(base, eocd) != 0x06054b50) return false;

  uint64_t total_entries = read_at<uint16_t>(base, eocd + 10);
  uint64_t cd_offset = read_at<uint32_t>(base, eocd + 16);

  if (eocd >= 20 && read_at<uint32_t>(base, eocd - 20) == 0x07064b50) {
    auto eocd64_offset = read_at<uint64_t>(base, eocd - 12);
    if (eocd64_offset + 56 <= file.size && read_at<uint32_t>(base, eocd64_offset) == 0x06064b50) {
      total_entries = read_at<uint64_t>(base, eocd64_offset + 32);
      cd_offset = read_at<uint64_t>(base, eocd64_offset + 48);
    }
  }

  if (total_entries > kMaxZipEntries || cd_offset > file.size) return false;

  entries.reserve(total_entries);
  size_t pos = cd_offset;

  for (uint64_t i = 0; i < total_entries; ++i) {
    if (pos + 46 > file.size) {
      return false;
    }
    if (read_at<uint32_t>(base, pos) != 0x02014b50) {
      return false;
    }

    ZipEntry e;
    e.compression_method = read_at<uint16_t>(base, pos + 10);
    e.compressed_size = read_at<uint32_t>(base, pos + 20);
    e.uncompressed_size = read_at<uint32_t>(base, pos + 24);

    auto name_len = read_at<uint16_t>(base, pos + 28);
    auto extra_len = read_at<uint16_t>(base, pos + 30);
    auto comment_len = read_at<uint16_t>(base, pos + 32);

    const size_t record_size = 46ull + name_len + extra_len + comment_len;
    if (record_size > file.size - pos) return false;

    auto lfh_offset = read_at<uint32_t>(base, pos + 42);
    e.name.assign(reinterpret_cast<const char*>(base + pos + 46), name_len);
    if (!is_safe_zip_entry_name(e.name)) return false;

    if (lfh_offset > file.size || file.size - lfh_offset < 30 ||
        read_at<uint32_t>(base, lfh_offset) != 0x04034b50) return false;
    auto lfh_name_len = read_at<uint16_t>(base, lfh_offset + 26);
    auto lfh_extra_len = read_at<uint16_t>(base, lfh_offset + 28);
    e.data_offset = lfh_offset + 30 + lfh_name_len + lfh_extra_len;
    if (e.data_offset > file.size || e.compressed_size > file.size - e.data_offset) return false;

    entries.push_back(std::move(e));
    pos += record_size;
  }

  return true;
}
