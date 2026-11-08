// Replaces hal/src/main/native/sim/MockHooks.cpp and the notifier queries of
// Notifier.cpp. Timing is the robot's, so pause, resume and step are forwarded
// to it, and only its pause state is mirrored here.

#include <stdint.h>

#include <atomic>

#include "HALInitializer.hpp"
#include "MockHooksInternal.hpp"
#include "wpi/hal/HAL.h"
#include "wpi/hal/simulation/MockHooks.h"
#include "wpi/hal/simulation/NotifierData.h"
#include "wpi/util/timestamp.hpp"

// Sends a sim.timing message to the robot, see simgui/Mirror.cpp.
void frc_sim_send_timing(const char* op, int64_t us);

static std::atomic<bool> programStarted{false};
static std::atomic<int64_t> programState{0};
static std::atomic<int64_t> programPauseTime{0};
static std::atomic<int64_t> programOffsetTime{0};

// One robot loop period, the Step button's stride.
static constexpr int64_t kStepNs = 20'000'000;

namespace wpi::hal::init {
void InitializeMockHooks() {}
void InitializeNotifier() {}
}  // namespace wpi::hal::init

namespace wpi::hal {
void RestartTiming() {}

void PauseTiming() {
  if (programPauseTime == 0) {
    programPauseTime = wpi::util::NowDefault();
  }
}

void ResumeTiming() {
  if (programPauseTime != 0) {
    programOffsetTime -= wpi::util::NowDefault() - programPauseTime;
    programPauseTime = 0;
  }
}

bool IsTimingPaused() {
  return programPauseTime != 0;
}

void StepTiming(int64_t delta) {
  programOffsetTime += delta;
}

int64_t GetMonotonicTime() {
  int64_t curTime = programPauseTime;
  if (curTime == 0) {
    curTime = wpi::util::NowDefault();
  }
  return curTime + programOffsetTime;
}

void SetProgramStarted() {
  programStarted = true;
}

bool GetProgramStarted() {
  return programStarted;
}
}  // namespace wpi::hal

using namespace wpi::hal;

extern "C" {
void HALSIM_WaitForProgramStart(HAL_Bool) {}

void HALSIM_SetProgramStarted(HAL_Bool started) {
  programStarted = started;
}

HAL_Bool HALSIM_GetProgramStarted(void) {
  return GetProgramStarted();
}

void HALSIM_SetProgramState(HAL_ControlWord controlWord) {
  programState = controlWord.value;
}

void HALSIM_GetProgramState(HAL_ControlWord* controlWord) {
  controlWord->value = programState;
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

void HALSIM_StepTiming(int64_t delta) {
  HALSIM_StepTimingAsync(delta);
}

// The page counts steps in microseconds.
void HALSIM_StepTimingAsync(int64_t delta) {
  StepTiming(delta);
  frc_sim_send_timing("step", delta / 1000);
}

// The robot's notifiers are not visible here.
int64_t HALSIM_GetNextNotifierTimeout(void) {
  return GetMonotonicTime() + kStepNs;
}

int32_t HALSIM_GetNumNotifiers(void) {
  return 0;
}

int32_t HALSIM_GetNotifierInfo(struct HALSIM_NotifierInfo* arr, int32_t size) {
  return 0;
}
}  // extern "C"
