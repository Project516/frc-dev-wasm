// Browser parts of wpigui: the frame rate limits, input tracking and OpenURL.

#include <emscripten.h>

#include <string>

#include "wpi/gui/wpigui_openurl.hpp"

// When the user last used the page, in emscripten_get_now() time. A connected
// gamepad counts as in use.
EM_JS(double, wpiguiLastInputMs, (), {
  if (navigator.getGamepads && Array.from(navigator.getGamepads()).some(Boolean)) {
    return performance.now();
  }
  return Module.frcLastInput ?? -Infinity;
});

namespace {
const int kTrackInput = [] {
  EM_ASM({
    const mark = () => { Module.frcLastInput = performance.now(); };
    for (const type of ['pointerdown', 'pointermove', 'wheel', 'keydown']) {
      window.addEventListener(type, mark, { capture: true, passive: true });
    }
  });
  return 0;
}();
}  // namespace

namespace wpi {

// Minimum time between frames, see the main loop in wpigui.cpp.
double wpiguiMinFrameMs = 0;
// The same after a second without input.
double wpiguiIdleFrameMs = 0;

void gui::OpenURL(const std::string& url) {
  EM_ASM({ window.open(UTF8ToString($0), '_blank'); }, url.c_str());
}

}  // namespace wpi
