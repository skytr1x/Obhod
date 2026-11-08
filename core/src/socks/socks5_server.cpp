#include "socks5_server.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <map>
#include <thread>
#include <vector>

#include "sky_log.h"
#include "tls_utils.h"

namespace skyunblock {

namespace {

constexpr uint8_t SOCKS5_VERSION = 0x05;
constexpr uint8_t SOCKS5_NO_AUTH = 0x00;
constexpr uint8_t SOCKS5_CMD_CONNECT = 0x01;
constexpr uint8_t SOCKS5_CMD_UDP_ASSOCIATE = 0x03;
constexpr uint8_t SOCKS5_ATYP_IPV4 = 0x01;
constexpr uint8_t SOCKS5_ATYP_DOMAIN = 0x03;
constexpr uint8_t SOCKS5_ATYP_IPV6 = 0x04;
constexpr uint8_t SOCKS5_REP_SUCCESS = 0x00;
constexpr uint8_t SOCKS5_REP_FAILURE = 0x01;
constexpr uint8_t SOCKS5_REP_HOST_UNREACHABLE = 0x04;
constexpr uint8_t SOCKS5_REP_CMD_UNSUPPORTED = 0x07;
constexpr uint8_t SOCKS5_REP_ATYP_UNSUPPORTED = 0x08;

constexpr int kConnectTimeoutMs = 8000;
constexpr int kFirstPayloadTimeoutMs = 30000;

constexpr int kResponseTimeoutMs = 1500;
constexpr int kMaxStrategyAttempts = 7;
constexpr int kStrategyBudgetMs = 12000;
constexpr int kRelayIdleTimeoutMs = 120000;
constexpr int kRelayTunnelIdleTimeoutMs = 300000;
constexpr std::size_t kMaxFirstPayload = 32768;
constexpr std::size_t kMaxUdpPeers = 64;

std::atomic<uint64_t> g_nextTcpRequestId{1};
thread_local uint64_t g_tcpRequestId = 0;

class TcpRequestLogScope {
public:
    explicit TcpRequestLogScope(uint64_t requestId)
        : m_previous(g_tcpRequestId) {
        g_tcpRequestId = requestId;
    }

