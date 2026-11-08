#include "WasmNetwork.h"

#include <emscripten/html5.h>
#include <emscripten/websocket.h>

#include <cctype>

#include <wpi/Logger.h>
#include <wpi/function_ref.h>
#include <wpi/raw_ostream.h>
#include <wpi/timestamp.h>

#include "IConnectionList.h"
#include "Log.h"
#include "net/NetworkInterface.h"
#include "net/WireConnection.h"

using namespace nt;

static constexpr int kReconnectMs = 1000;
static constexpr uint32_t kDefaultPeriodMs = 100;
static constexpr const char* kProtocols =
    "v4.1.networktables.first.wpi.edu,networktables.first.wpi.edu";

static uint64_t NowMs() {
  return wpi::Now() / 1000;
}

static std::string EscapeUri(std::string_view s) {
  static constexpr char hex[] = "0123456789ABCDEF";
  std::string out;
  for (unsigned char c : s) {
    if (std::isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~') {
      out += c;
    } else {
      out += '%';
      out += hex[c >> 4];
      out += hex[c & 15];
    }
  }
  return out;
}

namespace {

// Text frames are JSON arrays of messages, binary frames are concatenated
// msgpack messages. Writes batch up until Flush.
class WebSocketWire final : public net::WireConnection {
 public:
  WebSocketWire(EMSCRIPTEN_WEBSOCKET_T ws, unsigned int version)
      : m_ws{ws}, m_version{version} {}

  unsigned int GetVersion() const final { return m_version; }

  // The browser answers server pings itself, and the connection state comes
  // from the close event, so there is nothing to time out here.
  void SendPing(uint64_t) final {}
  uint64_t GetLastReceivedTime() const final { return wpi::Now(); }

  bool Ready() const final { return true; }

  int WriteText(wpi::function_ref<void(wpi::raw_ostream& os)> writer) final {
    wpi::raw_string_ostream os{m_text};
    os << (m_text.empty() ? '[' : ',');
    writer(os);
    return 0;
  }
  int WriteBinary(wpi::function_ref<void(wpi::raw_ostream& os)> writer) final {
    wpi::raw_string_ostream os{m_binary};
    writer(os);
    return 0;
  }
  int Flush() final {
    if (!m_text.empty()) {
      m_text += ']';
      emscripten_websocket_send_utf8_text(m_ws, m_text.c_str());
      m_text.clear();
    }
    if (!m_binary.empty()) {
      emscripten_websocket_send_binary(m_ws, m_binary.data(), m_binary.size());
      m_binary.clear();
    }
    m_lastFlush = wpi::Now();
    return 0;
  }

  void SendText(wpi::function_ref<void(wpi::raw_ostream& os)> writer) final {
    std::string text = "[";
    {
      wpi::raw_string_ostream os{text};
      writer(os);
    }
    text += ']';
    emscripten_websocket_send_utf8_text(m_ws, text.c_str());
  }
  void SendBinary(wpi::function_ref<void(wpi::raw_ostream& os)> writer) final {
    std::string data;
    {
      wpi::raw_string_ostream os{data};
      writer(os);
    }
    emscripten_websocket_send_binary(m_ws, data.data(), data.size());
  }

  uint64_t GetLastFlushTime() const final { return m_lastFlush; }

  void StopRead() final {}
  void StartRead() final {}

  void Disconnect(std::string_view reason) final {
    m_reason = reason;
    emscripten_websocket_close(m_ws, 1001, m_reason.c_str());
  }

  std::string_view GetDisconnectReason() const { return m_reason; }

 private:
  EMSCRIPTEN_WEBSOCKET_T m_ws;
  unsigned int m_version;
  std::string m_text;
  std::string m_binary;
  std::string m_reason;
  uint64_t m_lastFlush = 0;
};

}  // namespace

// Owned by its WebSocket until the close event; client is cleared when the
// NetworkClient goes away first.
struct NetworkClient::Connection {
  NetworkClient* client;
  EMSCRIPTEN_WEBSOCKET_T ws;
  std::string server;
  unsigned int port;
};

