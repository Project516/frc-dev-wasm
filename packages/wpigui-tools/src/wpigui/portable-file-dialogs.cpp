// portable-file-dialogs for browsers. A request goes to the hosting page, which
// shows a file dialog over the workspace and copies the chosen files into MEMFS
// (see shell/files.js). Only the file dialogs do anything.

#include <emscripten.h>

#include <cstdint>
#include <cstdlib>
#include <string>
#include <utility>

#include "portable-file-dialogs.h"

EM_JS(int, FrcPickStart,
      (const char* kind, const char* title, const char* path,
       const char* filters, int multiple),
      {
        return Module.frcFiles.start(UTF8ToString(kind), UTF8ToString(title),
                                     UTF8ToString(path), UTF8ToString(filters),
                                     multiple != 0);
      });
EM_JS(int, FrcPickDone, (int id), { return Module.frcFiles.done(id) ? 1 : 0; });
EM_JS(char*, FrcPickTake, (int id),
      { return stringToNewUTF8(Module.frcFiles.take(id)); });
EM_JS(void, FrcPickCancel, (int id), { Module.frcFiles.cancel(id); });

namespace pfd {

namespace internal {

class executor {
 public:
  int id = -1;
  bool finished = false;
  std::string out;
};

}  // namespace internal

settings::settings(bool) {}
bool settings::available() { return true; }
void settings::verbose(bool) {}
void settings::rescan() {}
bool settings::check_program(std::string const&) { return false; }

bool const& settings::flags(flag in_flag) const {
  static bool flags[static_cast<int>(flag::max_flag)] = {};
  return flags[static_cast<int>(in_flag)];
}

bool& settings::flags(flag in_flag) {
  return const_cast<bool&>(std::as_const(*this).flags(in_flag));
}

internal::dialog::dialog() : m_async(std::make_shared<executor>()) {}

internal::dialog::~dialog() {
  kill();
}

bool internal::dialog::ready(int) const {
  auto& e = *m_async;
  if (!e.finished && e.id >= 0 && FrcPickDone(e.id)) {
    char* text = FrcPickTake(e.id);
    e.out = text;
    std::free(text);
    e.finished = true;
  }
  return e.finished;
}

bool internal::dialog::kill() const {
  auto& e = *m_async;
  if (e.finished) {
    return false;
  }
  if (e.id >= 0) {
    FrcPickCancel(e.id);
  }
  e.finished = true;
  return true;
}

class internal::file_dialog::Impl {};

internal::file_dialog::file_dialog(type in_type, std::string const& title,
                                   std::string const& default_path,
                                   std::vector<std::string> const& filters,
                                   opt options)
    : m_impl(std::make_shared<Impl>()) {
  static const char* const kinds[] = {"open", "save", "folder"};
  std::string joined;
  for (auto& filter : filters) {
    joined += filter;
    joined += '\x1f';
  }
  m_async->id =
      FrcPickStart(kinds[in_type], title.c_str(), default_path.c_str(),
                   joined.c_str(), (options & opt::multiselect) ? 1 : 0);
}

std::string internal::file_dialog::string_result() {
  ready(0);
  auto result = m_async->out;
  auto end = result.find('\n');
  return end == std::string::npos ? result : result.substr(0, end);
}

std::vector<std::string> internal::file_dialog::vector_result() {
  ready(0);
  std::vector<std::string> paths;
  size_t start = 0;
  for (size_t end; (end = m_async->out.find('\n', start)) != std::string::npos;
       start = end + 1) {
    paths.push_back(m_async->out.substr(start, end - start));
  }
  return paths;
}

notify::notify(std::string const&, std::string const&, icon) {
  m_async->finished = true;
}

message::message(std::string const&, std::string const&, choice, icon) {
  m_async->finished = true;
}

button message::result() {
  return button::cancel;
}

}  // namespace pfd
