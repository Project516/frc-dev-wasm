// A mirror of the robot program's HAL simulation state, for halsim_gui.
//
// The robot JVM stays the authority. Its halsim_ws messages
// (docs/bridge-protocol.md) are applied to the HAL sim data of this module, and
// changes made in the GUI go back as the input keys ('>' and '<>') of the same
// messages. The halsim_ws_core providers produce the outgoing messages and
// apply the incoming input keys. Output keys ('<'), which they leave to the
// peer, and SimDevices are applied here.
//
// A change that comes from the robot is never sent back: callbacks fire inside
// the HAL setters, so a flag set while a message is applied drops the echo.

#include <emscripten.h>
#include <emscripten/html5.h>
#include <stdint.h>

#include <algorithm>
#include <charconv>
#include <cstdlib>
#include <map>
#include <memory>
#include <optional>
#include <set>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include <hal/Ports.h>
#include <hal/SimDevice.h>
#include <hal/simulation/AccelerometerData.h>
#include <hal/simulation/AddressableLEDData.h>
#include <hal/simulation/AnalogInData.h>
#include <hal/simulation/AnalogOutData.h>
#include <hal/simulation/CTREPCMData.h>
#include <hal/simulation/DIOData.h>
#include <hal/simulation/DigitalPWMData.h>
#include <hal/simulation/DriverStationData.h>
#include <hal/simulation/EncoderData.h>
#include <hal/simulation/PWMData.h>
#include <hal/simulation/PowerDistributionData.h>
#include <hal/simulation/REVPHData.h>
#include <hal/simulation/RelayData.h>
#include <hal/simulation/SimDeviceData.h>
#include <ntcore_cpp.h>
#include <wpi/StringExtras.h>
#include <wpi/json.h>

#include "WSBaseProvider.h"
#include "WSProviderContainer.h"
#include "WSProvider_AddressableLED.h"
#include "WSProvider_Analog.h"
#include "WSProvider_BuiltInAccelerometer.h"
#include "WSProvider_DIO.h"
#include "WSProvider_DriverStation.h"
#include "WSProvider_Encoder.h"
#include "WSProvider_Joystick.h"
#include "WSProvider_PCM.h"
#include "WSProvider_PWM.h"
#include "WSProvider_Relay.h"
#include "WSProvider_RoboRIO.h"
#include "WSProvider_Solenoid.h"
#include "WSProvider_dPWM.h"

namespace simgui {
std::shared_ptr<wpilibws::HALSimWSBaseProvider> MakeExtraProvider(const std::string& type, const std::string& device);
}

namespace hal {
void PauseTiming();
void ResumeTiming();
}  // namespace hal

// The page side is the SimHost the IDE window offers, see
// packages/runtime/src/sim-host.ts. Without one the GUI runs on its own.
EM_JS(void, frc_sim_connect, (), {
  let host;
  try {
    host = window.parent !== window ? window.parent.frcDevWasmSim : undefined;
  } catch {
  }
  Module.frcSim = { host };
  if (!host) return;
  const status = (s) => (s === 'running' ? 2 : s === 'starting' ? 1 : 0);
  const receive = (messages) => {
    if (messages.length) ccall('frc_sim_receive', null, ['string'], [JSON.stringify(messages)]);
  };
  receive(host.snapshot());
  ccall('frc_sim_status', null, ['number'], [status(host.status())]);
  host.listen(receive);
  host.onStatus((s) => ccall('frc_sim_status', null, ['number'], [status(s)]));
});

EM_JS(void, frc_sim_post, (const char* json), {
  const host = Module.frcSim && Module.frcSim.host;
  if (!host) return;
  for (const message of JSON.parse(UTF8ToString(json))) host.send(message);
});

