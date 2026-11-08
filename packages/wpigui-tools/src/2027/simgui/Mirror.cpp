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
#include <bit>
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

#include "mockdata/EncoderDataInternal.hpp"
#include "wpi/hal/DriverStation.h"
#include "wpi/hal/Ports.h"
#include "wpi/hal/SimDevice.h"
#include "wpi/hal/simulation/AddressableLEDData.h"
#include "wpi/hal/simulation/AnalogInData.h"
#include "wpi/hal/simulation/CTREPCMData.h"
#include "wpi/hal/simulation/DIOData.h"
#include "wpi/hal/simulation/DigitalPWMData.h"
#include "wpi/hal/simulation/DriverStationData.h"
#include "wpi/hal/simulation/EncoderData.h"
#include "wpi/hal/simulation/MockHooks.h"
#include "wpi/hal/simulation/PWMData.h"
#include "wpi/hal/simulation/PowerDistributionData.h"
#include "wpi/hal/simulation/REVPHData.h"
#include "wpi/hal/simulation/SimDeviceData.h"
#include "wpi/halsim/ws_core/WSBaseProvider.hpp"
#include "wpi/halsim/ws_core/WSProviderContainer.hpp"
#include "wpi/halsim/ws_core/WSProvider_AddressableLED.hpp"
#include "wpi/halsim/ws_core/WSProvider_Analog.hpp"
#include "wpi/halsim/ws_core/WSProvider_DIO.hpp"
#include "wpi/halsim/ws_core/WSProvider_DriverStation.hpp"
#include "wpi/halsim/ws_core/WSProvider_Encoder.hpp"
#include "wpi/halsim/ws_core/WSProvider_Joystick.hpp"
#include "wpi/halsim/ws_core/WSProvider_PCM.hpp"
#include "wpi/halsim/ws_core/WSProvider_PWM.hpp"
#include "wpi/halsim/ws_core/WSProvider_RoboRIO.hpp"
#include "wpi/halsim/ws_core/WSProvider_Solenoid.hpp"
#include "wpi/halsim/ws_core/WSProvider_dPWM.hpp"
#include "wpi/nt/ntcore_cpp.hpp"
#include "wpi/util/StringExtras.hpp"
#include "wpi/util/json.hpp"
#include "wpi/util/string.hpp"

namespace simgui {
std::shared_ptr<wpilibws::HALSimWSBaseProvider> MakeExtraProvider(const std::string& type, const std::string& device);
}

namespace wpi::hal {
void PauseTiming();
void ResumeTiming();
}  // namespace wpi::hal

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

using json = wpi::util::json;

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
  HAL_RobotMode robotMode;
  bool estop;
  bool fms;
  bool ds;
  HAL_AllianceStationID station;
  double matchTime;
  int64_t opMode;
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
    messages.emplace_back(json::object("type", m.type, "device", m.device, "data", std::move(m.data)));
  }
  gOut.clear();
  frc_sim_post(messages.to_string().c_str());
}