    ~TcpRequestLogScope() { g_tcpRequestId = m_previous; }

private:
    uint64_t m_previous;
};

const char* eventErrorCategory(const std::string& error) {
    if (error.find("timeout") != std::string::npos) return "timeout";
    if (error.find("alert") != std::string::npos) return "tls_alert";
    if (error.find("closed") != std::string::npos) return "server_closed";
    if (error.find("truncated") != std::string::npos) return "truncated_response";
    if (error.find("unexpected response") != std::string::npos) {
        return "unexpected_response";
    }
    if (error.find("not a TLS") != std::string::npos) return "not_tls";
    if (error.find("stopped") != std::string::npos) return "stopped";
    if (error.find("send") != std::string::npos) return "send_failed";
    if (error.find("reconnect") != std::string::npos) return "reconnect_failed";
    return "transport_error";
}

bool isTelegramDcAddress(const std::string& host) {
    static constexpr const char* kTelegramIpv4Ranges[] = {
        "91.108.56.0/22",
        "91.108.4.0/22",
        "91.108.8.0/22",
        "91.108.16.0/22",
        "91.108.12.0/22",
        "149.154.160.0/20",
        "91.105.192.0/23",
        "91.108.20.0/22",
        "185.76.151.0/24"
    };
    static constexpr const char* kTelegramIpv6Ranges[] = {
        "2001:b28:f23d::/48",
        "2001:b28:f23f::/48",
        "2001:67c:4e8::/48",
        "2001:b28:f23c::/48",
        "2a0a:f280::/32"
    };

    struct in_addr ipv4{};
    if (::inet_pton(AF_INET, host.c_str(), &ipv4) == 1) {
        const uint32_t address = ntohl(ipv4.s_addr);
        for (const char* range : kTelegramIpv4Ranges) {
            char networkText[INET_ADDRSTRLEN] = {};
            unsigned prefix = 0;
            if (std::sscanf(range, "%15[^/]/%u", networkText, &prefix) != 2 ||
                prefix > 32) {
                continue;
            }
            struct in_addr network{};
            if (::inet_pton(AF_INET, networkText, &network) != 1) continue;
            const uint32_t mask = prefix == 0 ? 0 : 0xffffffffu << (32 - prefix);
            if ((address & mask) == (ntohl(network.s_addr) & mask)) return true;
        }
        return false;
    }

    struct in6_addr ipv6{};
    if (::inet_pton(AF_INET6, host.c_str(), &ipv6) != 1) return false;
    for (const char* range : kTelegramIpv6Ranges) {
        char networkText[INET6_ADDRSTRLEN] = {};
        unsigned prefix = 0;
        if (std::sscanf(range, "%45[^/]/%u", networkText, &prefix) != 2 ||
            prefix > 128) {
            continue;
        }
        struct in6_addr network{};
        if (::inet_pton(AF_INET6, networkText, &network) != 1) continue;
        const unsigned fullBytes = prefix / 8;
        const unsigned remainingBits = prefix % 8;
        if (fullBytes > 0 &&
            std::memcmp(ipv6.s6_addr, network.s6_addr, fullBytes) != 0) {
            continue;
        }
        if (remainingBits == 0 || fullBytes >= sizeof(ipv6.s6_addr)) return true;
        const uint8_t mask = static_cast<uint8_t>(0xffu << (8 - remainingBits));
        if ((ipv6.s6_addr[fullBytes] & mask) ==
            (network.s6_addr[fullBytes] & mask)) {
            return true;
        }
    }
    return false;
}

bool isMtprotoPort(uint16_t port) {
    return port == 80 || port == 443 || port == 5222 || port == 5223 ||
           port == 5225 || port == 5226;
}

bool isLikelyObfuscatedMtproto(const std::vector<uint8_t>& payload,
                               const std::string& host, uint16_t port) {
    if (!isMtprotoPort(port) || !isTelegramDcAddress(host) ||
        payload.size() < 64 || payload[0] == 0xef || payload[0] == 0x16) {
        return false;
    }

    const uint32_t firstWord = static_cast<uint32_t>(payload[0]) |
                               (static_cast<uint32_t>(payload[1]) << 8) |
                               (static_cast<uint32_t>(payload[2]) << 16) |
                               (static_cast<uint32_t>(payload[3]) << 24);
    static constexpr uint32_t knownProtocols[] = {
        0x44414548, 0x54534f50, 0x20544547, 0x4954504f,
        0xdddddddd, 0xeeeeeeee, 0x02010316
    };
    for (uint32_t protocol : knownProtocols) {
        if (firstWord == protocol) return false;
    }
    return true;
}

bool recvAll(int fd, uint8_t* buffer, std::size_t len) {
    std::size_t received = 0;
    while (received < len) {
        const ssize_t n = ::recv(fd, buffer + received, len - received, 0);
        if (n < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        if (n == 0) return false;
        received += static_cast<std::size_t>(n);
    }
    return true;
}

bool sendAll(int fd, const uint8_t* buffer, std::size_t len) {
    std::size_t sent = 0;
    while (sent < len) {
        const ssize_t n = ::send(fd, buffer + sent, len - sent, MSG_NOSIGNAL);
        if (n < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        if (n == 0) return false;
        sent += static_cast<std::size_t>(n);
    }
    return true;
}

bool waitReadable(int fd, int timeoutMs) {
    struct pollfd pfd{fd, POLLIN, 0};
    int rc;
    do {
        rc = ::poll(&pfd, 1, timeoutMs);
    } while (rc < 0 && errno == EINTR);
    return rc > 0 && (pfd.revents & (POLLIN | POLLHUP | POLLERR)) != 0;
}

void setIoTimeout(int fd, int seconds) {
    struct timeval tv{seconds, 0};
    ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    ::setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
}

bool setNonBlocking(int fd, bool enabled) {
    const int flags = ::fcntl(fd, F_GETFL, 0);
    if (flags < 0) return false;
    const int value = enabled ? (flags | O_NONBLOCK) : (flags & ~O_NONBLOCK);
    return ::fcntl(fd, F_SETFL, value) == 0;
}

bool isNumericHost(const std::string& host) {
    in_addr v4{};
    in6_addr v6{};
    return ::inet_pton(AF_INET, host.c_str(), &v4) == 1 ||
           ::inet_pton(AF_INET6, host.c_str(), &v6) == 1;
}

std::string formatEndpoint(const std::string& host, uint16_t port) {
    if (host.find(':') != std::string::npos) {
        return "[" + host + "]:" + std::to_string(port);
    }
    return host + ":" + std::to_string(port);
}

std::string sockaddrText(const sockaddr* addr) {
    char text[INET6_ADDRSTRLEN] = {};
    if (addr->sa_family == AF_INET) {
        const auto* v4 = reinterpret_cast<const sockaddr_in*>(addr);
        ::inet_ntop(AF_INET, &v4->sin_addr, text, sizeof(text));
    } else if (addr->sa_family == AF_INET6) {
        const auto* v6 = reinterpret_cast<const sockaddr_in6*>(addr);
        ::inet_ntop(AF_INET6, &v6->sin6_addr, text, sizeof(text));
    }
    return text;
}

bool resolve(const std::string& host, uint16_t port, int sockType,
             std::vector<sockaddr_storage>& out, std::vector<socklen_t>& lengths,
             std::string& error) {
    struct addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = sockType;
    hints.ai_protocol = sockType == SOCK_DGRAM ? IPPROTO_UDP : IPPROTO_TCP;
    if (isNumericHost(host)) hints.ai_flags = AI_NUMERICHOST;

    struct addrinfo* result = nullptr;
    const std::string service = std::to_string(port);
    const int rc = ::getaddrinfo(host.c_str(), service.c_str(), &hints, &result);
    if (rc != 0) {
        error = std::string("getaddrinfo(") + host + "): " + ::gai_strerror(rc);
        return false;
    }

    for (const struct addrinfo* ai = result; ai != nullptr; ai = ai->ai_next) {
        if (ai->ai_family != AF_INET && ai->ai_family != AF_INET6) continue;
        sockaddr_storage storage{};
        std::memcpy(&storage, ai->ai_addr, ai->ai_addrlen);
        out.push_back(storage);
        lengths.push_back(static_cast<socklen_t>(ai->ai_addrlen));
    }
    ::freeaddrinfo(result);

    if (out.empty()) {
        error = "no addresses for " + host;
        return false;
    }
    return true;
}

bool connectWithTimeout(int fd, const sockaddr* addr, socklen_t len, int timeoutMs,
                        std::string& error,
                        const std::atomic<bool>* running = nullptr) {
    if (running && !running->load()) {
        error = "stopped";
        return false;
    }
    if (!setNonBlocking(fd, true)) {
        error = std::string("fcntl: ") + std::strerror(errno);
        return false;
    }

    int rc = ::connect(fd, addr, len);
    if (rc == 0) {
        setNonBlocking(fd, false);
        return true;
    }
    if (errno != EINPROGRESS) {
        error = std::string("connect: ") + std::strerror(errno);
        return false;
    }

    struct pollfd pfd{fd, POLLOUT, 0};
    int remaining = timeoutMs;
    rc = 0;
    while (remaining > 0) {
        if (running && !running->load()) {
            error = "stopped";
            return false;
        }
        const int slice = std::min(remaining, 100);
        rc = ::poll(&pfd, 1, slice);
        if (rc > 0) break;
        if (rc < 0 && errno != EINTR) {
            error = std::string("poll: ") + std::strerror(errno);
            return false;
        }
        if (rc < 0) continue;
        remaining -= slice;
    }

    if (rc == 0) {
        error = "connect timeout";
        return false;
    }

    int soError = 0;
    socklen_t soLen = sizeof(soError);
    if (::getsockopt(fd, SOL_SOCKET, SO_ERROR, &soError, &soLen) < 0) {
        error = std::string("getsockopt: ") + std::strerror(errno);
        return false;
    }
    if (soError != 0) {
        error = std::string("connect: ") + std::strerror(soError);
        return false;
    }

    setNonBlocking(fd, false);
    return true;
}

bool isLoopback(const sockaddr* addr) {
    if (!addr) return false;
    if (addr->sa_family == AF_INET) {
        const auto* v4 = reinterpret_cast<const sockaddr_in*>(addr);
        return (ntohl(v4->sin_addr.s_addr) >> 24) == 127;
    }
    if (addr->sa_family == AF_INET6) {
        const auto* v6 = reinterpret_cast<const sockaddr_in6*>(addr);
        return IN6_IS_ADDR_LOOPBACK(&v6->sin6_addr);
    }
    return false;
}

std::string udpKey(const std::string& host, uint16_t port) {
    return host + ':' + std::to_string(port);
}

bool readQuicVarInt(const uint8_t* data, std::size_t len, std::size_t& offset,
                    uint64_t& value) {
    if (offset >= len) return false;
    const std::size_t byteCount = static_cast<std::size_t>(1) << (data[offset] >> 6);
    if (byteCount > len - offset) return false;

    value = data[offset] & 0x3f;
    for (std::size_t i = 1; i < byteCount; ++i) {
        value = (value << 8) | data[offset + i];
    }
    offset += byteCount;
    return true;
}

bool isQuicInitial(const uint8_t* data, std::size_t len) {
    if (len < 7 || (data[0] & 0xc0) != 0xc0) return false;
    const uint32_t version = (static_cast<uint32_t>(data[1]) << 24) |
                             (static_cast<uint32_t>(data[2]) << 16) |
                             (static_cast<uint32_t>(data[3]) << 8) |
                             static_cast<uint32_t>(data[4]);
    uint8_t initialType = 0;
    switch (version) {
        case 0x00000001:
            initialType = 0x00;
            break;
        case 0x6b3343cf:
            initialType = 0x10;
            break;
        default:
            return false;
    }

    if ((data[0] & 0x30) != initialType) return false;

    std::size_t offset = 5;
    const std::size_t destinationIdLength = data[offset++];
    if (destinationIdLength > 20 || destinationIdLength > len - offset) return false;
    offset += destinationIdLength;
    if (offset >= len) return false;

    const std::size_t sourceIdLength = data[offset++];
    if (sourceIdLength > 20 || sourceIdLength > len - offset) return false;
    offset += sourceIdLength;

    uint64_t tokenLength = 0;
    if (!readQuicVarInt(data, len, offset, tokenLength) ||
        tokenLength > len - offset) {
        return false;
    }
    offset += static_cast<std::size_t>(tokenLength);

    uint64_t packetLength = 0;
    return readQuicVarInt(data, len, offset, packetLength) && packetLength > 0 &&
           packetLength <= len - offset;
}

bool isStunBindingRequest(const uint8_t* data, std::size_t len) {
    constexpr std::size_t kStunHeaderSize = 20;
    if (len < kStunHeaderSize || data[0] != 0x00 || data[1] != 0x01 ||
        data[4] != 0x21 || data[5] != 0x12 || data[6] != 0xa4 || data[7] != 0x42) {
        return false;
    }

    const std::size_t messageLength =
        (static_cast<std::size_t>(data[2]) << 8) | data[3];
    return (messageLength & 0x03) == 0 && messageLength == len - kStunHeaderSize;
}

bool prepareUdpFake(UdpPlan& plan, bool quicInitial, bool stunBindingRequest) {
    if (plan.fakeCount <= 0) return false;

    if ((plan.payload == "quic_google" || plan.payload == "quic") &&
        stunBindingRequest && !quicInitial) {
        plan.payload = "stun";
    }

    if (plan.payload == "quic_google" || plan.payload == "quic") {
        return quicInitial;
    }
    if (plan.payload == "stun") return stunBindingRequest;
    if (plan.payload == "zero") return quicInitial || stunBindingRequest;
    return false;
}

std::vector<uint8_t> buildUdpResponse(const sockaddr* from, const uint8_t* data,
                                      std::size_t len) {
    std::vector<uint8_t> packet;
    packet.reserve(len + 22);
    packet.push_back(0x00);
    packet.push_back(0x00);
    packet.push_back(0x00);

    if (from->sa_family == AF_INET6) {
        const auto* v6 = reinterpret_cast<const sockaddr_in6*>(from);
        packet.push_back(SOCKS5_ATYP_IPV6);
        const auto* addr = reinterpret_cast<const uint8_t*>(&v6->sin6_addr);
        packet.insert(packet.end(), addr, addr + 16);
        const uint16_t port = ntohs(v6->sin6_port);
        packet.push_back(static_cast<uint8_t>(port >> 8));
        packet.push_back(static_cast<uint8_t>(port & 0xff));
    } else {
        const auto* v4 = reinterpret_cast<const sockaddr_in*>(from);
        packet.push_back(SOCKS5_ATYP_IPV4);
        const auto* addr = reinterpret_cast<const uint8_t*>(&v4->sin_addr);
        packet.insert(packet.end(), addr, addr + 4);
        const uint16_t port = ntohs(v4->sin_port);
        packet.push_back(static_cast<uint8_t>(port >> 8));
        packet.push_back(static_cast<uint8_t>(port & 0xff));
    }

    packet.insert(packet.end(), data, data + len);
    return packet;
}

}

Socks5Server::Socks5Server() = default;

Socks5Server::~Socks5Server() {
    stop();
    waitForConnections(10000);
}

void Socks5Server::emitEvent(const char* format, ...) {
    if (!m_event) return;
    char buffer[640];
    va_list args;
    va_start(args, format);
    std::vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);
    if (g_tcpRequestId != 0) {
        char contextual[704];
        std::snprintf(contextual, sizeof(contextual), "%s [tcp_id=%llu]",
                      buffer, static_cast<unsigned long long>(g_tcpRequestId));
        m_event(contextual);
        return;
    }
    m_event(buffer);
}

bool Socks5Server::shouldLogHost(const std::string& host) {
    const auto now = std::chrono::steady_clock::now();
    std::lock_guard<std::mutex> lock(m_eventMutex);
    auto it = m_lastEventLog.find(host);
    if (it != m_lastEventLog.end() &&
        now - it->second < std::chrono::minutes(5)) {
        return false;
    }
    if (m_lastEventLog.size() > 1024) m_lastEventLog.clear();
    m_lastEventLog[host] = now;
    return true;
}

bool Socks5Server::start(uint16_t port, StrategySelector* selector,
                         ProtectorCallback protector, EventCallback event) {
    if (m_running.load()) return true;

    m_selector = selector;
    m_protector = std::move(protector);
    m_event = std::move(event);
    {
        std::lock_guard<std::mutex> lock(m_eventMutex);
        m_lastEventLog.clear();
    }
    m_port = port;

    m_listenFd = ::socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, IPPROTO_TCP);
    if (m_listenFd < 0) {
        SKY_LOGE("SOCKS5: socket() failed: %s", std::strerror(errno));
        return false;
    }