namespace {

using wpi::json;

struct Message {
  std::string type;
  std::string device;
  json data;
};

class Connection final : public wpilibws::HALSimBaseWebSocketConnection {
 public:
  void OnSimValueChanged(const json& msg) override;
};

struct SimValue {
  HAL_SimValueHandle handle;
  std::string device;  // message type and id, as in the robot's messages
  std::string type;
  std::string key;
  bool send;
};

struct SimDevice {
  HAL_SimDeviceHandle handle;
  std::map<std::string, std::unique_ptr<SimValue>> values;
};

wpilibws::ProviderContainer gProviders;
std::shared_ptr<Connection> gConnection;
bool gApplying = false;
bool gReady = false;
std::vector<std::string> gPending;
// Devices the robot initialized. Inputs of other devices are not sent.
std::set<std::string> gKnown;
// Last value sent per key, so repeated values stay quiet.
std::map<std::string, json> gSent;
std::vector<Message> gOut;
// A new data announcement makes the robot JVM refresh its driver station data,
// which a browser JVM feels. Announce when something changed, and now and then
// so the robot sees the driver station is alive.
bool gChanged = false;
double gLastAnnounce = 0;
constexpr double kHeartbeatMs = 250;
bool gFlushScheduled = false;
std::map<std::string, SimDevice> gSimDevices;

// What the GUI had set when the robot started, see RestoreDriverStation.
struct DriverStationState {
  bool enabled;
  bool autonomous;
  bool test;
  bool estop;
  bool fms;
  bool ds;
  HAL_AllianceStationID station;
  double matchTime;
};
std::optional<DriverStationState> gDriverStation;

// No initialization, the page can always have them.
bool AlwaysSent(const std::string& type) {
  return type == "DriverStation" || type == "Joystick" || type == "RoboRIO";
}

std::string DeviceKey(std::string_view type, std::string_view device) {
  return std::string{type} + "/" + std::string{device};
}

bool IsInput(std::string_view key) {
  return key.starts_with('>') || key.starts_with("<>");
}

void Flush() {
  gFlushScheduled = false;
  if (gOut.empty()) {
    return;
  }
  // Joystick values must reach the robot before the DriverStation message that
  // announces new data.
  std::stable_partition(gOut.begin(), gOut.end(), [](const Message& m) { return m.type != "DriverStation"; });
  json messages = json::array();
  for (auto& m : gOut) {
    messages.push_back({{"type", m.type}, {"device", m.device}, {"data", std::move(m.data)}});
  }
  gOut.clear();
  frc_sim_post(messages.dump().c_str());
}

void Queue(std::string type, std::string device, json data) {
  // Timing operations are a sequence, not state.
  auto it = std::find_if(gOut.begin(), gOut.end(), [&](const Message& m) { return m.type == type && m.device == device; });
  if (it != gOut.end() && type != "sim.timing") {
    for (auto&& [key, value] : data.items()) {
      it->data[key] = value;
    }
  } else {
    gOut.push_back({std::move(type), std::move(device), std::move(data)});
  }
  if (!gFlushScheduled) {
    gFlushScheduled = true;
    emscripten_set_timeout([](void*) { Flush(); }, 0, nullptr);
  }
}

void Send(const std::string& type, const std::string& device, const std::string& key, const json& value) {
  auto base = DeviceKey(type, device);
  if (!AlwaysSent(type) && !gKnown.contains(base)) {
    return;
  }
  if (key == ">new_data") {
    double now = emscripten_get_now();
    if (!gChanged && now - gLastAnnounce < kHeartbeatMs) {
      return;
    }
    gChanged = false;
    gLastAnnounce = now;
  } else {
    auto [it, added] = gSent.try_emplace(base + "/" + key, value);
    if (!added && it->second == value) {
      return;
    }
    it->second = value;
    gChanged = true;
  }
  if (type == "DriverStation" && key != ">new_data") {
    gDriverStation.reset();  // the GUI changed it after the robot started
  }
  Queue(type, device, {{key, value}});
}

void Connection::OnSimValueChanged(const json& msg) {
  if (gApplying) {
    return;
  }
  auto& type = msg["type"].get_ref<const std::string&>();
  auto& device = msg["device"].get_ref<const std::string&>();
  for (auto&& [key, value] : msg["data"].items()) {
    if (IsInput(key)) {
      Send(type, device, key, value);
    }
  }
}

// A JSON member, if present and of the right type.
template <typename T>
bool Read(const json& data, const char* key, T& value) {
  auto it = data.find(key);
  if (it == data.end() || it->is_null()) {
    return false;
  }
  value = it->template get<T>();
  return true;
}

int ParseIndex(std::string_view text) {
  int value = -1;
  std::from_chars(text.data(), text.data() + text.size(), value);
  return value;
}

#define ON(T, key, call)    \
  if (T v; Read(d, key, v)) \
  call

// The '<' keys of a message: what the robot reports about its devices.
void ApplyOutputs(const std::string& type, const std::string& device, const json& d) {
  int ch = ParseIndex(device);
  if (type == "PWM") {
    ON(bool, "<init", HALSIM_SetPWMInitialized(ch, v));
    ON(double, "<speed", HALSIM_SetPWMSpeed(ch, v));
    ON(double, "<position", HALSIM_SetPWMPosition(ch, v));
    ON(int32_t, "<raw", HALSIM_SetPWMPulseMicrosecond(ch, v));
    ON(int32_t, "<period_scale", HALSIM_SetPWMPeriodScale(ch, v));
    ON(bool, "<zero_latch", HALSIM_SetPWMZeroLatch(ch, v));
  } else if (type == "DIO") {
    ON(bool, "<init", HALSIM_SetDIOInitialized(ch, v));
    ON(double, "<pulse_length", HALSIM_SetDIOPulseLength(ch, v));
    ON(bool, "<input", HALSIM_SetDIOIsInput(ch, v));
  } else if (type == "dPWM") {
    ON(bool, "<init", HALSIM_SetDigitalPWMInitialized(ch, v));
    ON(double, "<duty_cycle", HALSIM_SetDigitalPWMDutyCycle(ch, v));
    ON(int32_t, "<dio_pin", HALSIM_SetDigitalPWMPin(ch, v));
  } else if (type == "AI") {
    ON(bool, "<init", HALSIM_SetAnalogInInitialized(ch, v));
    ON(int32_t, "<avg_bits", HALSIM_SetAnalogInAverageBits(ch, v));
    ON(int32_t, "<oversample_bits", HALSIM_SetAnalogInOversampleBits(ch, v));
    ON(bool, "<accum_init", HALSIM_SetAnalogInAccumulatorInitialized(ch, v));
    ON(int32_t, "<accum_center", HALSIM_SetAnalogInAccumulatorCenter(ch, v));
    ON(int32_t, "<accum_deadband", HALSIM_SetAnalogInAccumulatorDeadband(ch, v));
  } else if (type == "AO") {
    ON(bool, "<init", HALSIM_SetAnalogOutInitialized(ch, v));
    ON(double, "<voltage", HALSIM_SetAnalogOutVoltage(ch, v));
  } else if (type == "Encoder") {
    ON(bool, "<init", HALSIM_SetEncoderInitialized(ch, v));
    ON(bool, "<reverse_direction", HALSIM_SetEncoderReverseDirection(ch, v));
    ON(int32_t, "<samples_to_avg", HALSIM_SetEncoderSamplesToAverage(ch, v));
  } else if (type == "Relay") {
    ON(bool, "<init_fwd", HALSIM_SetRelayInitializedForward(ch, v));
    ON(bool, "<init_rev", HALSIM_SetRelayInitializedReverse(ch, v));
    ON(bool, "<fwd", HALSIM_SetRelayForward(ch, v));
    ON(bool, "<rev", HALSIM_SetRelayReverse(ch, v));
  } else if (type == "CTREPCM") {
    ON(bool, "<init", HALSIM_SetCTREPCMInitialized(ch, v));
    ON(bool, "<closed_loop", HALSIM_SetCTREPCMClosedLoopEnabled(ch, v));
  } else if (type == "Solenoid") {
    auto [mod, chan] = wpi::split(device, ',');
    ON(bool, "<output", HALSIM_SetCTREPCMSolenoidOutput(ParseIndex(mod), ParseIndex(chan), v));
  } else if (type == "PowerDistribution") {
    ON(bool, "<init", HALSIM_SetPowerDistributionInitialized(ch, v));
  } else if (type == "REVPH") {
    ON(bool, "<init", HALSIM_SetREVPHInitialized(ch, v));
    ON(int32_t, "<config", HALSIM_SetREVPHCompressorConfigType(ch, static_cast<HAL_REVPHCompressorConfigType>(v)));
  } else if (type == "REVPHSolenoid") {
    auto [mod, chan] = wpi::split(device, ',');
    ON(bool, "<output", HALSIM_SetREVPHSolenoidOutput(ParseIndex(mod), ParseIndex(chan), v));
  } else if (type == "Accel") {
    ON(bool, "<init", HALSIM_SetAccelerometerActive(0, v));
    ON(double, "<range", HALSIM_SetAccelerometerRange(0, v <= 2 ? HAL_AccelerometerRange_k2G : v <= 4 ? HAL_AccelerometerRange_k4G : HAL_AccelerometerRange_k8G));
  } else if (type == "AddressableLED") {
    ON(bool, "<init", HALSIM_SetAddressableLEDInitialized(ch, v));
    ON(int32_t, "<output_port", HALSIM_SetAddressableLEDOutputPort(ch, v));
    ON(int32_t, "<length", HALSIM_SetAddressableLEDLength(ch, v));
    ON(bool, "<running", HALSIM_SetAddressableLEDRunning(ch, v));
    if (auto it = d.find("<data"); it != d.end() && it->is_array()) {
      std::vector<HAL_AddressableLEDData> leds;
      for (auto&& led : *it) {
        HAL_AddressableLEDData out{};
        out.b = led.value("b", 0);
        out.g = led.value("g", 0);
        out.r = led.value("r", 0);
        leds.push_back(out);
      }
      HALSIM_SetAddressableLEDData(ch, leds.data(), leds.size());
    }
  } else if (type == "Joystick") {
    int64_t outputs = 0;
    int32_t left = 0;
    int32_t right = 0;
    HALSIM_GetJoystickOutputs(ch, &outputs, &left, &right);
    Read(d, "<outputs", outputs);
    Read(d, "<rumble_left", left);
    Read(d, "<rumble_right", right);
    HALSIM_SetJoystickOutputs(ch, outputs, left, right);
  }
}

// SimDevices have no provider here: the robot's devices are created on first
// sight. Numbers become doubles, because the type is lost in JSON.
void ApplySimDevice(const std::string& type, const std::string& device, const json& data) {
  auto name = type == "SimDevice" ? device : type + ":" + device;
  auto& dev = gSimDevices[name];
  if (dev.handle == 0) {
    dev.handle = HAL_CreateSimDevice(name.c_str());
    if (dev.handle == 0) {
      gSimDevices.erase(name);
      return;
    }
  }
  for (auto&& [key, value] : data.items()) {
    HAL_Value hv;
    if (value.is_boolean()) {
      hv = HAL_MakeBoolean(value.get<bool>());
    } else if (value.is_number()) {
      hv = HAL_MakeDouble(value.get<double>());
    } else {
      continue;
    }
    auto it = dev.values.find(key);
    if (it == dev.values.end()) {
      int direction = HAL_SimValueOutput;
      std::string_view valueName = key;
      if (valueName.starts_with("<>")) {
        direction = HAL_SimValueBidir;
        valueName.remove_prefix(2);
      } else if (valueName.starts_with('>')) {
        direction = HAL_SimValueInput;
        valueName.remove_prefix(1);
      } else if (valueName.starts_with('<')) {
        valueName.remove_prefix(1);
      }
      auto handle = HAL_CreateSimValue(dev.handle, std::string{valueName}.c_str(), direction, &hv);
      if (handle == 0) {
        continue;
      }
      auto entry = std::make_unique<SimValue>(SimValue{handle, device, type, key, direction != HAL_SimValueOutput});
      HALSIM_RegisterSimValueChangedCallback(
          handle, entry.get(),
          [](const char*, void* param, HAL_SimValueHandle, int32_t, const HAL_Value* value) {
            auto* v = static_cast<SimValue*>(param);
            if (gApplying || !v->send) {
              return;
            }
            if (value->type == HAL_BOOLEAN) {
              Send(v->type, v->device, v->key, static_cast<bool>(value->data.v_boolean));
            } else if (value->type == HAL_DOUBLE) {
              Send(v->type, v->device, v->key, value->data.v_double);
            }
          },
          false);
      it = dev.values.emplace(key, std::move(entry)).first;
    } else {
      HAL_SetSimValue(it->second->handle, &hv);
    }
  }
  gKnown.insert(DeviceKey(type, device));
}

bool IsRobotDevice(const std::string& type) {
  return !type.starts_with("nt.") && type != "console" && type != "exit" && type != "hello" && type != "stop";
}

void ApplyMessage(const json& msg) {
  auto& type = msg.at("type").get_ref<const std::string&>();
  auto& device = msg.at("device").get_ref<const std::string&>();
  auto& data = msg.at("data");
  if (type == "sim.timing") {
    if (bool paused; Read(data, "paused", paused)) {
      paused ? hal::PauseTiming() : hal::ResumeTiming();
    }
    return;
  }
  if (!IsRobotDevice(type)) {
    return;
  }
  gApplying = true;
  auto base = device.empty() ? type : type + "/" + device;
  auto provider = gProviders.Get(base);
  if (!provider && (provider = simgui::MakeExtraProvider(type, device))) {
    gProviders.Add(base, provider);
    provider->OnNetworkConnected(gConnection);
  }
  if (provider) {
    if (!AlwaysSent(type)) {
      gKnown.insert(DeviceKey(type, device));
    }
    provider->OnNetValueChanged(data);
    // The robot's value is the GUI's value now, so setting the earlier one again is sent.
    for (auto&& [key, value] : data.items()) {
      if (IsInput(key)) {
        gSent[DeviceKey(type, device) + "/" + key] = value;
      }
    }
    ApplyOutputs(type, device, data);
  } else {
    ApplySimDevice(type, device, data);
  }
  gApplying = false;
}

// The driver station belongs to the GUI. A starting robot resets its own
// state and reports that in its first messages, which would overwrite the
// GUI's. The robot gets the GUI's state from the resend, so put it back here.
void RestoreDriverStation() {
  if (!gDriverStation) {
    return;
  }
  auto& d = *gDriverStation;
  gApplying = true;
  HALSIM_SetDriverStationEnabled(d.enabled);
  HALSIM_SetDriverStationAutonomous(d.autonomous);
  HALSIM_SetDriverStationTest(d.test);
  HALSIM_SetDriverStationEStop(d.estop);
  HALSIM_SetDriverStationFmsAttached(d.fms);
  HALSIM_SetDriverStationDsAttached(d.ds);
  HALSIM_SetDriverStationAllianceStationId(d.station);
  HALSIM_SetDriverStationMatchTime(d.matchTime);
  gApplying = false;
  gDriverStation.reset();
}

void Receive(const std::string& text) {
  bool driverStation = false;
  try {
    for (auto&& msg : json::parse(text)) {
      driverStation |= msg.value("type", "") == "DriverStation";
      ApplyMessage(msg);
    }
  } catch (const json::exception& e) {
    gApplying = false;
    emscripten_console_warn(e.what());
  }
  if (driverStation) {
    RestoreDriverStation();
  }
}

// The robot is gone: its devices go away with it.
void Reset() {
  gApplying = true;
  const json uninit = {{"<init", false}, {"<init_fwd", false}, {"<init_rev", false}, {"<accum_init", false}};
  for (auto& key : gKnown) {
    auto slash = key.find('/');
    auto type = key.substr(0, slash);
    auto device = key.substr(slash + 1);
    if (gProviders.Get(device.empty() ? type : key)) {
      ApplyOutputs(type, device, uninit);
    }
  }
  for (auto& [name, dev] : gSimDevices) {
    HAL_FreeSimDevice(dev.handle);
  }
  gSimDevices.clear();
  gKnown.clear();
  gSent.clear();
  gApplying = false;
}

// A new robot has no inputs yet: re-registering the providers notifies the
// current value of every key, as halsim_ws does for a new connection.
void Resend() {
  gSent.clear();
  gProviders.ForEach([](auto provider) {
    provider->OnNetworkDisconnected();
    provider->OnNetworkConnected(gConnection);
  });
  for (auto& [name, dev] : gSimDevices) {
    for (auto& [key, value] : dev.values) {
      HAL_Value hv;
      HAL_GetSimValue(value->handle, &hv);
      if (value->send && hv.type == HAL_BOOLEAN) {
        Send(value->type, value->device, key, static_cast<bool>(hv.data.v_boolean));
      } else if (value->send && hv.type == HAL_DOUBLE) {
        Send(value->type, value->device, key, hv.data.v_double);
      }
    }
  }
}

void Register(std::string_view key, std::shared_ptr<wpilibws::HALSimWSBaseProvider> provider) {
  gProviders.Add(key, std::move(provider));
}

}  // namespace

