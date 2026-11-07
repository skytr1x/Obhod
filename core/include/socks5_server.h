#ifndef SOCKS5_SERVER_H
#define SOCKS5_SERVER_H

#include <atomic>
#include <chrono>
#include <cstdint>
#include <functional>
#include <map>
#include <mutex>
#include <set>
#include <string>
#include <sys/socket.h>
#include <thread>
#include <vector>

#include "desync_engine.h"
#include "strategy_selector.h"

namespace skyunblock {

class Socks5Server {
public:
    static constexpr int MAX_CONNECTIONS = 64;
    using ProtectorCallback = std::function<bool(int fd)>;
    using EventCallback = std::function<void(const std::string& message)>;

    Socks5Server();
    ~Socks5Server();

    bool start(uint16_t port, StrategySelector* selector, ProtectorCallback protector,
               EventCallback event = nullptr);
    void stop();

    bool isRunning() const { return m_running.load(); }
    uint16_t port() const { return m_port; }

    int  activeConnections() const { return m_activeConnections.load(); }
    long totalConnections()  const { return m_totalConnections.load(); }
    long bytesIn()           const { return m_bytesIn.load(); }
    long bytesOut()          const { return m_bytesOut.load(); }

private:
    struct Request {
        uint8_t command = 0;
        std::string host;
        uint16_t port = 0;
    };

    struct UdpPeer {
        int fd = -1;
        std::string key;
        std::string host;
        uint16_t port = 0;
        struct sockaddr_storage addr {};
        socklen_t addrLen = 0;
        std::string strategyKey;
        bool udpVerified = false;
        std::chrono::steady_clock::time_point lastFakeAt{};
        uint64_t rxAtLastFake = 0;
        uint64_t txAtLastFake = 0;
        uint64_t sentPackets = 0;
        uint64_t receivedPackets = 0;
        uint64_t sentBytes = 0;
        uint64_t receivedBytes = 0;
        std::chrono::steady_clock::time_point lastTxLog{};
        std::chrono::steady_clock::time_point lastRxLog{};
    };

    void acceptLoop();
    void handleClient(int clientFd);
    void handleConnect(int clientFd, const Request& request);
    void handleUdpAssociate(int clientFd, const Request& request);

    bool receiveRequest(int clientFd, Request& request, uint8_t& replyCode);
    bool sendReply(int clientFd, uint8_t rep, const struct sockaddr* bound = nullptr);

    void trackFd(int fd);
    void untrackFd(int fd);
    void closeTrackedFd(int fd);
    void waitForConnections(int timeoutMs);

    bool connectRemote(const std::string& host, uint16_t port, int& outFd,
                       struct sockaddr_storage* outAddr, socklen_t* outAddrLen,
                       std::string& error);
    int waitForInitialData(int clientFd, int remoteFd, int timeoutMs);
    bool readInitialPayload(int clientFd, std::vector<uint8_t>& payload,
                            std::string& host, bool captureOpaqueHeader);
    bool sendFirstPayload(int fd, const std::vector<uint8_t>& payload,
                          const std::string& host, const StrategyChoice& choice,
                          SendOutcome& outcome);
    bool waitForResponse(int fd, int timeoutMs, bool& readable, std::string& error);
    void relay(int clientFd, int remoteFd, const std::string& host = {},
               uint16_t port = 0, bool reportStream = false, int idleTimeoutMs = 0);

    bool createRemoteUdp(const std::string& host, uint16_t port, UdpPeer& peer,
                         std::string& error);
    void relayUdp(int clientFd, int relayFd);

    int m_listenFd = -1;
    uint16_t m_port = 0;
    StrategySelector* m_selector = nullptr;
    ProtectorCallback m_protector;
    EventCallback m_event;
    std::mutex m_eventMutex;
    std::map<std::string, std::chrono::steady_clock::time_point> m_lastEventLog;

    void emitEvent(const char* format, ...);
    bool shouldLogHost(const std::string& host);

    std::atomic<bool> m_running{false};
    std::thread m_acceptThread;
    std::mutex m_connectionMutex;
    std::set<int> m_connections;

    std::atomic<int>  m_activeConnections{0};
    std::atomic<long> m_totalConnections{0};
    std::atomic<long> m_bytesIn{0};
    std::atomic<long> m_bytesOut{0};
};

}

#endif