    int reuse = 1;
    ::setsockopt(m_listenFd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

    struct sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    if (::bind(m_listenFd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) < 0) {
        SKY_LOGE("SOCKS5: bind() on 127.0.0.1:%u failed: %s", port, std::strerror(errno));
        ::close(m_listenFd);
        m_listenFd = -1;
        return false;
    }

    if (::listen(m_listenFd, 32) < 0) {
        SKY_LOGE("SOCKS5: listen() failed: %s", std::strerror(errno));
        ::close(m_listenFd);
        m_listenFd = -1;
        return false;
    }

    m_running = true;
    m_acceptThread = std::thread(&Socks5Server::acceptLoop, this);
    SKY_LOGI("SOCKS5: listening on 127.0.0.1:%u", port);
    return true;
}

void Socks5Server::trackFd(int fd) {
    if (fd < 0) return;
    std::lock_guard<std::mutex> lock(m_connectionMutex);
    m_connections.insert(fd);
}

void Socks5Server::untrackFd(int fd) {
    if (fd < 0) return;
    std::lock_guard<std::mutex> lock(m_connectionMutex);
    m_connections.erase(fd);
}

void Socks5Server::closeTrackedFd(int fd) {
    if (fd < 0) return;
    untrackFd(fd);
    ::shutdown(fd, SHUT_RDWR);
    ::close(fd);
}

void Socks5Server::waitForConnections(int timeoutMs) {
    const auto deadline = std::chrono::steady_clock::now() +
                          std::chrono::milliseconds(timeoutMs);
    while (m_activeConnections.load() > 0 &&
           std::chrono::steady_clock::now() < deadline) {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
}

void Socks5Server::stop() {
    if (!m_running.exchange(false)) return;

    if (m_listenFd >= 0) {
        ::shutdown(m_listenFd, SHUT_RDWR);
        ::close(m_listenFd);
        m_listenFd = -1;
    }

    if (m_acceptThread.joinable()) m_acceptThread.join();

    {
        std::lock_guard<std::mutex> lock(m_connectionMutex);
        for (int fd : m_connections) ::shutdown(fd, SHUT_RDWR);
    }
    waitForConnections(5000);
    if (m_activeConnections.load() > 0) {
        SKY_LOGW("SOCKS5: %d connection thread(s) still active during stop",
                 m_activeConnections.load());
    }

    SKY_LOGI("SOCKS5: stopped");
}

void Socks5Server::acceptLoop() {
    while (m_running.load()) {
        struct pollfd pfd{m_listenFd, POLLIN, 0};
        int pollResult;
        do {
            pollResult = ::poll(&pfd, 1, 200);
        } while (pollResult < 0 && errno == EINTR);

        if (!m_running.load()) break;
        if (pollResult == 0) continue;
        if (pollResult < 0) {
            SKY_LOGE("SOCKS5: poll() failed: %s", std::strerror(errno));
            break;
        }
        if (!(pfd.revents & POLLIN)) continue;

        struct sockaddr_in clientAddr{};
        socklen_t addrLen = sizeof(clientAddr);
        const int clientFd = ::accept4(m_listenFd,
                                       reinterpret_cast<struct sockaddr*>(&clientAddr),
                                       &addrLen, SOCK_CLOEXEC);
        if (clientFd < 0) {
            if (!m_running.load()) break;
            if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK ||
                errno == ECONNABORTED) continue;
            SKY_LOGE("SOCKS5: accept() failed: %s", std::strerror(errno));
            break;
        }

        if (!m_running.load()) {
            ::close(clientFd);
            break;
        }

        if (m_activeConnections.load() >= MAX_CONNECTIONS) {
            SKY_LOGW("SOCKS5: connection limit reached, dropping client");
            ::close(clientFd);
            continue;
        }

        m_totalConnections.fetch_add(1);
        m_activeConnections.fetch_add(1);
        trackFd(clientFd);
        std::thread([this, clientFd]() {
            handleClient(clientFd);
            closeTrackedFd(clientFd);
            m_activeConnections.fetch_sub(1);
        }).detach();
    }
}

void Socks5Server::handleClient(int clientFd) {
    setIoTimeout(clientFd, kFirstPayloadTimeoutMs / 1000);

    Request request;
    uint8_t replyCode = SOCKS5_REP_FAILURE;
    if (!receiveRequest(clientFd, request, replyCode)) {
        if (replyCode != SOCKS5_REP_SUCCESS) sendReply(clientFd, replyCode);
        return;
    }

    if (request.command == SOCKS5_CMD_CONNECT) {
        handleConnect(clientFd, request);
    } else if (request.command == SOCKS5_CMD_UDP_ASSOCIATE) {
        handleUdpAssociate(clientFd, request);
    } else {
        sendReply(clientFd, SOCKS5_REP_CMD_UNSUPPORTED);
    }
}

bool Socks5Server::receiveRequest(int clientFd, Request& request, uint8_t& replyCode) {
    uint8_t header[2];
    if (!recvAll(clientFd, header, sizeof(header))) return false;
    if (header[0] != SOCKS5_VERSION) {
        SKY_LOGW("SOCKS5: bad version 0x%02x", header[0]);
        return false;
    }

    const uint8_t methodCount = header[1];
    uint8_t methods[255];
    if (methodCount > 0 && !recvAll(clientFd, methods, methodCount)) return false;

    const uint8_t methodReply[2] = {SOCKS5_VERSION, SOCKS5_NO_AUTH};
    if (!sendAll(clientFd, methodReply, sizeof(methodReply))) return false;

    uint8_t req[4];
    if (!recvAll(clientFd, req, sizeof(req))) return false;
    if (req[0] != SOCKS5_VERSION) return false;

    request.command = req[1];
    if (request.command != SOCKS5_CMD_CONNECT &&
        request.command != SOCKS5_CMD_UDP_ASSOCIATE) {
        replyCode = SOCKS5_REP_CMD_UNSUPPORTED;
        return false;
    }

    const uint8_t atyp = req[3];
    if (atyp == SOCKS5_ATYP_IPV4) {
        uint8_t ip[4];
        if (!recvAll(clientFd, ip, sizeof(ip))) return false;
        char text[INET_ADDRSTRLEN] = {};
        ::inet_ntop(AF_INET, ip, text, sizeof(text));
        request.host = text;
    } else if (atyp == SOCKS5_ATYP_IPV6) {
        uint8_t ip[16];
        if (!recvAll(clientFd, ip, sizeof(ip))) return false;
        char text[INET6_ADDRSTRLEN] = {};
        ::inet_ntop(AF_INET6, ip, text, sizeof(text));
        request.host = text;
    } else if (atyp == SOCKS5_ATYP_DOMAIN) {
        uint8_t length = 0;
        if (!recvAll(clientFd, &length, 1) || length == 0) return false;
        std::string domain(length, '\0');
        if (!recvAll(clientFd, reinterpret_cast<uint8_t*>(&domain[0]), length)) return false;
        request.host = std::move(domain);
    } else {
        replyCode = SOCKS5_REP_ATYP_UNSUPPORTED;
        return false;
    }

    uint8_t portBytes[2];
    if (!recvAll(clientFd, portBytes, sizeof(portBytes))) return false;
    request.port = static_cast<uint16_t>((portBytes[0] << 8) | portBytes[1]);
    replyCode = SOCKS5_REP_SUCCESS;
    return true;
}

bool Socks5Server::sendReply(int clientFd, uint8_t rep, const struct sockaddr* bound) {
    std::vector<uint8_t> reply;
    if (bound && bound->sa_family == AF_INET6) {
        reply = {SOCKS5_VERSION, rep, 0x00, SOCKS5_ATYP_IPV6};
        const auto* v6 = reinterpret_cast<const sockaddr_in6*>(bound);
        const auto* addr = reinterpret_cast<const uint8_t*>(&v6->sin6_addr);
        reply.insert(reply.end(), addr, addr + 16);
        const uint16_t port = ntohs(v6->sin6_port);
        reply.push_back(static_cast<uint8_t>(port >> 8));
        reply.push_back(static_cast<uint8_t>(port & 0xff));
    } else if (bound && bound->sa_family == AF_INET) {
        reply = {SOCKS5_VERSION, rep, 0x00, SOCKS5_ATYP_IPV4};
        const auto* v4 = reinterpret_cast<const sockaddr_in*>(bound);
        const auto* addr = reinterpret_cast<const uint8_t*>(&v4->sin_addr);
        reply.insert(reply.end(), addr, addr + 4);
        const uint16_t port = ntohs(v4->sin_port);
        reply.push_back(static_cast<uint8_t>(port >> 8));
        reply.push_back(static_cast<uint8_t>(port & 0xff));
    } else {
        reply = {SOCKS5_VERSION, rep, 0x00, SOCKS5_ATYP_IPV4, 0, 0, 0, 0, 0, 0};
    }
    return sendAll(clientFd, reply.data(), reply.size());
}

bool Socks5Server::connectRemote(const std::string& host, uint16_t port, int& outFd,
                                 struct sockaddr_storage* outAddr, socklen_t* outAddrLen,
                                 std::string& error, std::size_t addressOffset,
                                 int timeoutMs) {
    std::vector<sockaddr_storage> addresses;
    std::vector<socklen_t> lengths;
    if (!resolve(host, port, SOCK_STREAM, addresses, lengths, error)) {
        if (m_running.load()) {
            emitEvent("[CONNECT-FAIL] %s:%u: %s", host.c_str(), port,
                      error.c_str());
        }
        return false;
    }
    if (addresses.empty()) {
        error = "no resolved addresses";
        return false;
    }

    const std::size_t start = addressOffset % addresses.size();
    const auto connectDeadline = std::chrono::steady_clock::now() +
                                 std::chrono::milliseconds(std::max(timeoutMs, 1));
    for (std::size_t attempt = 0; attempt < addresses.size(); ++attempt) {
        const auto remainingMs =
            std::chrono::duration_cast<std::chrono::milliseconds>(
                connectDeadline - std::chrono::steady_clock::now()).count();
        if (remainingMs <= 0) break;
        const std::size_t addressesRemaining = addresses.size() - attempt;
        const int perAddressTimeout = std::max(
            1, static_cast<int>(remainingMs / addressesRemaining));
        const std::size_t i = (start + attempt) % addresses.size();
        const int fd = ::socket(addresses[i].ss_family,
                                SOCK_STREAM | SOCK_CLOEXEC, IPPROTO_TCP);
        if (fd < 0) {
            error = std::string("socket: ") + std::strerror(errno);
            continue;
        }
        if (m_protector && !m_protector(fd)) {
            SKY_LOGW("SOCKS5: protect(fd=%d) failed", fd);
            ::close(fd);
            error = "VpnService.protect failed";
            continue;
        }

        if (!connectWithTimeout(fd,
                                reinterpret_cast<const sockaddr*>(&addresses[i]),
                                lengths[i], perAddressTimeout, error, &m_running)) {
            SKY_LOGD("SOCKS5: connect %s:%u failed: %s", sockaddrText(
                         reinterpret_cast<const sockaddr*>(&addresses[i])).c_str(),
                     port, error.c_str());
            ::close(fd);
            continue;
        }

        int nodelay = 1;
        ::setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));
        setIoTimeout(fd, kFirstPayloadTimeoutMs / 1000);

