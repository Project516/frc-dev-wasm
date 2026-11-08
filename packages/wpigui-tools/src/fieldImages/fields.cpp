// Same field list as allwpilib's fieldImages, but the files are fetched when a
// field is selected instead of being linked in (they are 9.7 MB). Served from
// fields/ next to the page.

#include "fields/fields.h"

#include <emscripten.h>

#include <map>
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

#define FIELD(label, json, image)                                  \
  {                                                                \
    label, [] { return Load(json); }, [] { return Load(image); }   \
  }

using namespace fields;

static const Field kFields[] = {
    FIELD("2026 Rebuilt", "2026-rebuilt.json", "2026-field.png"),
    FIELD("2025 Reefscape", "2025-reefscape.json", "2025-field.png"),
    FIELD("2024 Crescendo", "2024-crescendo.json", "2024-field.png"),
    FIELD("2023 Charged Up", "2023-chargedup.json", "2023-field.png"),
    FIELD("2022 Rapid React", "2022-rapidreact.json", "2022-field.png"),
    FIELD("2021 Barrel Racing Path", "2021-barrelracingpath.json",
          "2021-barrel.png"),
    FIELD("2021 Bounce Path", "2021-bouncepath.json", "2021-bounce.png"),
    FIELD("2021 Galactic Search A", "2021-galacticsearcha.json",
          "2021-galacticsearcha.png"),
    FIELD("2021 Galactic Search B", "2021-galacticsearchb.json",
          "2021-galacticsearchb.png"),
    FIELD("2021 Infinite Recharge", "2021-infiniterecharge.json",
          "2021-field.png"),
    FIELD("2021 Slalom Path", "2021-slalompath.json", "2021-slalom.png"),
    FIELD("2020 Infinite Recharge", "2020-infiniterecharge.json",
          "2020-field.png"),
    FIELD("2019 Destination: Deep Space", "2019-deepspace.json",
          "2019-field.jpg"),
    FIELD("2018 Power Up", "2018-powerup.json", "2018-field.jpg"),
};

std::span<const Field> fields::GetFields() {
  return kFields;
}
