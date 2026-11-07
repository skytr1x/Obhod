#include "prober.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cerrno>
#include <chrono>
#include <cstring>
#include <sstream>
#include <vector>

#include "desync_engine.h"
#include "json.h"
#include "sky_log.h"
#include "tls_utils.h"

namespace skyunblock {

namespace {

using Clock = std::chrono::steady_clock;

long elapsedMs(Clock::time_point start) {
    return static_cast<long>(
        std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count());
}

struct ResolvedAddress {
    sockaddr_storage storage{};
    socklen_t length = 0;
    std::string text;
};

std::string sockaddrText(const sockaddr* addr) {
    char host[INET6_ADDRSTRLEN] = {};
    if (addr->sa_family == AF_INET) {
        const auto* v4 = reinterpret_cast<const sockaddr_in*>(addr);
        ::inet_ntop(AF_INET, &v4->sin_addr, host, sizeof(host));
    } else if (addr->sa_family == AF_INET6) {
        const auto* v6 = reinterpret_cast<const sockaddr_in6*>(addr);
        ::inet_ntop(AF_INET6, &v6->sin6_addr, host, sizeof(host));
    }
    return host;
}

bool isNumericHost(const std::string& host) {
    in_addr v4{};
    in6_addr v6{};
    return ::inet_pton(AF_INET, host.c_str(), &v4) == 1 ||
           ::inet_pton(AF_INET6, host.c_str(), &v6) == 1;
}

bool resolve(const std::string& host, int port, std::vector<ResolvedAddress>& out,
             std::string& error) {
    struct addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;
    if (isNumericHost(host)) hints.ai_flags = AI_NUMERICHOST;

    struct addrinfo* result = nullptr;
    const std::string service = std::to_string(port);
    const int rc = ::getaddrinfo(host.c_str(), service.c_str(), &hints, &result);
    if (rc != 0) {
        error = std::string("getaddrinfo: ") + ::gai_strerror(rc);
        return false;
    }

    for (const struct addrinfo* ai = result; ai != nullptr; ai = ai->ai_next) {
        if (ai->ai_family != AF_INET && ai->ai_family != AF_INET6) continue;
        ResolvedAddress address;
        std::memcpy(&address.storage, ai->ai_addr, ai->ai_addrlen);
        address.length = static_cast<socklen_t>(ai->ai_addrlen);
        address.text = sockaddrText(ai->ai_addr);
        out.push_back(std::move(address));
    }
    ::freeaddrinfo(result);

    if (out.empty()) {
        error = "no addresses";
        return false;
    }
    return true;
}

bool setNonBlocking(int fd) {
    const int flags = ::fcntl(fd, F_GETFL, 0);
    if (flags < 0) return false;
    return ::fcntl(fd, F_SETFL, flags | O_NONBLOCK) == 0;
}

bool connectWithTimeout(int fd, const sockaddr* addr, socklen_t len, int timeoutMs,
                        std::string& error) {
    if (!setNonBlocking(fd)) {
        error = std::string("fcntl: ") + std::strerror(errno);
        return false;
    }

    int rc = ::connect(fd, addr, len);
    if (rc == 0) return true;
    if (errno != EINPROGRESS) {
        error = std::string("connect: ") + std::strerror(errno);
        return false;
    }

    struct pollfd pfd{fd, POLLOUT, 0};
    rc = ::poll(&pfd, 1, timeoutMs);
    if (rc == 0) {
        error = "connect timeout";
        return false;
    }
    if (rc < 0) {
        error = std::string("poll: ") + std::strerror(errno);
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
    return true;
}

bool setBlocking(int fd) {
    const int flags = ::fcntl(fd, F_GETFL, 0);
    if (flags < 0) return false;
    return ::fcntl(fd, F_SETFL, flags & ~O_NONBLOCK) == 0;
}

bool waitForTlsResponse(int fd, int timeoutMs, int& bytesRead, std::string& error) {
    struct pollfd pfd{fd, POLLIN, 0};
    const int rc = ::poll(&pfd, 1, timeoutMs);
    if (rc == 0) {
        error = "response timeout";
        return false;
    }
    if (rc < 0) {
        error = std::string("poll: ") + std::strerror(errno);
        return false;
    }

    std::uint8_t header[5] = {};
    ssize_t n = ::recv(fd, header, sizeof(header), MSG_PEEK | MSG_DONTWAIT);
    if (n <= 0) {
        error = n == 0 ? "connection closed" : std::string("recv: ") + std::strerror(errno);
        return false;
    }

    bytesRead = static_cast<int>(n);
    if (n < 1) {
        error = "empty response";
        return false;
    }
    if (header[0] == 0x15) {
        error = "server returned a TLS alert";
        return false;
    }
    if (header[0] != 0x16) {
        std::ostringstream ss;
        ss << "unexpected first byte 0x" << std::hex << static_cast<int>(header[0]);
        error = ss.str();
        return false;
    }
    if (n >= 3 && header[1] != 0x03) {
        error = "not a TLS record";
        return false;
    }
    return true;
}

}

Prober::Prober(StrategySelector* selector, ProtectorCallback protector,
               int connectTimeoutMs, int responseTimeoutMs, EventCallback event)
    : m_selector(selector),
      m_protector(std::move(protector)),
      m_connectTimeoutMs(connectTimeoutMs > 0 ? connectTimeoutMs : 5000),
      m_responseTimeoutMs(responseTimeoutMs > 0 ? responseTimeoutMs : 7000),
      m_event(std::move(event)) {}

ProbeResult Prober::probe(const std::string& host, int port) {
    ProbeResult result;
    result.host = host;
    result.port = port;

    const auto dnsStart = Clock::now();
    std::vector<ResolvedAddress> addresses;
    std::string error;
    result.dnsOk = resolve(host, port, addresses, error);
    result.dnsMs = elapsedMs(dnsStart);
    if (!result.dnsOk) {
        ProbeAttempt attempt;
        attempt.strategy = "direct";
        attempt.error = error;
        result.attempts.push_back(std::move(attempt));
        return result;
    }
    for (const auto& address : addresses) {
        if (!result.address.empty()) result.address += ",";
        result.address += address.text;
    }

    const std::vector<std::uint8_t> hello = buildClientHello(host);
    std::vector<StrategyChoice> choices;
    if (m_selector) {
        choices = m_selector->candidates(host, false);
    }
    if (choices.empty()) {
        StrategyChoice direct;
        direct.direct = true;
        choices.push_back(std::move(direct));
    }

    auto report = [&](const ProbeAttempt& attempt) {
        if (!m_event) return;
        std::ostringstream line;
        line << (attempt.success ? "[PROBE-OK] " : "[PROBE-FAIL] ")
             << host << ':' << port << " via " << attempt.strategy;
        if (attempt.success) {
            line << " (" << (attempt.connectMs + attempt.responseMs) << " ms)";
        } else if (!attempt.error.empty()) {
            line << ": " << attempt.error;
        }
        m_event(line.str());
    };

    for (const auto& choice : choices) {
        ProbeAttempt attempt;
        attempt.strategy = choice.key();

        if (m_event) {
            m_event("[PROBE] " + host + ":" + std::to_string(port) +
                    " via " + attempt.strategy);
        }

        const auto attemptStart = Clock::now();
        int fd = -1;
        bool connected = false;

        for (const auto& address : addresses) {
            const int candidate = ::socket(address.storage.ss_family,
                                           SOCK_STREAM | SOCK_CLOEXEC, IPPROTO_TCP);
            if (candidate < 0) {
                attempt.error = std::string("socket: ") + std::strerror(errno);
                continue;
            }
            if (m_protector) m_protector(candidate);

            std::string connectError;
            if (connectWithTimeout(candidate, reinterpret_cast<const sockaddr*>(
                                                   &address.storage),
                                   address.length, m_connectTimeoutMs, connectError)) {
                fd = candidate;
                connected = true;
                break;
            }
            attempt.error = connectError;
            ::close(candidate);
        }

        attempt.connectMs = elapsedMs(attemptStart);
        attempt.tcpConnected = connected;
        if (!connected) {
            if (attempt.error.empty()) attempt.error = "connect failed";
            report(attempt);
            result.attempts.push_back(std::move(attempt));
            continue;
        }

        if (!setBlocking(fd)) {
            attempt.error = std::string("fcntl: ") + std::strerror(errno);
            ::close(fd);
            report(attempt);
            result.attempts.push_back(std::move(attempt));
            continue;
        }

        const auto sendStart = Clock::now();
        bool sent = false;
        if (choice.direct || choice.tunnel || !choice.strategy) {
            std::size_t offset = 0;
            sent = true;
            while (offset < hello.size()) {
                const ssize_t n = ::send(fd, hello.data() + offset, hello.size() - offset,
                                         MSG_NOSIGNAL);
                if (n <= 0) {
                    attempt.error = n == 0 ? "send: zero bytes"
                                           : std::string("send: ") + std::strerror(errno);
                    sent = false;
                    break;
                }
                offset += static_cast<std::size_t>(n);
            }
        } else {
            DesyncEngine engine;
            const SendOutcome outcome = engine.sendWithPlan(fd, hello.data(), hello.size(),
                                                            choice.strategy->tcp, host,
                                                            choice.ttl);
            sent = outcome.ok;
            if (!sent) attempt.error = outcome.error.empty() ? "desync send failed" : outcome.error;
        }

        if (!sent) {
            ::close(fd);
            report(attempt);
            result.attempts.push_back(std::move(attempt));
            continue;
        }

        int bytes = 0;
        std::string responseError;
        const bool ok = waitForTlsResponse(fd, m_responseTimeoutMs, bytes, responseError);
        attempt.responseMs = elapsedMs(sendStart);
        attempt.responseBytes = bytes;
        attempt.tlsResponse = ok;
        attempt.success = ok;
        if (!ok) attempt.error = responseError;
        ::close(fd);

        if (ok && !result.success) {
            result.success = true;
            result.strategy = attempt.strategy;
            result.latencyMs = attempt.connectMs + attempt.responseMs;
            if (m_selector && choice.strategy) {
                m_selector->markSuccess(host, choice.key(),
                                        attempt.connectMs + attempt.responseMs);
            }
        } else if (!ok && m_selector && choice.strategy) {
            m_selector->markFailure(host, choice.key());
        }

        SKY_LOGI("Probe %s:%d via %s: %s (%ld ms)%s%s", host.c_str(), port,
                 attempt.strategy.c_str(), ok ? "ok" : "failed",
                 attempt.connectMs + attempt.responseMs,
                 attempt.error.empty() ? "" : " - ", attempt.error.c_str());

        report(attempt);
        result.attempts.push_back(std::move(attempt));
    }

    if (!result.success && result.strategy.empty() && !result.attempts.empty()) {
        result.strategy = "none";
    }
    return result;
}

std::string Prober::probeJson(const std::string& host, int port) {
    return resultToJson(probe(host, port));
}

std::string Prober::resultToJson(const ProbeResult& result) {
    std::ostringstream ss;
    ss << "{\"host\":\"" << jsonEscape(result.host) << "\""
       << ",\"port\":" << result.port
       << ",\"dns_ok\":" << (result.dnsOk ? "true" : "false")
       << ",\"address\":\"" << jsonEscape(result.address) << "\""
       << ",\"dns_ms\":" << result.dnsMs
       << ",\"success\":" << (result.success ? "true" : "false")
       << ",\"strategy\":\"" << jsonEscape(result.strategy) << "\""
       << ",\"latency_ms\":" << result.latencyMs
       << ",\"attempts\":[";
    for (std::size_t i = 0; i < result.attempts.size(); ++i) {
        const ProbeAttempt& attempt = result.attempts[i];
        if (i) ss << ',';
        ss << "{\"strategy\":\"" << jsonEscape(attempt.strategy) << "\""
           << ",\"tcp_connected\":" << (attempt.tcpConnected ? "true" : "false")
           << ",\"tls_response\":" << (attempt.tlsResponse ? "true" : "false")
           << ",\"success\":" << (attempt.success ? "true" : "false")
           << ",\"connect_ms\":" << attempt.connectMs
           << ",\"response_ms\":" << attempt.responseMs
           << ",\"response_bytes\":" << attempt.responseBytes
           << ",\"error\":";
        if (attempt.error.empty()) ss << "null";
        else ss << '"' << jsonEscape(attempt.error) << '"';
        ss << '}';
    }
    ss << "]}";
    return ss.str();
}

}