        outFd = fd;
        if (outAddr) std::memcpy(outAddr, &addresses[i], sizeof(sockaddr_storage));
        if (outAddrLen) *outAddrLen = lengths[i];
        return true;
    }

    if (error.empty()) error = "connection failed";
    if (m_running.load()) {
        emitEvent("[CONNECT-FAIL] %s:%u: %s", host.c_str(), port, error.c_str());
    }
    return false;
}

int Socks5Server::waitForInitialData(int clientFd, int remoteFd, int timeoutMs) {
    struct pollfd fds[2] = {
        {clientFd, POLLIN, 0},
        {remoteFd, POLLIN, 0}
    };

    int rc;
    do {
        rc = ::poll(fds, 2, timeoutMs);
    } while (rc < 0 && errno == EINTR);
    if (rc <= 0) return 0;

    if (fds[0].revents & (POLLIN | POLLHUP | POLLERR)) return 1;
    if (fds[1].revents & (POLLIN | POLLHUP | POLLERR)) return 2;
    return 0;
}

bool Socks5Server::readInitialPayload(int clientFd, std::vector<uint8_t>& payload,
                                      std::string& host, bool captureOpaqueHeader) {
    payload.clear();
    payload.reserve(4096);

    uint8_t buffer[16384];
    const ssize_t first = ::recv(clientFd, buffer, sizeof(buffer), 0);
    if (first <= 0) return false;
    payload.insert(payload.end(), buffer, buffer + first);

    for (int round = 0; round < 8 && payload.size() < kMaxFirstPayload; ++round) {
        PayloadInfo info = analyzePayload(payload.data(), payload.size());
        bool complete = false;
        if (info.kind == PayloadKind::TLS_CLIENT_HELLO) {
            if (payload.size() >= 5) {
                const std::size_t recordLen =
                    (static_cast<std::size_t>(payload[3]) << 8) | payload[4];
                complete = payload.size() >= 5 + recordLen;
            }
            if (!info.host.empty() && complete) break;
        } else if (info.kind == PayloadKind::HTTP_REQUEST) {
            if (!info.host.empty()) break;
            static const char kEnd[] = "\r\n\r\n";
            complete = std::search(payload.begin(), payload.end(),
                                   kEnd, kEnd + 4) != payload.end();
        } else if (captureOpaqueHeader && payload.size() < 64) {
            complete = false;
        } else if (payload.size() >= 9) {
            break;
        }
        if (complete) break;

        if (!waitReadable(clientFd, 30)) break;
        const ssize_t n = ::recv(clientFd, buffer, sizeof(buffer), 0);
        if (n <= 0) break;
        payload.insert(payload.end(), buffer, buffer + n);
    }

    const PayloadInfo finalInfo = analyzePayload(payload.data(), payload.size());
    if (!finalInfo.host.empty()) host = finalInfo.host;
    return !payload.empty();
}

bool Socks5Server::sendFirstPayload(int fd, const std::vector<uint8_t>& payload,
                                    const std::string& host,
                                    const StrategyChoice& choice,
                                    SendOutcome& outcome) {
    if (choice.direct || choice.tunnel || !choice.strategy) {
        if (choice.tunnel) {
            SKY_LOGW("SOCKS5: tunnel strategy is not available yet, using direct");
        }
        outcome.ok = sendAll(fd, payload.data(), payload.size());
        if (!outcome.ok) outcome.error = std::string("send: ") + std::strerror(errno);
        return outcome.ok;
    }

    DesyncEngine engine;
    outcome = engine.sendWithPlan(fd, payload.data(), payload.size(),
                                  choice.strategy->tcp, host, choice.ttl);
    return outcome.ok;
}

