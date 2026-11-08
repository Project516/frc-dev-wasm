// Same image list as allwpilib's field_images.cpp, but the files are fetched
// when a field is selected instead of being linked in (they are 9.7 MB). Served
// from fields/ next to the page.

#include "wpi/fields/field_images.hpp"

#include <emscripten.h>

#include <map>
#include <span>
#include <string>

// Synchronous XHR returns binary data only through this charset trick.
EM_JS(char*, FetchField, (const char* name, int* len), {
  var xhr = new XMLHttpRequest();
  xhr.open('GET', 'fields/' + UTF8ToString(name), false);
  xhr.overrideMimeType('text/plain; charset=x-user-defined');
  xhr.send();
  if (xhr.status !== 200) {
    HEAP32[len >> 2] = 0;
    return 0;
  }
  var text = xhr.responseText;
  var ptr = _malloc(text.length);
  for (var i = 0; i < text.length; i++) {
    HEAPU8[ptr + i] = text.charCodeAt(i) & 0xff;
  }
  HEAP32[len >> 2] = text.length;
  return ptr;
});

static std::string_view Load(const char* name) {
  static std::map<std::string, std::string_view> cache;
  auto it = cache.find(name);
  if (it == cache.end()) {
    int len = 0;
    const char* data = FetchField(name, &len);
    it = cache.emplace(name, std::string_view{data, static_cast<size_t>(len)})
             .first;
  }
  return it->second;
}

#define IMAGE(path) \
  { path, [] { return Load(path); } }

namespace wpi::fields {

static const FieldImageResource FIELD_IMAGES[] = {
    IMAGE("frc/2026-rebuilt.png"),
    IMAGE("ftc/2025-2026-decode.png"),
    IMAGE("frc/2025-reefscape.png"),
    IMAGE("ftc/2024-2025-intothedeep.png"),
    IMAGE("frc/2024-crescendo.png"),
    IMAGE("frc/2023-chargedup.png"),
    IMAGE("frc/2022-rapidreact.png"),
    IMAGE("frc/2021-infiniterecharge.png"),
    IMAGE("frc/2021-barrelracingpath.png"),
    IMAGE("frc/2021-bouncepath.png"),
    IMAGE("frc/2021-galacticsearcha.png"),
    IMAGE("frc/2021-galacticsearchb.png"),
    IMAGE("frc/2021-slalompath.png"),
    IMAGE("frc/2020-infiniterecharge.png"),
    IMAGE("frc/2019-deepspace.jpg"),
    IMAGE("frc/2018-powerup.jpg"),
};

std::span<const FieldImageResource> GetFieldImages() {
  return FIELD_IMAGES;
}

std::string_view GetFieldImage(std::string_view resourcePath) {
  for (auto&& image : FIELD_IMAGES) {
    if (std::string_view{image.resourcePath} == resourcePath) {
      return image.getImage();
    }
  }
  return {};
}

}  // namespace wpi::fields
