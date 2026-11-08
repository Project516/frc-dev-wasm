// Entry point of the Sim GUI: halsim_gui, which desktop WPILib loads as a HAL
// extension into the robot process, runs here against a mirror of the robot's
// HAL state (Mirror.cpp) and connects to NetworkTables as a client.

#include "wpi/hal/HAL.h"
#include "wpi/hal/Main.h"
#include "wpi/nt/ntcore_cpp.hpp"

extern "C" int HALSIM_InitExtension(void);

// The robot JVM runs on the same thread as the GUI, so the GUI leaves it time.
namespace wpi {
extern double wpiguiMinFrameMs;
extern double wpiguiIdleFrameMs;
}  // namespace wpi

namespace simgui {
void StartMirror();
}

int main() {
  wpi::wpiguiMinFrameMs = 33;
  wpi::wpiguiIdleFrameMs = 100;
  HAL_Initialize();

  NT_Inst inst = wpi::nt::GetDefaultInstance();
  wpi::nt::StartClient(inst, "simgui");
  wpi::nt::SetServer(inst, "localhost", 0);

  HALSIM_InitExtension();
  simgui::StartMirror();

  // Runs the GUI main loop, which does not return in a browser.
  HAL_RunMain();
  return 0;
}