bool Socks5Server::waitForResponse(int fd, int timeoutMs, bool& readable,
                                   std::size_t& tlsHeaderBytes,
                                   std::string& error) {
    readable = false;
    tlsHeaderBytes = 0;
    const auto deadline = std::chrono::steady_clock::now() +
                          std::chrono::milliseconds(timeoutMs);

    while (std::chrono::steady_clock::now() < deadline) {
        if (!m_running.load()) {
            error = "stopped";
            return false;
        }
        const auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(
            deadline - std::chrono::steady_clock::now()).count();
        struct pollfd pfd{fd, POLLIN, 0};
        int rc;
        do {
            rc = ::poll(&pfd, 1, static_cast<int>(
                std::max<long long>(std::min<long long>(remaining, 100), 1)));
        } while (rc < 0 && errno == EINTR);
        if (rc == 0) continue;
        if (rc < 0) {
            error = std::string("poll: ") + std::strerror(errno);
            return false;
        }

        uint8_t header[6] = {};
        const ssize_t n = ::recv(fd, header, sizeof(header), MSG_PEEK | MSG_DONTWAIT);
        if (n > 0) {
            tlsHeaderBytes = static_cast<std::size_t>(n);
            if (n >= 5 && header[0] == 0x15) {
                char detail[96];
                std::snprintf(detail, sizeof(detail),
                              "server returned TLS alert (version=0x%02x%02x, length=%u)",
                              header[1], header[2],
                              static_cast<unsigned>((header[3] << 8) | header[4]));
                error = detail;
                return false;
            }
            if (n < static_cast<ssize_t>(sizeof(header))) {
                if (pfd.revents & (POLLHUP | POLLERR | POLLNVAL)) {
                    char detail[80];
                    std::snprintf(detail, sizeof(detail),
                                  "truncated TLS ServerHello (%zd/6 bytes)", n);
                    error = detail;
                    return false;
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
                continue;
            }
            if (header[0] != 0x16) {
                char detail[64];
                std::snprintf(detail, sizeof(detail),
                              "unexpected response byte=0x%02x", header[0]);
                error = detail;
                return false;
            }
            if (n >= 2 && header[1] != 0x03) {
                char detail[64];
                std::snprintf(detail, sizeof(detail),
                              "not a TLS record version=0x%02x%02x",
                              header[1], header[2]);
                error = detail;
                return false;
            }
            if (header[5] != 0x02) {
                char detail[64];
                std::snprintf(detail, sizeof(detail),
                              "unexpected TLS handshake message type=0x%02x",
                              header[5]);
                error = detail;
                return false;
            }
            readable = true;
            return true;
        }
        if (n == 0) {
            error = "connection closed";
            return false;
        }
        if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
            error = std::string("recv: ") + std::strerror(errno);
            return false;
        }
    }

    error = "response timeout";
    return false;
}