NetworkClient::NetworkClient(
    int inst, std::string_view id, net::ILocalStorage& localStorage,
    IConnectionList& connList, wpi::Logger& logger,
    std::function<void(int64_t serverTimeOffset, int64_t rtt2, bool valid)>
        timeSyncUpdated)
    : m_inst{inst},
      m_localStorage{localStorage},
      m_connList{connList},
      m_logger{logger},
      m_id{id},
      m_timeSyncUpdated{std::move(timeSyncUpdated)},
      m_localQueue{logger} {
  INFO("starting network client");
}

NetworkClient::~NetworkClient() {
  emscripten_clear_timeout(m_connectTimer);
  emscripten_clear_timeout(m_flushTimer);
  emscripten_clear_interval(m_tickTimer);
  if (m_conn) {
    m_conn->client = nullptr;
    emscripten_websocket_close(m_conn->ws, 1000, "");
  }
  m_clientImpl.reset();
  m_wire.reset();
  m_localStorage.ClearNetwork();
  m_connList.ClearConnections();
}

void NetworkClient::SetServers(
    std::span<const std::pair<std::string, unsigned int>> servers) {
  m_servers.clear();
  for (auto&& server : servers) {
    m_servers.emplace_back(server.first,
                           server.second == 0 ? NT_DEFAULT_PORT4 : server.second);
  }
  m_nextServer = 0;
  if (!m_conn) {
    ScheduleConnect(0);
  }
}

void NetworkClient::Disconnect() {
  if (m_wire) {
    static_cast<WebSocketWire&>(*m_wire).Disconnect("requested by application");
  }
}

void NetworkClient::FlushLocal() {
  ScheduleFlush();
}

void NetworkClient::Flush() {
  ScheduleFlush();
}

void NetworkClient::ScheduleFlush() {
  if (m_flushTimer || !m_clientImpl) {
    return;
  }
  m_flushTimer = emscripten_set_timeout(
      [](void* self) {
        auto* client = static_cast<NetworkClient*>(self);
        client->m_flushTimer = 0;
        client->Tick(true);
      },
      0, this);
}

void NetworkClient::ScheduleConnect(int delayMs) {
  if (m_connectTimer || m_servers.empty()) {
    return;
  }
  m_connectTimer = emscripten_set_timeout(
      [](void* self) {
        auto* client = static_cast<NetworkClient*>(self);
        client->m_connectTimer = 0;
        client->Connect();
      },
      delayMs, this);
}

void NetworkClient::Connect() {
  if (m_conn || m_servers.empty()) {
    return;
  }
  auto& [host, port] = m_servers[m_nextServer++ % m_servers.size()];
  std::string url = "ws://" + host + ":" + std::to_string(port) + "/nt/" +
                    EscapeUri(m_id);
  DEBUG4("Starting WebSocket client on {}", url);

  EmscriptenWebSocketCreateAttributes attrs;
  emscripten_websocket_init_create_attributes(&attrs);
  attrs.url = url.c_str();
  attrs.protocols = kProtocols;
  EMSCRIPTEN_WEBSOCKET_T ws = emscripten_websocket_new(&attrs);
  if (ws <= 0) {
    ScheduleConnect(kReconnectMs);
    return;
  }

  auto* conn = new Connection{this, ws, host, port};
  m_conn = conn;
  emscripten_websocket_set_onopen_callback(
      ws, conn, [](int, const EmscriptenWebSocketOpenEvent*, void* user) {
        auto* conn = static_cast<Connection*>(user);
        if (conn->client) {
          char protocol[64] = {};
          emscripten_websocket_get_protocol(conn->ws, protocol,
                                            sizeof(protocol));
          conn->client->Opened(*conn, protocol);
        }
        return true;
      });
  emscripten_websocket_set_onmessage_callback(
      ws, conn,
      [](int, const EmscriptenWebSocketMessageEvent* event, void* user) {
        auto* conn = static_cast<Connection*>(user);
        auto* self = conn->client;
        if (!self || !self->m_clientImpl) {
          return true;
        }
        if (event->isText) {
          // numBytes includes the terminating null
          self->m_clientImpl->ProcessIncomingText(
              {reinterpret_cast<const char*>(event->data),
               event->numBytes - 1});
        } else {
          self->m_clientImpl->ProcessIncomingBinary(
              NowMs(), {event->data, event->numBytes});
        }
        return true;
      });
  emscripten_websocket_set_onclose_callback(
      ws, conn, [](int, const EmscriptenWebSocketCloseEvent* event, void* user) {
        auto* conn = static_cast<Connection*>(user);
        if (conn->client) {
          conn->client->Closed(*conn, event->reason);
        }
        emscripten_websocket_delete(conn->ws);
        delete conn;
        return true;
      });
}