void frc_sim_send_timing(const char* op, int64_t us) {
  Queue("sim.timing", "", {{"op", op}, {"us", us}});
}

namespace simgui {

// Registers the providers and connects to the page. Call after HAL_Initialize.
void StartMirror() {
  gConnection = std::make_shared<Connection>();
  using namespace wpilibws;
  HALSimWSProviderAddressableLED::Initialize(Register);
  HALSimWSProviderAnalogIn::Initialize(Register);
  HALSimWSProviderAnalogOut::Initialize(Register);
  HALSimWSProviderBuiltInAccelerometer::Initialize(Register);
  HALSimWSProviderDIO::Initialize(Register);
  HALSimWSProviderDigitalPWM::Initialize(Register);
  HALSimWSProviderDriverStation::Initialize(Register);
  HALSimWSProviderEncoder::Initialize(Register);
  HALSimWSProviderJoystick::Initialize(Register);
  HALSimWSProviderPCM::Initialize(Register);
  HALSimWSProviderPWM::Initialize(Register);
  HALSimWSProviderRelay::Initialize(Register);
  HALSimWSProviderRoboRIO::Initialize(Register);
  HALSimWSProviderSolenoid::Initialize(Register);

  gApplying = true;
  gProviders.ForEach([](auto provider) { provider->OnNetworkConnected(gConnection); });
  gApplying = false;

  gReady = true;
  for (auto& text : gPending) {
    Receive(text);
  }
  gPending.clear();
  frc_sim_connect();
}

}  // namespace simgui