void Socks5Server::handleConnect(int clientFd, const Request& request) {
    const uint64_t requestId = g_nextTcpRequestId.fetch_add(1);
    [[maybe_unused]] TcpRequestLogScope requestLogScope(requestId);
    const std::string target = request.host + ':' + std::to_string(request.port);
    SKY_LOGI("SOCKS5: CONNECT %s", target.c_str());

    int remoteFd = -1;
    std::string error;
    sockaddr_storage remoteAddress{};
    socklen_t remoteAddressLen = 0;
    if (!connectRemote(request.host, request.port, remoteFd, &remoteAddress,
                       &remoteAddressLen, error)) {
        if (m_running.load()) {
            emitEvent("[PROBLEM] type=tcp_connect_failed host=%s port=%u reason=%s",
                      request.host.c_str(), request.port,
                      eventErrorCategory(error));
        }
        SKY_LOGW("SOCKS5: CONNECT %s failed: %s", target.c_str(), error.c_str());
        sendReply(clientFd, SOCKS5_REP_HOST_UNREACHABLE);
        return;
    }
    trackFd(remoteFd);
    auto connectedIp = [&remoteAddress, &remoteAddressLen]() {
        return remoteAddressLen == 0
            ? std::string("unknown")
            : sockaddrText(reinterpret_cast<const sockaddr*>(&remoteAddress));
    };

    if (!sendReply(clientFd, SOCKS5_REP_SUCCESS)) {
        closeTrackedFd(remoteFd);
        return;
    }

    const int firstSide = waitForInitialData(clientFd, remoteFd, 5000);
    if (firstSide != 1) {
        relay(clientFd, remoteFd);
        closeTrackedFd(remoteFd);
        return;
    }

    std::vector<uint8_t> payload;
    std::string payloadHost;
    if (!readInitialPayload(clientFd, payload, payloadHost,
                            isMtprotoPort(request.port) &&
                                isTelegramDcAddress(request.host))) {
        closeTrackedFd(remoteFd);
        return;
    }

    const bool mtprotoObfuscated = isLikelyObfuscatedMtproto(
        payload, request.host, request.port);

    const std::string host = payloadHost.empty() ? request.host : payloadHost;
    const std::string strategyScope = "tcp-" + std::to_string(request.port);
    const PayloadInfo info = analyzePayload(payload.data(), payload.size());
    const bool tlsClientHello = info.kind == PayloadKind::TLS_CLIENT_HELLO;
    const bool completeClientHello =
        tlsClientHello && info.recordEnd > 0 && payload.size() >= info.recordEnd;

    std::string cached;
    if (m_selector) cached = m_selector->cachedStrategy(host, strategyScope);

    std::vector<StrategyChoice> candidates;
    if (m_selector) {
        candidates = m_selector->candidates(host, false, strategyScope);
    }
    if (mtprotoObfuscated && m_selector) {
        StrategyChoice strategy = m_selector->findStrategy("mtproto-obfs-split");
        const bool explicitDirect = !candidates.empty() &&
            (candidates.front().direct ||
             (candidates.front().strategy && candidates.front().strategy->direct));
        if (strategy.strategy && !explicitDirect) {
            candidates.insert(candidates.begin(), std::move(strategy));
            emitEvent("[MTPROTO] obfuscated 64-byte handshake at %s:%u",
                      request.host.c_str(), request.port);
        }
    }
    if (candidates.empty()) {
        StrategyChoice direct;
        direct.direct = true;
        candidates.push_back(std::move(direct));
    }

    const std::size_t generatedCandidateCount = candidates.size();
    const std::size_t profileCandidateLimit =
        static_cast<std::size_t>(kMaxStrategyAttempts);
    if (candidates.size() > profileCandidateLimit) {
        std::vector<StrategyChoice> limited;
        std::vector<StrategyChoice> extraVariants;
        std::set<const StrategyDef*> seenStrategies;
        StrategyChoice directChoice;
        bool hasDirect = false;
        for (auto& choice : candidates) {
            if (choice.direct || (choice.strategy && choice.strategy->direct)) {
                if (!hasDirect) {
                    directChoice = choice;
                    hasDirect = true;
                }
                continue;
            }
            if (choice.strategy && seenStrategies.insert(choice.strategy).second &&
                limited.size() + 1 < profileCandidateLimit) {
                limited.push_back(choice);
            } else {
                extraVariants.push_back(choice);
            }
        }
        for (auto& choice : extraVariants) {
            if (limited.size() + 1 >= profileCandidateLimit) break;
            limited.push_back(std::move(choice));
        }
        if (hasDirect) {
            limited.push_back(std::move(directChoice));
        } else {
            StrategyChoice direct;
            direct.direct = true;
            limited.push_back(std::move(direct));
        }
        candidates.swap(limited);
    }
    const bool directOnly =
        candidates.size() == 1 &&
        (candidates.front().direct ||
         (candidates.front().strategy && candidates.front().strategy->direct));
    const bool logThis = !directOnly && shouldLogHost(host);

    if (logThis) {
        emitEvent("[TCP-PLAN] %s:%u remote_ip=%s generated=%zu candidates=%zu omitted_by_cap=%zu timeout_slots=%d response_timeout_ms=%d retry_budget_ms=%d cached=%s",
                  host.c_str(), request.port, connectedIp().c_str(),
                  generatedCandidateCount, candidates.size(),
                  generatedCandidateCount - candidates.size(),
                  kStrategyBudgetMs / kResponseTimeoutMs, kResponseTimeoutMs,
                  kStrategyBudgetMs,
                  cached.empty() ? "none" : cached.c_str());
    }

    const auto planStart = std::chrono::steady_clock::now();
    const auto elapsed = [&planStart]() {
        return static_cast<long>(std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - planStart).count());
    };
    const auto remainingBudget = [&elapsed]() {
        return static_cast<int>(std::max<long long>(
            0, static_cast<long long>(kStrategyBudgetMs) - elapsed()));
    };

    bool verified = false;
    bool lastSendOk = false;
    bool budgetExhausted = false;
    bool cancelled = false;
    std::size_t lastTlsHeaderBytes = 0;
    std::size_t attemptsTried = 0;
    std::string lastFailure;
    const char* lastFailureStage = "none";

    for (std::size_t i = 0; i < candidates.size(); ++i) {
        const StrategyChoice& choice = candidates[i];
        const std::string strategyId = choice.key();

        const int budgetMs = remainingBudget();
        if (budgetMs == 0) {
            budgetExhausted = true;
            emitEvent("[GIVEUP] %s:%u retry budget %d ms exhausted after %zu attempt(s)",
                      host.c_str(), request.port, kStrategyBudgetMs,
                      attemptsTried);
            break;
        }

        const std::size_t candidatesRemaining = candidates.size() - i;
        const int candidateBudgetMs = std::max(
            1, budgetMs / static_cast<int>(candidatesRemaining));
        const auto candidateDeadline = std::chrono::steady_clock::now() +
            std::chrono::milliseconds(candidateBudgetMs);
        const auto candidateRemainingMs = [&candidateDeadline]() {
            return static_cast<int>(std::max<long long>(
                0, std::chrono::duration_cast<std::chrono::milliseconds>(
                       candidateDeadline - std::chrono::steady_clock::now()).count()));
        };

        if (i > 0) {
            closeTrackedFd(remoteFd);
            remoteFd = -1;
            if (!m_running.load()) {
                cancelled = true;
                emitEvent("[CANCELLED] %s:%u stage=reconnect", host.c_str(),
                          request.port);
                break;
            }
            if (!connectRemote(request.host, request.port, remoteFd,
                               &remoteAddress, &remoteAddressLen, error, i,
                               std::min(kConnectTimeoutMs, candidateRemainingMs()))) {
                if (!m_running.load() || error == "stopped") {
                    cancelled = true;
                    emitEvent("[CANCELLED] %s:%u stage=reconnect", host.c_str(),
                              request.port);
                    break;
                }
                emitEvent("[FAIL] %s:%u via %s: reconnect failed: %s",
                          host.c_str(), request.port, strategyId.c_str(),
                          error.c_str());
                lastFailure = error;
                lastFailureStage = "reconnect";
                lastSendOk = false;
                continue;
            }
            trackFd(remoteFd);
        }

        const auto attemptStart = std::chrono::steady_clock::now();
        ++attemptsTried;
        if (logThis || i > 0) {
            const bool cachedAttempt = i == 0 && !cached.empty() && cached == strategyId;
            emitEvent("[TRY] %s:%u via %s remote_ip=%s (%s%zu/%zu)",
                      host.c_str(), request.port, strategyId.c_str(),
                      connectedIp().c_str(), cachedAttempt ? "cached, " : "",
                      attemptsTried, candidates.size());
        }

        SendOutcome outcome;
        const bool sent = sendFirstPayload(remoteFd, payload, host, choice, outcome);
        lastSendOk = sent;
        if (!sent) {
            const long ms = static_cast<long>(
                std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - attemptStart).count());
            if (!m_running.load() || outcome.error == "stopped") {
                cancelled = true;
                emitEvent("[CANCELLED] %s:%u stage=strategy_send",
                          host.c_str(), request.port);
                break;
            }
            emitEvent("[FAIL] %s:%u via %s remote_ip=%s: send failed: %s (%ld ms)",
                      host.c_str(), request.port, strategyId.c_str(),
                      connectedIp().c_str(), outcome.error.c_str(), ms);
            lastFailure = outcome.error;
            lastFailureStage = "strategy_send";
            SKY_LOGW("SOCKS5: strategy '%s' failed to send: %s",
                     strategyId.c_str(), outcome.error.c_str());
            if (m_selector && choice.strategy) {
                m_selector->markFailure(host, choice.key(), strategyScope);
            }
            continue;
        }

        if (!completeClientHello || directOnly) {
            if (logThis) {
                emitEvent("[SEND] %s:%u via %s (unverified first payload)",
                          host.c_str(), request.port, strategyId.c_str());
            }
            verified = false;
            break;
        }

        bool readable = false;
        std::size_t tlsHeaderBytes = 0;
        std::string responseError;
        if (waitForResponse(remoteFd, std::min(kResponseTimeoutMs,
                                               candidateRemainingMs()),
                            readable, tlsHeaderBytes, responseError)) {
            verified = true;
            const long ms = static_cast<long>(
                std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - attemptStart).count());
            if (m_selector) {
                m_selector->markSuccess(host, strategyId, ms, strategyScope);
            }
            const char* action = "split";
            if (choice.direct) {
                action = "direct";
            } else if (outcome.usedFake) {
                action = "fake";
            } else if (outcome.usedDisorder) {
                action = "disorder";
            } else if (outcome.usedTlsRec) {
                action = "tlsrec";
            }
            if (logThis || i > 0 || attemptsTried > 1) {
                emitEvent("[OK] %s:%u via %s remote_ip=%s (%ld ms, %s, TLS ServerHello observed)",
                          host.c_str(), request.port, strategyId.c_str(),
                          connectedIp().c_str(), ms, action);
            }
            SKY_LOGI("SOCKS5: %s via '%s' (%s, %ld ms)", target.c_str(),
                     strategyId.c_str(), action, ms);
            break;
        }

        if (!m_running.load() || responseError == "stopped") {
            cancelled = true;
            emitEvent("[CANCELLED] %s:%u stage=tls_response",
                      host.c_str(), request.port);
            break;
        }

        const long ms = static_cast<long>(
            std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - attemptStart).count());
        emitEvent("[FAIL] %s:%u via %s remote_ip=%s: %s (%ld ms)",
                  host.c_str(), request.port, strategyId.c_str(),
                  connectedIp().c_str(), responseError.c_str(), ms);
        lastFailure = responseError;
        lastFailureStage = "tls_response";
        lastTlsHeaderBytes = tlsHeaderBytes;
        SKY_LOGW("SOCKS5: %s via '%s' failed: %s", target.c_str(),
                 strategyId.c_str(), responseError.c_str());
        if (m_selector && choice.strategy) {
            m_selector->markFailure(host, choice.key(), strategyScope);
        }

        if (i + 1 >= candidates.size()) {
            SKY_LOGW("SOCKS5: all strategies failed for %s, keeping last connection",
                     target.c_str());
            break;
        }
    }

    if (cancelled || !m_running.load()) {
        if (remoteFd >= 0) closeTrackedFd(remoteFd);
        if (!cancelled) {
            emitEvent("[CANCELLED] %s:%u stage=shutdown", host.c_str(),
                      request.port);
        }
        return;
    }

    if (!verified && completeClientHello && !budgetExhausted && !directOnly) {
        emitEvent("[GIVEUP] %s:%u after %zu attempt(s) (%ld ms)", host.c_str(),
                  request.port, attemptsTried, elapsed());
    }
    if (budgetExhausted) {
        lastSendOk = false;
    }

    const char* directReplayStatus = "not_needed";
    if (!lastSendOk) {
        emitEvent("[DIRECT] %s:%u final replay without desync", host.c_str(),
                  request.port);
        closeTrackedFd(remoteFd);
        remoteFd = -1;
        if (connectRemote(request.host, request.port, remoteFd,
                          &remoteAddress, &remoteAddressLen, error, attemptsTried)) {
            trackFd(remoteFd);
            if (!sendAll(remoteFd, payload.data(), payload.size())) {
                if (!m_running.load()) {
                    emitEvent("[CANCELLED] %s:%u stage=direct_send",
                              host.c_str(), request.port);
                    closeTrackedFd(remoteFd);
                    return;
                }
                emitEvent("[FAIL] %s:%u final direct send: %s", host.c_str(),
                          request.port, std::strerror(errno));
                emitEvent("[PROBLEM] type=tcp_direct_replay_failed host=%s port=%u stage=send",
                          host.c_str(), request.port);
                SKY_LOGD("SOCKS5: final direct send failed: %s", std::strerror(errno));
                closeTrackedFd(remoteFd);
                return;
            }
            directReplayStatus = "sent_unverified";
            emitEvent("[DIRECT-RESULT] %s:%u remote_ip=%s status=sent_unverified",
                      host.c_str(), request.port, connectedIp().c_str());
        } else {
            if (!m_running.load() || error == "stopped") {
                emitEvent("[CANCELLED] %s:%u stage=direct_reconnect",
                          host.c_str(), request.port);
                return;
            }
            emitEvent("[FAIL] %s:%u final direct reconnect: %s", host.c_str(),
                      request.port, error.c_str());
            emitEvent("[PROBLEM] type=tcp_direct_replay_failed host=%s port=%u stage=connect reason=%s",
                      host.c_str(), request.port, eventErrorCategory(error));
            SKY_LOGW("SOCKS5: final reconnect to %s failed: %s",
                     target.c_str(), error.c_str());
            return;
        }
    }

    if (!verified && completeClientHello && !directOnly) {
        const char* reason = eventErrorCategory(lastFailure);
        const std::size_t untried = candidates.size() > attemptsTried
            ? candidates.size() - attemptsTried
            : 0;
        emitEvent("[PROBLEM] type=tls_unverified host=%s port=%u remote_ip=%s attempted=%zu candidates=%zu generated=%zu untried=%zu omitted_by_cap=%zu elapsed_ms=%ld stage=%s reason=%s tls_header_bytes=%zu retry_budget=%s direct_replay=%s",
                  host.c_str(), request.port, connectedIp().c_str(),
                  attemptsTried, candidates.size(), generatedCandidateCount,
                  untried, generatedCandidateCount - candidates.size(), elapsed(),
                  lastFailureStage, reason, lastTlsHeaderBytes,
                  budgetExhausted ? "exhausted" : "available",
                  directReplayStatus);
    }

    relay(clientFd, remoteFd, host, request.port, !completeClientHello,
          completeClientHello ? kRelayTunnelIdleTimeoutMs : kRelayIdleTimeoutMs);
    closeTrackedFd(remoteFd);
}

