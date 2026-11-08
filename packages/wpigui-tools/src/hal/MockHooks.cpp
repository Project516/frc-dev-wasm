// Replaces hal/src/main/native/sim/MockHooks.cpp and the notifier queries of
// Notifier.cpp. Timing is the robot's, so pause, resume and step are forwarded
// to it, and only its pause state is mirrored here.

#include <stdint.h>

#include <atomic>

#include <wpi/timestamp.h>

#include "HALInitializer.h"
#include "MockHooksInternal.h"
#include "hal/HALBase.h"
#include "hal/simulation/NotifierData.h"

// Sends a sim.timing message to the robot, see simgui/Mirror.cpp.
void frc_sim_send_timing(const char* op, int64_t us);

static std::atomic<bool> programStarted{false};
static std::atomic<uint64_t> programStartTime{0};
static std::atomic<uint64_t> programPauseTime{0};
static std::atomic<uint64_t> programStepTime{0};

// One robot loop period, the Step button's stride.
static constexpr uint64_t kStepUs = 20000;

namespace hal::init {
void InitializeMockHooks() {}
void InitializeNotifier() {}
}  // namespace hal::init

namespace hal {
void RestartTiming() {
  programStartTime = wpi::NowDefault();
  programStepTime = 0;
  if (programPauseTime != 0) {
    programPauseTime = programStartTime.load();
  }
}

void PauseTiming() {
  if (programPauseTime == 0) {
    programPauseTime = wpi::NowDefault();
  }
}

void ResumeTiming() {
  if (programPauseTime != 0) {
    programStartTime += wpi::NowDefault() - programPauseTime;
    programPauseTime = 0;
  }
}

bool IsTimingPaused() {
  return programPauseTime != 0;
}

void StepTiming(uint64_t delta) {
  programStepTime += delta;
}

uint64_t GetFPGATime() {
  uint64_t curTime = programPauseTime;
  if (curTime == 0) {
    curTime = wpi::NowDefault();
  }
  return curTime + programStepTime - programStartTime;
}

double GetFPGATimestamp() {
  return GetFPGATime() * 1.0e-6;
}

void SetProgramStarted() {
  programStarted = true;
}

bool GetProgramStarted() {
  return programStarted;
}
}  // namespace hal

using namespace hal;

extern "C" {
void HALSIM_WaitForProgramStart(void) {}

void HALSIM_SetProgramStarted(void) {
  SetProgramStarted();
}

HAL_Bool HALSIM_GetProgramStarted(void) {
  return GetProgramStarted();
}

void HALSIM_RestartTiming(void) {
  RestartTiming();
}

void HALSIM_PauseTiming(void) {
  PauseTiming();
  frc_sim_send_timing("pause", 0);
}

void HALSIM_ResumeTiming(void) {
  ResumeTiming();
  frc_sim_send_timing("resume", 0);
}

HAL_Bool HALSIM_IsTimingPaused(void) {
  return IsTimingPaused();
}

void HALSIM_StepTiming(uint64_t delta) {
  HALSIM_StepTimingAsync(delta);
}

void HALSIM_StepTimingAsync(uint64_t delta) {
  StepTiming(delta);
  frc_sim_send_timing("step", static_cast<int64_t>(delta));
}

// The robot's notifiers are not visible here.
uint64_t HALSIM_GetNextNotifierTimeout(void) {
  return GetFPGATime() + kStepUs;
}

int32_t HALSIM_GetNumNotifiers(void) {
  return 0;
}

int32_t HALSIM_GetNotifierInfo(struct HALSIM_NotifierInfo* arr, int32_t size) {
  return 0;
}
}  // extern "C"