void NetworkClient::Opened(Connection& conn, std::string_view protocol) {
  unsigned int version =
      protocol == "v4.1.networktables.first.wpi.edu" ? 0x0401 : 0x0400;

  ConnectionInfo connInfo;
  connInfo.remote_ip = conn.server;
  connInfo.remote_port = conn.port;
  connInfo.protocol_version = version;
  INFO("CONNECTED NT4 to {} port {}", connInfo.remote_ip, connInfo.remote_port);
  m_connHandle = m_connList.AddConnection(connInfo);

  m_wire = std::make_unique<WebSocketWire>(conn.ws, version);
  m_clientImpl = std::make_unique<net::ClientImpl>(
      NowMs(), *m_wire, m_logger, m_timeSyncUpdated,
      [this](uint32_t repeatMs) { SetPeriod(repeatMs); });
  m_clientImpl->SetLocal(&m_localStorage);
  m_localStorage.StartNetwork(&m_localQueue);
  HandleLocal();
  m_clientImpl->SendInitial();
  SetPeriod(m_period ? m_period : kDefaultPeriodMs);
}

void NetworkClient::Closed(Connection& conn, std::string_view reason) {
  m_conn = nullptr;
  if (m_wire) {
    auto wireReason = static_cast<WebSocketWire&>(*m_wire).GetDisconnectReason();
    DoDisconnect(wireReason.empty() ? reason : wireReason);
  } else {
    ScheduleConnect(kReconnectMs);
  }
}

void NetworkClient::DoDisconnect(std::string_view reason) {
  INFO("DISCONNECTED NT4 connection: {}", reason);
  emscripten_clear_interval(m_tickTimer);
  m_tickTimer = 0;
  m_clientImpl.reset();
  m_wire.reset();
  m_localStorage.ClearNetwork();
  m_localQueue.ClearQueue();
  m_connList.RemoveConnection(m_connHandle);
  m_connHandle = 0;
  m_timeSyncUpdated(0, 0, false);
  ScheduleConnect(kReconnectMs);
}

void NetworkClient::HandleLocal() {
  for (;;) {
    auto msgs = m_localQueue.ReadQueue(m_localMsgs);
    if (msgs.empty()) {
      return;
    }
    if (m_clientImpl) {
      m_clientImpl->HandleLocal(msgs);
    }
  }
}

void NetworkClient::Tick(bool flush) {
  if (m_clientImpl) {
    HandleLocal();
    m_clientImpl->SendOutgoing(NowMs(), flush);
  }
}

void NetworkClient::SetPeriod(uint32_t repeatMs) {
  if (m_tickTimer && repeatMs == m_period) {
    return;
  }
  DEBUG4("Setting periodic timer to {}", repeatMs);
  m_period = repeatMs;
  emscripten_clear_interval(m_tickTimer);
  m_tickTimer = emscripten_set_interval(
      [](void* self) { static_cast<NetworkClient*>(self)->Tick(false); },
      repeatMs, this);
}

NetworkClient3::NetworkClient3(int, std::string_view, net::ILocalStorage&,
                               IConnectionList&, wpi::Logger& logger) {
  WPI_ERROR(logger, "NT3 is not supported in the browser, use NT4");
}

NetworkServer::NetworkServer(std::string_view, std::string_view, unsigned int,
                             unsigned int, net::ILocalStorage&,
                             IConnectionList&, wpi::Logger& logger,
                             std::function<void()> initDone) {
  WPI_ERROR(logger, "NT server mode is not supported in the browser");
  initDone();
}