bool Socks5Server::createRemoteUdp(const std::string& host, uint16_t port,
                                   UdpPeer& peer, std::string& error) {
    std::vector<sockaddr_storage> addresses;
    std::vector<socklen_t> lengths;
    if (!resolve(host, port, SOCK_DGRAM, addresses, lengths, error)) return false;

    for (std::size_t i = 0; i < addresses.size(); ++i) {
        const int fd = ::socket(addresses[i].ss_family,
                                SOCK_DGRAM | SOCK_CLOEXEC, IPPROTO_UDP);
        if (fd < 0) {
            error = std::string("socket: ") + std::strerror(errno);
            continue;
        }
        if (m_protector && !m_protector(fd)) {
            SKY_LOGW("SOCKS5: protect(udp fd=%d) failed", fd);
            ::close(fd);
            error = "VpnService.protect failed";
            continue;
        }

        if (::connect(fd, reinterpret_cast<const sockaddr*>(&addresses[i]),
                      lengths[i]) < 0) {
            error = std::string("udp connect: ") + std::strerror(errno);
            ::close(fd);
            continue;
        }
        setNonBlocking(fd, true);
        peer.fd = fd;
        peer.host = host;
        peer.port = port;
        peer.key = udpKey(host, port);
        std::memcpy(&peer.addr, &addresses[i], sizeof(sockaddr_storage));
        peer.addrLen = lengths[i];
        peer.strategyKey.clear();
        peer.udpVerified = false;
        peer.lastFakeAt = {};
        peer.rxAtLastFake = 0;
        peer.txAtLastFake = 0;
        trackFd(fd);
        return true;
    }

    return false;
}

void Socks5Server::handleUdpAssociate(int clientFd, const Request& request) {
    (void)request;
    const int relayFd = ::socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, IPPROTO_UDP);
    if (relayFd < 0) {
        sendReply(clientFd, SOCKS5_REP_FAILURE);
        return;
    }
    trackFd(relayFd);

    struct sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = 0;
    if (::bind(relayFd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) < 0) {
        SKY_LOGE("SOCKS5: UDP relay bind failed: %s", std::strerror(errno));
        sendReply(clientFd, SOCKS5_REP_FAILURE);
        closeTrackedFd(relayFd);
        return;
    }

    socklen_t boundLen = sizeof(addr);
    ::getsockname(relayFd, reinterpret_cast<struct sockaddr*>(&addr), &boundLen);
    setNonBlocking(relayFd, true);

    if (!sendReply(clientFd, SOCKS5_REP_SUCCESS,
                   reinterpret_cast<struct sockaddr*>(&addr))) {
        emitEvent("[UDP-ASSOCIATE] failed to send relay address");
        closeTrackedFd(relayFd);
        return;
    }

    emitEvent("[UDP-ASSOCIATE] started relay=127.0.0.1:%u", ntohs(addr.sin_port));
    SKY_LOGI("SOCKS5: UDP ASSOCIATE relay on 127.0.0.1:%u", ntohs(addr.sin_port));
    relayUdp(clientFd, relayFd);
    closeTrackedFd(relayFd);
}

