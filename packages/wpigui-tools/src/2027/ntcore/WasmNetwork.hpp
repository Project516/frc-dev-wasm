// NT4 client transport for browsers. Replaces ntcore's NetworkClient and
// NetworkServer, which need libuv and threads. Driven by browser timers and
// WebSocket events on the main thread. Included from ntcore's InstanceImpl.hpp.

#pragma once

#include <stdint.h>

#include <functional>
#include <memory>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "INetworkClient.hpp"
#include "net/ClientImpl.hpp"
#include "net/ClientMessageQueue.hpp"
#include "net/Message.hpp"

namespace wpi::util {
class Logger;
}  // namespace wpi::util

namespace wpi::nt::net {
class ILocalStorage;
}  // namespace wpi::nt::net

namespace wpi::nt {

class IConnectionList;

class NetworkClient final : public INetworkClient {
 public:
  NetworkClient(
      int inst, std::string_view id, net::ILocalStorage& localStorage,
      IConnectionList& connList, wpi::util::Logger& logger,
      std::function<void(int64_t serverTimeOffset, int64_t rtt2, bool valid)>
          timeSyncUpdated);
  ~NetworkClient() final;

  void SetServers(
      std::span<const std::pair<std::string, unsigned int>> servers) final;
  // There is no Driver Station or mDNS to ask in a browser.
  void SetServers(std::span<const std::pair<std::string, unsigned int>> servers,
                  const ServerResolver&) final {
    SetServers(servers);
  }
  void Disconnect() final;

  void StartDSClient(unsigned int) final {}
  void StopDSClient() final {}

  void FlushLocal() final;
  void Flush() final;

  struct Connection;

 private:
  friend struct Connection;

  void ScheduleConnect(int delayMs);
  void Connect();
  void Opened(Connection& conn, std::string_view protocol);
  void Closed(Connection& conn, std::string_view reason);
  void DoDisconnect(std::string_view reason);
  void HandleLocal();
  void Tick(bool flush);
  void SetPeriod(uint32_t repeatMs);
  void ScheduleFlush();

  int m_inst;
  net::ILocalStorage& m_localStorage;
  IConnectionList& m_connList;
  wpi::util::Logger& m_logger;
  std::string m_id;
  std::function<void(int64_t, int64_t, bool)> m_timeSyncUpdated;

  std::vector<std::pair<std::string, unsigned int>> m_servers;
  size_t m_nextServer = 0;

  using Queue = net::LocalClientMessageQueue;
  Queue m_localQueue;
  net::ClientMessage m_localMsgs[Queue::BLOCK_SIZE];

  Connection* m_conn = nullptr;
  std::unique_ptr<net::WireConnection> m_wire;
  std::unique_ptr<net::ClientImpl> m_clientImpl;
  int m_connHandle = 0;

  int m_connectTimer = 0;
  int m_flushTimer = 0;
  int m_tickTimer = 0;
  uint32_t m_period = 0;
};

// A browser cannot listen for connections.
class NetworkServer {
 public:
  NetworkServer(std::string_view persistentFilename,
                std::string_view listenAddress, std::string_view mdnsService,
                unsigned int port, net::ILocalStorage& localStorage,
                IConnectionList& connList, wpi::util::Logger& logger,
                std::function<void(bool)> initDone);

  void FlushLocal() {}
  void Flush() {}
};

}  // namespace wpi::nt
