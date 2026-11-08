// wpigui platform backend for browsers: GLFW (emscripten port) and WebGL2 via
// imgui's OpenGL3 backend. Counterpart of wpigui's opengl3/wpigui_opengl3.cpp.

#include <algorithm>
#include <cstdio>
#include <string>

#include <emscripten.h>
#include <GLES3/gl3.h>
#include <GLFW/emscripten_glfw3.h>
#include <GLFW/glfw3.h>
#include <imgui.h>
#include <imgui_impl_glfw.h>
#include <imgui_impl_opengl3.h>

#include "wpigui.h"
#include "wpigui_internal.h"
#include "wpigui_openurl.h"

using namespace wpi::gui;

static bool gPlatformValid = false;

// The saved layout comes from a desktop window, often larger than the tab. It is drawn scaled
// down to fit, as zooming out would; the saved size and window positions do not change.
static float gFitScale = 1;

static void FitCursorPos(GLFWwindow* window, double x, double y) {
  ImGui_ImplGlfw_CursorPosCallback(window, x / gFitScale, y / gFitScale);
}



// WebGL has no BGRA upload.
static const unsigned char* ToRGBA(PixelFormat format, int width, int height,
                                   const unsigned char* data,
                                   std::string& storage) {
  if (format != kPixelBGRA) {
    return data;
  }
  storage.assign(reinterpret_cast<const char*>(data), width * height * 4);
  for (size_t i = 0; i < storage.size(); i += 4) {
    std::swap(storage[i], storage[i + 2]);
  }
  return reinterpret_cast<const unsigned char*>(storage.data());
}

// When the user last used the page, in emscripten_get_now() time. A connected
// gamepad counts as in use: GLFW reads it once per frame.
EM_JS(double, wpiguiLastInputMs, (), {
  if (navigator.getGamepads && Array.from(navigator.getGamepads()).some(Boolean)) {
    return performance.now();
  }
  return Module.frcLastInput ?? -Infinity;
});

namespace wpi {

// Minimum time between frames, see the main loop in wpigui.cpp.
double wpiguiMinFrameMs = 0;
// The same after a second without input.
double wpiguiIdleFrameMs = 0;

void gui::PlatformCreateContext() {}

void gui::PlatformDestroyContext() {}

void gui::PlatformGlfwInitHints() {}

void gui::PlatformGlfwWindowHints() {
  emscripten_glfw_set_next_window_canvas_selector("#canvas");
  glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_ES_API);
  glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
  glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
  // The workbench gives the tab focus. Focusing the canvas once loaded would take the
  // keyboard from wherever the user is typing by then.
  glfwWindowHint(GLFW_FOCUSED, GLFW_FALSE);
  glfwWindowHint(GLFW_FOCUS_ON_SHOW, GLFW_FALSE);
}

bool gui::PlatformInitRenderer() {
  glfwMakeContextCurrent(gContext->window);

  EM_ASM({
    const mark = () => { Module.frcLastInput = performance.now(); };
    for (const type of ['pointerdown', 'pointermove', 'wheel', 'keydown']) {
      window.addEventListener(type, mark, { capture: true, passive: true });
    }
  });

  // The canvas fills the page and follows its size and devicePixelRatio.
  emscripten_glfw_make_canvas_resizable(gContext->window, "window", nullptr);

  ImGui_ImplGlfw_InitForOpenGL(gContext->window, true);
  glfwSetCursorPosCallback(gContext->window, FitCursorPos);
  ImGui_ImplOpenGL3_Init("#version 300 es");

  gPlatformValid = true;
  return true;
}

void gui::PlatformRenderFrame() {
  if (gContext->reloadFonts) {
    ImGui_ImplOpenGL3_DestroyFontsTexture();
    ImGui_ImplOpenGL3_CreateFontsTexture();
    gContext->reloadFonts = false;
  }
  ImGui_ImplOpenGL3_NewFrame();

  CommonRenderFrame();

  int display_w, display_h;
  glfwGetFramebufferSize(gContext->window, &display_w, &display_h);
  glViewport(0, 0, display_w, display_h);
  glClearColor(gContext->clearColor.x, gContext->clearColor.y,
               gContext->clearColor.z, gContext->clearColor.w);
  glClear(GL_COLOR_BUFFER_BIT);
  ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());

  glfwSwapBuffers(gContext->window);
}

void gui::PlatformShutdown() {
  gPlatformValid = false;
  ImGui_ImplOpenGL3_Shutdown();
}

void gui::PlatformFramebufferSizeChanged(int width, int height) {}

void gui::PlatformFitDisplay() {
  ImGuiIO& io = ImGui::GetIO();
  gFitScale = 1;
  if (gContext->loadedWidthHeight && gContext->width > 0 && gContext->height > 0) {
    gFitScale = std::min({1.0f, io.DisplaySize.x / gContext->width,
                          io.DisplaySize.y / gContext->height});
  }
  io.DisplaySize.x /= gFitScale;
  io.DisplaySize.y /= gFitScale;
  io.DisplayFramebufferScale.x *= gFitScale;
  io.DisplayFramebufferScale.y *= gFitScale;
}

ImTextureID gui::CreateTexture(PixelFormat format, int width, int height,
                               const unsigned char* data) {
  if (!gPlatformValid) {
    return nullptr;
  }

  GLuint texture;
  glGenTextures(1, &texture);
  glBindTexture(GL_TEXTURE_2D, texture);

  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

  std::string swapped;
  data = ToRGBA(format, width, height, data, swapped);

  glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA,
               GL_UNSIGNED_BYTE, data);

  return reinterpret_cast<ImTextureID>(static_cast<uintptr_t>(texture));
}

void gui::UpdateTexture(ImTextureID texture, PixelFormat format, int width,
                        int height, const unsigned char* data) {
  GLuint glTexture = static_cast<GLuint>(reinterpret_cast<uintptr_t>(texture));
  if (glTexture == 0) {
    return;
  }
  std::string swapped;
  data = ToRGBA(format, width, height, data, swapped);
  glBindTexture(GL_TEXTURE_2D, glTexture);
  glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA,
                  GL_UNSIGNED_BYTE, data);
}

void gui::DeleteTexture(ImTextureID texture) {
  if (!gPlatformValid) {
    return;
  }
  GLuint glTexture = static_cast<GLuint>(reinterpret_cast<uintptr_t>(texture));
  if (glTexture != 0) {
    glDeleteTextures(1, &glTexture);
  }
}

void gui::OpenURL(const std::string& url) {
  emscripten_glfw_open_url(url.c_str(), "_blank");
}

}  // namespace wpi
