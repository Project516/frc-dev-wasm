// Providers for the devices halsim_ws_core has none for: power distribution
// modules and REV PHs. The keys follow its CTREPCM and Solenoid providers, see
// docs/bridge-protocol.md. There are too many modules and channels to create
// them all up front, so a provider is made when the robot first mentions its
// device.

#include <stdint.h>

#include <charconv>
#include <functional>
#include <memory>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "wpi/hal/Ports.h"
#include "wpi/hal/simulation/PowerDistributionData.h"
#include "wpi/hal/simulation/REVPHData.h"
#include "wpi/halsim/ws_core/WSHalProviders.hpp"
#include "wpi/util/StringExtras.hpp"
#include "wpi/util/json.hpp"

namespace simgui {

namespace {

using wpi::util::json;

struct Entry {
  const char* key;
  std::function<int32_t(HAL_NotifyCallback, void*)> reg;
  std::function<void(int32_t)> cancel;
};

class EntryProvider final : public wpilibws::HALSimWSHalProvider {
 public:
  EntryProvider(std::string_view key, std::string_view type, std::string_view id, std::vector<Entry> entries,
                std::function<void(const json&)> apply)
      : HALSimWSHalProvider{key, type}, m_entries{std::move(entries)}, m_apply{std::move(apply)} {
    m_deviceId = id;
  }

  ~EntryProvider() override { CancelCallbacks(); }

  void OnNetValueChanged(const json& data) override { m_apply(data); }

 protected:
  void RegisterCallbacks() override {
    CancelCallbacks();
    for (auto& entry : m_entries) {
      auto param = std::make_unique<Param>(Param{this, entry.key});
      m_uids.push_back(entry.reg(&Notify, param.get()));
      m_params.push_back(std::move(param));
    }
  }

  void CancelCallbacks() override {
    for (size_t i = 0; i < m_uids.size(); i++) {
      m_entries[i].cancel(m_uids[i]);
    }
    m_uids.clear();
    m_params.clear();
  }

 private:
  struct Param {
    EntryProvider* self;
    const char* key;
  };

  static void Notify(const char*, void* param, const HAL_Value* value) {
    auto* p = static_cast<Param*>(param);
    json v;
    switch (value->type) {
      case HAL_BOOLEAN:
        v = static_cast<bool>(value->data.v_boolean);
        break;
      case HAL_DOUBLE:
        v = value->data.v_double;
        break;
      case HAL_ENUM:
        v = value->data.v_enum;
        break;
      case HAL_INT:
        v = value->data.v_int;
        break;
      default:
        return;
    }
    p->self->ProcessHalCallback(json::object(p->key, std::move(v)));
  }

  std::vector<Entry> m_entries;
  std::function<void(const json&)> m_apply;
  std::vector<int32_t> m_uids;
  std::vector<std::unique_ptr<Param>> m_params;
};

int ParseIndex(std::string_view text) {
  int value = -1;
  std::from_chars(text.data(), text.data() + text.size(), value);
  return value;
}

// "module,channel", or -1 for both when malformed.
std::pair<int, int> ParseChannel(std::string_view device) {
  auto [mod, chan] = wpi::util::split(device, ',');
  return {ParseIndex(mod), ParseIndex(chan)};
}

void ApplyBool(const json& data, const char* key, std::function<void(bool)> set) {
  if (auto* v = data.lookup(key); v && v->is_bool()) {
    set(v->get_bool());
  }
}

void ApplyNumber(const json& data, const char* key, std::function<void(double)> set) {
  if (auto* v = data.lookup(key); v && v->is_number()) {
    set(v->get_number());
  }
}

#define ENTRY(key, name, ...) \
  Entry { key, [=](HAL_NotifyCallback cb, void* p) { return HALSIM_Register##name##Callback(__VA_ARGS__ cb, p, true); }, [=](int32_t uid) { HALSIM_Cancel##name##Callback(__VA_ARGS__ uid); } }

}  // namespace

std::shared_ptr<wpilibws::HALSimWSBaseProvider> MakeExtraProvider(const std::string& type, const std::string& device) {
  if (type == "PowerDistribution") {
    int m = ParseIndex(device);
    if (m < 0 || m >= HAL_GetNumREVPDHModules()) {
      return nullptr;
    }
    return std::make_shared<EntryProvider>(
        "PowerDistribution/" + device, type, device,
        std::vector<Entry>{ENTRY("<init", PowerDistributionInitialized, m, ), ENTRY(">temperature", PowerDistributionTemperature, m, ),
                           ENTRY(">voltage", PowerDistributionVoltage, m, )},
        [m](const json& d) {
          ApplyNumber(d, ">temperature", [m](double v) { HALSIM_SetPowerDistributionTemperature(m, v); });
          ApplyNumber(d, ">voltage", [m](double v) { HALSIM_SetPowerDistributionVoltage(m, v); });
        });
  }
  if (type == "PDChannel") {
    auto [m, c] = ParseChannel(device);
    if (m < 0 || m >= HAL_GetNumREVPDHModules() || c < 0 || c >= HAL_GetNumREVPDHChannels()) {
      return nullptr;
    }
    return std::make_shared<EntryProvider>(
        "PDChannel/" + device, type, device, std::vector<Entry>{ENTRY(">current", PowerDistributionCurrent, m, c, )},
        [m, c](const json& d) { ApplyNumber(d, ">current", [m, c](double v) { HALSIM_SetPowerDistributionCurrent(m, c, v); }); });
  }
  if (type == "REVPH") {
    int m = ParseIndex(device);
    if (m < 0 || m >= HAL_GetNumREVPHModules()) {
      return nullptr;
    }
    return std::make_shared<EntryProvider>(
        "REVPH/" + device, type, device,
        std::vector<Entry>{ENTRY("<init", REVPHInitialized, m, ), ENTRY(">on", REVPHCompressorOn, m, ),
                           ENTRY("<config", REVPHCompressorConfigType, m, ), ENTRY(">pressure_switch", REVPHPressureSwitch, m, ),
                           ENTRY(">current", REVPHCompressorCurrent, m, )},
        [m](const json& d) {
          ApplyBool(d, ">on", [m](bool v) { HALSIM_SetREVPHCompressorOn(m, v); });
          ApplyBool(d, ">pressure_switch", [m](bool v) { HALSIM_SetREVPHPressureSwitch(m, v); });
          ApplyNumber(d, ">current", [m](double v) { HALSIM_SetREVPHCompressorCurrent(m, v); });
        });
  }
  if (type == "REVPHSolenoid") {
    auto [m, c] = ParseChannel(device);
    if (m < 0 || m >= HAL_GetNumREVPHModules() || c < 0 || c >= HAL_GetNumREVPHChannels()) {
      return nullptr;
    }
    return std::make_shared<EntryProvider>("REVPHSolenoid/" + device, type, device,
                                           std::vector<Entry>{ENTRY("<output", REVPHSolenoidOutput, m, c, )}, [](const json&) {});
  }
  return nullptr;
}

}  // namespace simgui