void Socks5Server::relayUdp(int clientFd, int relayFd) {
    std::map<std::string, UdpPeer> peers;
    sockaddr_storage clientAddr{};
    socklen_t clientAddrLen = 0;
    uint64_t totalSentPackets = 0;
    uint64_t totalReceivedPackets = 0;
    uint64_t totalSentBytes = 0;
    uint64_t totalReceivedBytes = 0;
    const bool blockQuic = m_selector && m_selector->quicFallbackEnabled();
    const auto fakeRetry = std::chrono::seconds(10);
    const auto fakeRefresh = std::chrono::seconds(30);

    std::vector<uint8_t> packet(65535);

    while (m_running.load()) {
        std::vector<struct pollfd> fds;
        fds.push_back({clientFd, POLLIN, 0});
        fds.push_back({relayFd, POLLIN, 0});
        std::vector<std::string> keys;
        keys.reserve(peers.size());
        for (auto& entry : peers) {
            fds.push_back({entry.second.fd, POLLIN, 0});
            keys.push_back(entry.first);
        }

        int rc;
        do {
            rc = ::poll(fds.data(), fds.size(), 1000);
        } while (rc < 0 && errno == EINTR);
        if (rc < 0) break;
        if (rc == 0) continue;
        if (!m_running.load()) break;

        if (fds[0].revents & (POLLIN | POLLHUP | POLLERR)) {
            uint8_t discard[512];
            const ssize_t n = ::recv(clientFd, discard, sizeof(discard), MSG_DONTWAIT);
            if (n == 0) {
                emitEvent("[UDP-ASSOCIATE] control connection closed");
                break;
            }
            if (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
                emitEvent("[UDP-ASSOCIATE] control read failed: %s", std::strerror(errno));
                break;
            }
        }

        if (fds[1].revents & POLLIN) {
            struct sockaddr_storage from{};
            socklen_t fromLen = sizeof(from);
            const ssize_t n = ::recvfrom(relayFd, packet.data(), packet.size(),
                                         MSG_DONTWAIT,
                                         reinterpret_cast<struct sockaddr*>(&from),
                                         &fromLen);
            if (n > 0 && isLoopback(reinterpret_cast<struct sockaddr*>(&from))) {
                std::memcpy(&clientAddr, &from, sizeof(from));
                clientAddrLen = fromLen;

                if (n >= 10) {
                    const uint8_t* data = packet.data();
                    if (data[0] == 0 && data[1] == 0 && data[2] == 0) {
                        const uint8_t atyp = data[3];
                        std::size_t offset = 4;
                        std::string host;
                        bool valid = false;
                        if (atyp == SOCKS5_ATYP_IPV4 && n >= 10) {
                            char text[INET_ADDRSTRLEN] = {};
                            ::inet_ntop(AF_INET, data + offset, text, sizeof(text));
                            host = text;
                            offset += 4;
                            valid = true;
                        } else if (atyp == SOCKS5_ATYP_IPV6 && n >= 22) {
                            char text[INET6_ADDRSTRLEN] = {};
                            ::inet_ntop(AF_INET6, data + offset, text, sizeof(text));
                            host = text;
                            offset += 16;
                            valid = true;
                        } else if (atyp == SOCKS5_ATYP_DOMAIN && n >= 5) {
                            const uint8_t length = data[offset++];
                            if (length > 0 && offset + length + 2 <= static_cast<std::size_t>(n)) {
                                host.assign(reinterpret_cast<const char*>(data + offset), length);
                                offset += length;
                                valid = true;
                            }
                        }

                        if (valid && offset + 2 <= static_cast<std::size_t>(n)) {
                            const uint16_t port =
                                static_cast<uint16_t>((data[offset] << 8) | data[offset + 1]);
                            offset += 2;
                            const std::string key = udpKey(host, port);
                            const uint8_t* payload = data + offset;
                            const std::size_t payloadLen =
                                static_cast<std::size_t>(n) - offset;
                            const bool quicInitial = isQuicInitial(payload, payloadLen);
                            const bool stunBindingRequest =
                                isStunBindingRequest(payload, payloadLen);

                            if (blockQuic && port == 443 && quicInitial) {
                                if (shouldLogHost("quic:" + host)) {
                                    emitEvent("[UDP-QUIC-BLOCK] %s forced TCP fallback",
                                              formatEndpoint(host, port).c_str());
                                }
                                continue;
                            }

                            auto it = peers.find(key);
                            if (it == peers.end()) {
                                if (peers.size() >= kMaxUdpPeers) {
                                    auto oldest = peers.begin();
                                    closeTrackedFd(oldest->second.fd);
                                    peers.erase(oldest);
                                }
                                UdpPeer peer;
                                std::string udpError;
                                if (createRemoteUdp(host, port, peer, udpError)) {
                                    it = peers.emplace(key, std::move(peer)).first;
                                    const char* family = it->second.addr.ss_family == AF_INET6
                                        ? "IPv6"
                                        : "IPv4";
                                    emitEvent("[UDP-PEER] opened %s family=%s",
                                              formatEndpoint(host, port).c_str(), family);
                                } else {
                                    emitEvent("[UDP-OPEN-FAIL] %s: %s",
                                              formatEndpoint(host, port).c_str(), udpError.c_str());
                                }
                            }

                            if (it != peers.end()) {
                                UdpPeer& peer = it->second;

                                const auto now = std::chrono::steady_clock::now();
                                const bool fakePort = port == 443 || port >= 1024;
                                const auto fakeInterval = peer.udpVerified
                                    ? fakeRefresh
                                    : fakeRetry;
                                const std::string strategyScope =
                                    "udp-" + std::to_string(port);
                                const bool fakeDue = peer.lastFakeAt.time_since_epoch().count() == 0 ||
                                    now - peer.lastFakeAt >= fakeInterval;
                                if (m_selector && fakePort && fakeDue &&
                                    (quicInitial || stunBindingRequest)) {
                                    if (!peer.strategyKey.empty() && !peer.udpVerified &&
                                        peer.receivedPackets == peer.rxAtLastFake &&
                                        peer.sentPackets > peer.txAtLastFake) {
                                        m_selector->markFailure(host, peer.strategyKey,
                                                                strategyScope);
                                        emitEvent("[UDP-FAIL] %s via %s",
                                                  formatEndpoint(host, port).c_str(),
                                                  peer.strategyKey.c_str());
                                        peer.strategyKey.clear();
                                    }

                                    const StrategyChoice choice =
                                        m_selector->select(host, true, strategyScope);
                                    if (choice.strategy && choice.strategy->udp.valid &&
                                        choice.strategy->udp.fakeCount > 0) {
                                        UdpPlan plan = choice.strategy->udp;
                                        if (prepareUdpFake(plan, quicInitial,
                                                           stunBindingRequest)) {
                                            DesyncEngine engine;
                                            const bool ok = engine.sendUdpFakes(
                                                peer.fd, plan, nullptr, 0, choice.ttl);
                                            if (!ok || shouldLogHost("udp:" + host)) {
                                                emitEvent("[UDP] %s via %s (%s)",
                                                          formatEndpoint(host, port).c_str(),
                                                          choice.key().c_str(),
                                                          ok ? "fake sent" : "fake failed");
                                            }
                                            peer.strategyKey = choice.key();
                                            peer.lastFakeAt = now;
                                            peer.rxAtLastFake = peer.receivedPackets;
                                            peer.txAtLastFake = peer.sentPackets;
                                            if (ok) {
                                                const auto jitterUs =
                                                    (peer.sentPackets * 7919u + 137u) % 3000u;
                                                std::this_thread::sleep_for(
                                                    std::chrono::microseconds(jitterUs));
                                            }
                                        }
                                    }
                                }

                                const ssize_t sent = ::send(peer.fd, payload, payloadLen,
                                                             MSG_NOSIGNAL);
                                if (sent >= 0 && static_cast<std::size_t>(sent) == payloadLen) {
                                    m_bytesIn.fetch_add(sent);
                                    ++peer.sentPackets;
                                    peer.sentBytes += static_cast<uint64_t>(sent);
                                    ++totalSentPackets;
                                    totalSentBytes += static_cast<uint64_t>(sent);
                                    if (peer.sentPackets == 1 ||
                                        now - peer.lastTxLog >= std::chrono::seconds(5)) {
                                        emitEvent("[UDP-TX] %s packets=%llu bytes=%llu last=%zu",
                                                  formatEndpoint(host, port).c_str(),
                                                  static_cast<unsigned long long>(peer.sentPackets),
                                                  static_cast<unsigned long long>(peer.sentBytes),
                                                  payloadLen);
                                        peer.lastTxLog = now;
                                    }
                                } else {
                                    const int sendError = sent < 0 ? errno : EIO;
                                    emitEvent("[UDP-TX-FAIL] %s: %s",
                                              formatEndpoint(host, port).c_str(),
                                              std::strerror(sendError));
                                    if (sendError == ECONNREFUSED) {
                                        closeTrackedFd(peer.fd);
                                        peers.erase(it);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        for (std::size_t i = 0; i < keys.size(); ++i) {
            const std::size_t index = i + 2;
            if (index >= fds.size()) break;
            if (!(fds[index].revents & (POLLIN | POLLERR | POLLHUP))) continue;

            auto it = peers.find(keys[i]);
            if (it == peers.end()) continue;
            UdpPeer& peer = it->second;

            struct sockaddr_storage from{};
            socklen_t fromLen = sizeof(from);
            const ssize_t n = ::recvfrom(peer.fd, packet.data(), packet.size(),
                                         MSG_DONTWAIT,
                                         reinterpret_cast<struct sockaddr*>(&from),
                                         &fromLen);
            if (n <= 0) {
                if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) {
                    continue;
                }
                emitEvent("[UDP-RX-FAIL] %s: %s",
                          formatEndpoint(peer.host, peer.port).c_str(),
                          n == 0 ? "empty datagram" : std::strerror(errno));
                closeTrackedFd(peer.fd);
                peers.erase(it);
                continue;
            }

            m_bytesOut.fetch_add(n);
            ++peer.receivedPackets;
            peer.receivedBytes += static_cast<uint64_t>(n);
            ++totalReceivedPackets;
            totalReceivedBytes += static_cast<uint64_t>(n);
            const auto now = std::chrono::steady_clock::now();
            if (!peer.udpVerified && !peer.strategyKey.empty() && m_selector) {
                peer.udpVerified = true;
                const long latencyMs = peer.lastFakeAt.time_since_epoch().count() == 0
                    ? -1
                    : static_cast<long>(std::chrono::duration_cast<std::chrono::milliseconds>(
                          now - peer.lastFakeAt).count());
                m_selector->markSuccess(
                    peer.host, peer.strategyKey, latencyMs,
                    "udp-" + std::to_string(peer.port));
            }
            if (peer.receivedPackets == 1 ||
                now - peer.lastRxLog >= std::chrono::seconds(5)) {
                emitEvent("[UDP-RX] %s packets=%llu bytes=%llu last=%zd",
                          formatEndpoint(peer.host, peer.port).c_str(),
                          static_cast<unsigned long long>(peer.receivedPackets),
                          static_cast<unsigned long long>(peer.receivedBytes), n);
                peer.lastRxLog = now;
            }
            if (clientAddrLen == 0) continue;

            const sockaddr* source =
                fromLen > 0 ? reinterpret_cast<const sockaddr*>(&from)
                            : reinterpret_cast<const sockaddr*>(&peer.addr);
            const std::vector<uint8_t> response =
                buildUdpResponse(source, packet.data(), static_cast<std::size_t>(n));
            const ssize_t relayed = ::sendto(
                relayFd, response.data(), response.size(), MSG_NOSIGNAL,
                reinterpret_cast<const struct sockaddr*>(&clientAddr), clientAddrLen);
            if (relayed < 0) {
                emitEvent("[UDP-RELAY-FAIL] to local client: %s", std::strerror(errno));
            }
        }
    }

    for (auto& entry : peers) {
        closeTrackedFd(entry.second.fd);
    }
    emitEvent("[UDP-ASSOCIATE] stopped peers=%zu tx_packets=%llu tx_bytes=%llu rx_packets=%llu rx_bytes=%llu",
              peers.size(), static_cast<unsigned long long>(totalSentPackets),
              static_cast<unsigned long long>(totalSentBytes),
              static_cast<unsigned long long>(totalReceivedPackets),
              static_cast<unsigned long long>(totalReceivedBytes));
}

void Socks5Server::relay(int clientFd, int remoteFd, const std::string& host,
                         uint16_t port, bool reportStream, int idleTimeoutMs) {
    bool clientOpen = true;
    bool remoteOpen = true;
    uint64_t clientToServer = 0;
    uint64_t serverToClient = 0;
    uint8_t buffer[16384];
    const int idleMs = idleTimeoutMs > 0 ? idleTimeoutMs : kRelayIdleTimeoutMs;

    while (m_running.load() && (clientOpen || remoteOpen)) {
        struct pollfd fds[2] = {
            {clientOpen ? clientFd : -1, POLLIN, 0},
            {remoteOpen ? remoteFd : -1, POLLIN, 0}
        };

        int rc;
        do {
            rc = ::poll(fds, 2, idleMs);
        } while (rc < 0 && errno == EINTR);
        if (rc == 0) break;
        if (rc < 0) break;
        if (!m_running.load()) break;

        if (clientOpen && (fds[0].revents & (POLLIN | POLLHUP | POLLERR))) {
            const ssize_t n = ::recv(clientFd, buffer, sizeof(buffer), 0);
            if (n > 0) {
                if (!sendAll(remoteFd, buffer, static_cast<std::size_t>(n))) break;
                clientToServer += static_cast<uint64_t>(n);
                m_bytesIn.fetch_add(n);
                m_bytesOut.fetch_add(n);
            } else {
                clientOpen = false;
                ::shutdown(remoteFd, SHUT_WR);
            }
        }

        if (remoteOpen && (fds[1].revents & (POLLIN | POLLHUP | POLLERR))) {
            const ssize_t n = ::recv(remoteFd, buffer, sizeof(buffer), 0);
            if (n > 0) {
                serverToClient += static_cast<uint64_t>(n);
                m_bytesOut.fetch_add(n);
                if (!sendAll(clientFd, buffer, static_cast<std::size_t>(n))) break;
                m_bytesIn.fetch_add(n);
            } else {
                remoteOpen = false;
                ::shutdown(clientFd, SHUT_WR);
            }
        }
    }

    if (reportStream) {
        emitEvent("[STREAM] %s:%u client_to_server=%llu server_to_client=%llu",
                  host.c_str(), port,
                  static_cast<unsigned long long>(clientToServer),
                  static_cast<unsigned long long>(serverToClient));
    }
}

}