void Queue(std::string type, std::string device, std::string_view key, json value) {
  // Timing operations are a sequence, not state.
  auto it = std::find_if(gOut.begin(), gOut.end(), [&](const Message& m) { return m.type == type && m.device == device; });
  if (it != gOut.end() && type != "sim.timing") {
    it->data[key] = std::move(value);
  } else {
    auto data = json::object();
    data[key] = std::move(value);
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
  Queue(type, device, key, value);
}

void Connection::OnSimValueChanged(const json& msg) {
  if (gApplying) {
    return;
  }
  auto& type = msg.at("type").get_string();
  auto& device = msg.at("device").get_string();
  for (auto&& [key, value] : msg.at("data").get_object()) {
    if (IsInput(key)) {
      Send(type, device, key, value);
    }
  }
}

// A JSON member, if present and of the right type. Numbers of any JSON type
// read as any number type.
bool Read(const json& data, const char* key, bool& value) {
  auto* v = data.lookup(key);
  if (!v || !v->is_bool()) {
    return false;
  }
  value = v->get_bool();
  return true;
}

template <typename T>
  requires(std::is_arithmetic_v<T> && !std::is_same_v<T, bool>)
bool Read(const json& data, const char* key, T& value) {
  auto* v = data.lookup(key);
  if (!v || !v->is_number()) {
    return false;
  }
  value = static_cast<T>(v->get_number());
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

// Op mode ids and the program's control word are decimal strings: they go past
// 2^53, which JSON numbers cannot hold.
bool ReadInt64(const json& data, const char* key, int64_t& value) {
  auto* v = data.lookup(key);
  if (!v || !v->is_string()) {
    return false;
  }
  auto& text = v->get_string();
  auto [end, error] = std::from_chars(text.data(), text.data() + text.size(), value);
  return error == std::errc{} && end == text.data() + text.size();
}

std::string ReadString(const json& data, const char* key) {
  auto* v = data.lookup(key);
  return v && v->is_string() ? v->get_string() : std::string{};
}

void SetOpModeOptions(const json& list) {
  struct Option {
    int64_t id = 0;
    std::string name;
    std::string group;
    std::string description;
    int32_t textColor = -1;
    int32_t backgroundColor = -1;
  };
  std::vector<Option> options;
  for (auto&& o : list.get_array()) {
    Option option;
    if (o.is_object() && ReadInt64(o, "id", option.id)) {
      option.name = ReadString(o, "name");
      option.group = ReadString(o, "group");
      option.description = ReadString(o, "description");
      Read(o, "textColor", option.textColor);
      Read(o, "backgroundColor", option.backgroundColor);
      options.push_back(std::move(option));
    }
  }
  // The strings stay in options for the call. The HAL copies them.
  std::vector<HAL_OpModeOption> hal;
  for (auto& o : options) {
    hal.push_back({o.id, wpi::util::make_string(o.name), wpi::util::make_string(o.group),
                   wpi::util::make_string(o.description), o.textColor, o.backgroundColor});
  }
  HAL_SetOpModeOptions(hal.data(), static_cast<int32_t>(hal.size()));
}

// The keys of the DriverStation message that halsim_ws does not have, see
// docs/bridge-protocol.md.
void ApplyOpModes(const json& d) {
  if (int64_t id; ReadInt64(d, ">opmode", id)) {
    HALSIM_SetDriverStationOpMode(id);
  }
  if (auto* options = d.lookup("<opmode_options"); options && options->is_array()) {
    SetOpModeOptions(*options);
  }
  if (int64_t word; ReadInt64(d, "<program_state", word)) {
    HALSIM_SetProgramState(HAL_ControlWord{word});
  }
}

// The LEDs of an output's range, as the robot sends them.
void ApplyLedData(int ch, const json& d) {
  auto* data = d.lookup("<data");
  if (!data || !data->is_array()) {
    return;
  }
  std::vector<HAL_AddressableLEDData> leds;
  for (auto&& led : data->get_array()) {
    HAL_AddressableLEDData out{};
    if (led.is_object()) {
      Read(led, "r", out.r);
      Read(led, "g", out.g);
      Read(led, "b", out.b);
    }
    leds.push_back(out);
  }
  HALSIM_SetAddressableLEDData(HALSIM_GetAddressableLEDStart(ch), static_cast<int32_t>(leds.size()), leds.data());
}

// The '<' keys of a message: what the robot reports about its devices.
void ApplyOutputs(const std::string& type, const std::string& device, const json& d) {
  int ch = ParseIndex(device);
  if (type == "PWM") {
    ON(bool, "<init", HALSIM_SetPWMInitialized(ch, v));
    ON(int32_t, "<raw", HALSIM_SetPWMPulseMicrosecond(ch, v));
    ON(int32_t, "<output_period", HALSIM_SetPWMOutputPeriod(ch, v));
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
  } else if (type == "Encoder") {
    ON(bool, "<init", HALSIM_SetEncoderInitialized(ch, v));
    ON(bool, "<reverse_direction", HALSIM_SetEncoderReverseDirection(ch, v));
    if (ch >= 0 && ch < HAL_GetNumEncoders()) {
      ON(int32_t, "<channel_a", wpi::hal::SimEncoderData[ch].digitalChannelA = v);
      ON(int32_t, "<channel_b", wpi::hal::SimEncoderData[ch].digitalChannelB = v);
    }
  } else if (type == "CTREPCM") {
    ON(bool, "<init", HALSIM_SetCTREPCMInitialized(ch, v));
    ON(bool, "<closed_loop", HALSIM_SetCTREPCMClosedLoopEnabled(ch, v));
  } else if (type == "Solenoid") {
    auto [mod, chan] = wpi::util::split(device, ',');
    ON(bool, "<output", HALSIM_SetCTREPCMSolenoidOutput(ParseIndex(mod), ParseIndex(chan), v));
  } else if (type == "PowerDistribution") {
    ON(bool, "<init", HALSIM_SetPowerDistributionInitialized(ch, v));
  } else if (type == "REVPH") {
    ON(bool, "<init", HALSIM_SetREVPHInitialized(ch, v));
    ON(int32_t, "<config", HALSIM_SetREVPHCompressorConfigType(ch, static_cast<HAL_REVPHCompressorConfigType>(v)));
  } else if (type == "REVPHSolenoid") {
    auto [mod, chan] = wpi::util::split(device, ',');
    ON(bool, "<output", HALSIM_SetREVPHSolenoidOutput(ParseIndex(mod), ParseIndex(chan), v));
  } else if (type == "AddressableLED") {
    ON(bool, "<init", HALSIM_SetAddressableLEDInitialized(ch, v));
    ON(int32_t, "<start", HALSIM_SetAddressableLEDStart(ch, std::max(v, 0)));
    ON(int32_t, "<length", HALSIM_SetAddressableLEDLength(ch, v));
    ApplyLedData(ch, d);
  } else if (type == "Joystick") {
    int32_t leds = 0;
    int32_t left = 0;
    int32_t right = 0;
    int32_t leftTrigger = 0;
    int32_t rightTrigger = 0;
    HALSIM_GetJoystickLeds(ch, &leds);
    HALSIM_GetJoystickRumbles(ch, &left, &right, &leftTrigger, &rightTrigger);
    Read(d, "<leds", leds);
    Read(d, "<rumble_left", left);
    Read(d, "<rumble_right", right);
    HALSIM_SetJoystickLeds(ch, leds);
    HALSIM_SetJoystickRumbles(ch, left, right, leftTrigger, rightTrigger);
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
  for (auto&& [key, value] : data.get_object()) {
    HAL_Value hv;
    if (value.is_bool()) {
      hv = HAL_MakeBoolean(value.get_bool());
    } else if (value.is_number()) {
      hv = HAL_MakeDouble(value.get_number());
    } else {
      continue;
    }
    auto it = dev.values.find(key);
    if (it == dev.values.end()) {
      int direction = HAL_SIM_VALUE_OUTPUT;
      std::string_view valueName = key;
      if (valueName.starts_with("<>")) {
        direction = HAL_SIM_VALUE_BIDIR;
        valueName.remove_prefix(2);
      } else if (valueName.starts_with('>')) {
        direction = HAL_SIM_VALUE_INPUT;
        valueName.remove_prefix(1);
      } else if (valueName.starts_with('<')) {
        valueName.remove_prefix(1);
      }
      auto handle = HAL_CreateSimValue(dev.handle, std::string{valueName}.c_str(), direction, &hv);
      if (handle == 0) {
        continue;
      }
      auto entry = std::make_unique<SimValue>(SimValue{handle, device, type, key, direction != HAL_SIM_VALUE_OUTPUT});
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
  auto& type = msg.at("type").get_string();
  auto& device = msg.at("device").get_string();
  auto& data = msg.at("data");
  if (type == "sim.timing") {
    if (bool paused; Read(data, "paused", paused)) {
      paused ? wpi::hal::PauseTiming() : wpi::hal::ResumeTiming();
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
    for (auto&& [key, value] : data.get_object()) {
      if (IsInput(key)) {
        gSent[DeviceKey(type, device) + "/" + key] = value;
      }
    }
    ApplyOutputs(type, device, data);
    if (type == "DriverStation") {
      ApplyOpModes(data);
    }
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
  HALSIM_SetDriverStationRobotMode(d.robotMode);
  HALSIM_SetDriverStationEStop(d.estop);
  HALSIM_SetDriverStationFmsAttached(d.fms);
  HALSIM_SetDriverStationDsAttached(d.ds);
  HALSIM_SetDriverStationAllianceStationId(d.station);
  HALSIM_SetDriverStationMatchTime(d.matchTime);
  HALSIM_SetDriverStationOpMode(d.opMode);
  gApplying = false;
  gDriverStation.reset();
}

void Receive(const std::string& text) {
  auto messages = json::parse(text);
  if (!messages || !messages->is_array()) {
    emscripten_console_warn(messages ? "robot messages are not an array" : messages.error());
    return;
  }
  bool driverStation = false;
  for (auto&& msg : messages->get_array()) {
    try {
      auto* type = msg.lookup("type");
      driverStation |= type && type->is_string() && type->get_string() == "DriverStation";
      ApplyMessage(msg);
    } catch (const std::exception& e) {
      gApplying = false;
      emscripten_console_warn(e.what());
    }
  }
  if (driverStation) {
    RestoreDriverStation();
  }
}

// The robot is gone: its devices go away with it.
void Reset() {
  gApplying = true;
  const json uninit = json::object("<init", false, "<accum_init", false);
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
  // A selection of this robot's op modes means nothing to the next robot.
  HAL_SetOpModeOptions(nullptr, 0);
  HALSIM_SetDriverStationOpMode(0);
  HALSIM_SetProgramState(HAL_ControlWord{0});
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
  Send("DriverStation", "", ">opmode", std::to_string(HALSIM_GetDriverStationOpMode()));
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
  Queue("sim.timing", "", "op", op);
  gOut.back().data["us"] = us;
}

namespace simgui {

// Registers the providers and connects to the page. Call after HAL_Initialize.
void StartMirror() {
  gConnection = std::make_shared<Connection>();
  using namespace wpilibws;
  HALSimWSProviderAddressableLED::Initialize(Register);
  HALSimWSProviderAnalogIn::Initialize(Register);
  HALSimWSProviderDIO::Initialize(Register);
  HALSimWSProviderDigitalPWM::Initialize(Register);
  HALSimWSProviderDriverStation::Initialize(Register);
  HALSimWSProviderEncoder::Initialize(Register);
  HALSimWSProviderJoystick::Initialize(Register);
  HALSimWSProviderPCM::Initialize(Register);
  HALSimWSProviderPWM::Initialize(Register);
  HALSimWSProviderRoboRIO::Initialize(Register);
  HALSimWSProviderSolenoid::Initialize(Register);

  gApplying = true;
  gProviders.ForEach([](auto provider) { provider->OnNetworkConnected(gConnection); });
  gApplying = false;

  // Not a halsim_ws_core provider key: the selection goes to the robot as a string.
  HALSIM_RegisterDriverStationOpModeCallback(
      [](const char*, void*, const HAL_Value*) {
        if (!gApplying) {
          Send("DriverStation", "", ">opmode", std::to_string(HALSIM_GetDriverStationOpMode()));
        }
      },
      nullptr, false);

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
  // The GUI enables the robot only once its program has started.
  HALSIM_SetProgramStarted(status == 2);
  if (status == 0) {
    Reset();
    wpi::hal::ResumeTiming();
  } else if (status == 2) {
    Resend();
    gDriverStation = DriverStationState{static_cast<bool>(HALSIM_GetDriverStationEnabled()),
                                        HALSIM_GetDriverStationRobotMode(),
                                        static_cast<bool>(HALSIM_GetDriverStationEStop()),
                                        static_cast<bool>(HALSIM_GetDriverStationFmsAttached()),
                                        static_cast<bool>(HALSIM_GetDriverStationDsAttached()),
                                        HALSIM_GetDriverStationAllianceStationId(),
                                        HALSIM_GetDriverStationMatchTime(),
                                        HALSIM_GetDriverStationOpMode()};
  }
}

// State the tests look at.
EMSCRIPTEN_KEEPALIVE const char* frc_sim_state() {
  static std::string out;
  auto mode = HALSIM_GetDriverStationRobotMode();
  json state = json::object("ds", static_cast<bool>(HALSIM_GetDriverStationDsAttached()), "enabled",
                            static_cast<bool>(HALSIM_GetDriverStationEnabled()), "robotMode", static_cast<int>(mode), "autonomous",
                            mode == HAL_ROBOT_MODE_AUTONOMOUS, "test", mode == HAL_ROBOT_MODE_UTILITY, "known", json(gKnown),
                            "settingsDir", std::getenv("SIMGUI_DIR") ? std::getenv("SIMGUI_DIR") : "", "nt",
                            wpi::nt::IsConnected(wpi::nt::GetDefaultInstance()), "pwm", json::object());
  HAL_JoystickAxes axes{};
  HALSIM_GetJoystickAxes(0, &axes);
  std::vector<float> axes0(axes.axes, axes.axes + (16 - std::countl_zero(axes.available)));
  state["axes0"] = json(axes0);
  HAL_ControlWord program{};
  HALSIM_GetProgramState(&program);
  int32_t opModeCount = 0;
  auto* opModes = HALSIM_GetOpModeOptions(&opModeCount);
  HALSIM_FreeOpModeOptionsArray(opModes, opModeCount);
  state["opMode"] = std::to_string(HALSIM_GetDriverStationOpMode());
  state["programState"] = std::to_string(program.value);
  state["opModes"] = opModeCount;
  for (int i = 0; i < HAL_GetNumPWMChannels(); i++) {
    if (HALSIM_GetPWMInitialized(i)) {
      state["pwm"][std::to_string(i)] = HALSIM_GetPWMPulseMicrosecond(i);
    }
  }
  out = state.to_string();
  return out.c_str();
}

}  // extern "C"