extern "C" {

// A JSON array of robot messages.
EMSCRIPTEN_KEEPALIVE void frc_sim_receive(const char* text) {
  if (gReady) {
    Receive(text);
  } else {
    gPending.emplace_back(text);
  }
}

// 0 stopped, 1 starting, 2 running.
EMSCRIPTEN_KEEPALIVE void frc_sim_status(int status) {
  if (!gReady) {
    return;
  }
  if (status == 0) {
    Reset();
    hal::ResumeTiming();
  } else if (status == 2) {
    Resend();
    gDriverStation = DriverStationState{static_cast<bool>(HALSIM_GetDriverStationEnabled()),
                                        static_cast<bool>(HALSIM_GetDriverStationAutonomous()),
                                        static_cast<bool>(HALSIM_GetDriverStationTest()),
                                        static_cast<bool>(HALSIM_GetDriverStationEStop()),
                                        static_cast<bool>(HALSIM_GetDriverStationFmsAttached()),
                                        static_cast<bool>(HALSIM_GetDriverStationDsAttached()),
                                        HALSIM_GetDriverStationAllianceStationId(),
                                        HALSIM_GetDriverStationMatchTime()};
  }
}

// State the tests look at.
EMSCRIPTEN_KEEPALIVE const char* frc_sim_state() {
  static std::string out;
  json state = {{"ds", static_cast<bool>(HALSIM_GetDriverStationDsAttached())},
                {"enabled", static_cast<bool>(HALSIM_GetDriverStationEnabled())},
                {"autonomous", static_cast<bool>(HALSIM_GetDriverStationAutonomous())},
                {"test", static_cast<bool>(HALSIM_GetDriverStationTest())},
                {"known", gKnown},
                {"settingsDir", std::getenv("SIMGUI_DIR") ? std::getenv("SIMGUI_DIR") : ""},
                {"nt", nt::IsConnected(nt::GetDefaultInstance())},
                {"pwm", json::object()}};
  HAL_JoystickAxes axes{};
  HALSIM_GetJoystickAxes(0, &axes);
  state["axes0"] = std::vector<float>(axes.axes, axes.axes + axes.count);
  for (int i = 0; i < HAL_GetNumPWMChannels(); i++) {
    if (HALSIM_GetPWMInitialized(i)) {
      state["pwm"][std::to_string(i)] = HALSIM_GetPWMSpeed(i);
    }
  }
  out = state.dump();
  return out.c_str();
}

}  // extern "C"
