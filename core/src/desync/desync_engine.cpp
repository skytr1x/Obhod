#include "desync_engine.h"

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <fcntl.h>
#include <functional>
#include <linux/sockios.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <thread>
#include <unistd.h>

#include "fake_payloads.h"
#include "sky_log.h"
#include "tls_utils.h"

namespace skyunblock {

namespace {

constexpr int kMaxFakeSize = 1600;

void sleepMs(int ms) {
    if (ms > 0) {
        std::this_thread::sleep_for(std::chrono::milliseconds(ms));
    }
}

bool waitUntilSent(int fd, int timeoutMs) {
    const auto deadline = std::chrono::steady_clock::now() +
                          std::chrono::milliseconds(timeoutMs);
    while (std::chrono::steady_clock::now() < deadline) {
        int pending = 0;
        if (::ioctl(fd, SIOCOUTQNSD, &pending) == 0 && pending == 0) {
            return true;
        }
        std::this_thread::sleep_for(std::chrono::microseconds(500));
    }
    return false;
}

}

DesyncEngine::DesyncEngine(int defaultTtl) : m_defaultTtl(defaultTtl > 0 ? defaultTtl : 64) {}

bool DesyncEngine::sendAll(int fd, const std::uint8_t* data, std::size_t len,
                           std::string& error) {
    std::size_t sent = 0;
    while (sent < len) {
        const ssize_t n = ::send(fd, data + sent, len - sent, MSG_NOSIGNAL);
        if (n < 0) {
            if (errno == EINTR) continue;
            error = std::string("send: ") + std::strerror(errno);
            return false;
        }
        if (n == 0) {
            error = "send: zero bytes";
            return false;
        }
        sent += static_cast<std::size_t>(n);
    }
    return true;
}

bool DesyncEngine::setNodelay(int fd, bool enabled) {
    const int flag = enabled ? 1 : 0;
    if (::setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &flag, sizeof(flag)) < 0) {
        SKY_LOGD("setsockopt(TCP_NODELAY=%d): %s", flag, std::strerror(errno));
        return false;
    }
    return true;
}

bool DesyncEngine::setTtl(int fd, int ttl) {
    int domain = AF_UNSPEC;
    socklen_t domainLen = sizeof(domain);
    if (::getsockopt(fd, SOL_SOCKET, SO_DOMAIN, &domain, &domainLen) < 0) {
        domain = AF_UNSPEC;
    }

    bool ok = false;
    int savedErrno = 0;
    if (domain != AF_INET6) {
        if (::setsockopt(fd, IPPROTO_IP, IP_TTL, &ttl, sizeof(ttl)) == 0) {
            ok = true;
        } else {
            savedErrno = errno;
        }
    }
    if (domain != AF_INET) {
        const int hops = ttl;
        if (::setsockopt(fd, IPPROTO_IPV6, IPV6_UNICAST_HOPS, &hops, sizeof(hops)) == 0) {
            ok = true;
        } else if (!savedErrno) {
            savedErrno = errno;
        }
    }

    if (!ok) {
        SKY_LOGD("setsockopt(TTL=%d): %s", ttl,
                 std::strerror(savedErrno ? savedErrno : ENOPROTOOPT));
    }
    return ok;
}

bool DesyncEngine::supportsFakeZeroCopy() {
    static std::atomic<int> cached{-1};
    const int value = cached.load(std::memory_order_relaxed);
    if (value >= 0) return value != 0;

    bool ok = false;
    const long page = ::sysconf(_SC_PAGESIZE);
    void* mem = ::mmap(nullptr, static_cast<std::size_t>(page), PROT_READ | PROT_WRITE,
                       MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mem != MAP_FAILED) {
        std::memset(mem, 0, static_cast<std::size_t>(page));
        int pipeFds[2] = {-1, -1};
        if (::pipe(pipeFds) == 0) {
            struct iovec iov{mem, 1};
            const ssize_t n = ::vmsplice(pipeFds[1], &iov, 1, SPLICE_F_GIFT);
            ok = (n == 1);
            ::close(pipeFds[0]);
            ::close(pipeFds[1]);
        }
        ::munmap(mem, static_cast<std::size_t>(page));
    }
    cached.store(ok ? 1 : 0, std::memory_order_relaxed);
    SKY_LOGI("Desync: zero-copy fake support: %s", ok ? "yes" : "no");
    return ok;
}

std::vector<std::uint8_t> DesyncEngine::fakePayload(
        const std::string& name, const std::vector<std::uint8_t>& original) {
    if (name == "orig" || name == "original") return original;
    if (name == "tls_4pda" || name == "4pda") {
        return std::vector<std::uint8_t>(kTls4pda, kTls4pda + kTls4pdaLen);
    }
    if (name == "quic_google" || name == "quic") {
        return std::vector<std::uint8_t>(kQuicGoogle, kQuicGoogle + kQuicGoogleLen);
    }
    if (name == "stun") {
        return std::vector<std::uint8_t>(kStun, kStun + kStunLen);
    }
    if (name == "zero") {
        return std::vector<std::uint8_t>(kMaxFakeSize, 0);
    }
    return std::vector<std::uint8_t>(kTlsGoogle, kTlsGoogle + kTlsGoogleLen);
}

bool DesyncEngine::sendWithLowTtl(int fd, const std::uint8_t* data, std::size_t len,
                                  int ttl, int restoreTtl) {
    if (!setTtl(fd, ttl)) return false;
    std::string error;
    const bool ok = sendAll(fd, data, len, error);
    setTtl(fd, restoreTtl);
    if (!ok) {
        SKY_LOGW("Desync disorder chunk failed: %s", error.c_str());
    }
    return ok;
}

bool DesyncEngine::sendFakePrefix(int fd, const std::uint8_t* fake, std::size_t fakeLen,
                                  const std::uint8_t* original, std::size_t originalLen,
                                  std::size_t chunkLen, int ttl, int restoreTtl,
                                  bool& fatal) {
    fatal = false;
    if (chunkLen == 0) return true;

    const long pageSize = ::sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) return false;
    const std::size_t allocSize =
        ((chunkLen + static_cast<std::size_t>(pageSize) - 1) /
         static_cast<std::size_t>(pageSize)) * static_cast<std::size_t>(pageSize);

    void* mem = ::mmap(nullptr, allocSize, PROT_READ | PROT_WRITE,
                       MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mem == MAP_FAILED) {
        SKY_LOGD("Desync fake: mmap failed: %s", std::strerror(errno));
        return false;
    }
    std::memset(mem, 0, allocSize);
    if (fake && fakeLen > 0) {
        std::memcpy(mem, fake, std::min(fakeLen, chunkLen));
    }

    int pipeFds[2] = {-1, -1};
    if (::pipe(pipeFds) != 0) {
        ::munmap(mem, allocSize);
        return false;
    }

    const int pipeSize = ::fcntl(pipeFds[1], F_GETPIPE_SZ);
    if (pipeSize > 0 && chunkLen > static_cast<std::size_t>(pipeSize)) {
        ::close(pipeFds[0]);
        ::close(pipeFds[1]);
        ::munmap(mem, allocSize);
        return false;
    }

    struct iovec iov{mem, chunkLen};
    const ssize_t queued = ::vmsplice(pipeFds[1], &iov, 1, SPLICE_F_GIFT);
    if (queued != static_cast<ssize_t>(chunkLen)) {
        SKY_LOGD("Desync fake: vmsplice failed: %s", std::strerror(errno));
        ::close(pipeFds[0]);
        ::close(pipeFds[1]);
        ::munmap(mem, allocSize);
        return false;
    }

    if (!setTtl(fd, ttl)) {
        ::close(pipeFds[0]);
        ::close(pipeFds[1]);
        ::munmap(mem, allocSize);
        return false;
    }

    std::size_t sent = 0;
    bool ok = true;
    while (sent < chunkLen) {
        const ssize_t n = ::splice(pipeFds[0], nullptr, fd, nullptr,
                                   chunkLen - sent, SPLICE_F_MOVE);
        if (n < 0) {
            if (errno == EINTR) continue;
            SKY_LOGD("Desync fake: splice failed after %zu bytes: %s",
                     sent, std::strerror(errno));
            ok = false;
            if (sent > 0) fatal = true;
            break;
        }
        if (n == 0) {
            ok = false;
            if (sent > 0) fatal = true;
            break;
        }
        sent += static_cast<std::size_t>(n);
    }

    waitUntilSent(fd, 50);

    if (sent > 0) {
        const std::size_t copyLen = std::min(sent, originalLen);
        if (copyLen > 0) std::memcpy(mem, original, copyLen);
    }

    setTtl(fd, restoreTtl);
    ::close(pipeFds[0]);
    ::close(pipeFds[1]);
    ::munmap(mem, allocSize);
    return ok && sent == chunkLen;
}

SendOutcome DesyncEngine::sendWithPlan(int fd, const std::uint8_t* data, std::size_t len,
                                       const TcpPlan& plan, const std::string& host,
                                       int ttlOverride) {
    SendOutcome outcome;
    if (!data || len == 0) {
        outcome.ok = true;
        return outcome;
    }

    if (plan.tcpNodelay) setNodelay(fd, true);

    const int restoreTtl = plan.defaultTtl > 0 ? plan.defaultTtl : m_defaultTtl;
    const PayloadInfo info = analyzePayload(data, len);
    std::vector<std::uint8_t> buffer(data, data + len);
    const std::size_t originalLen = buffer.size();

    std::vector<std::size_t> tlsSplits;
    if (info.kind == PayloadKind::TLS_CLIENT_HELLO) {
        for (const auto& action : plan.actions) {
            if (action.type != ActionType::TLS_REC) continue;
            std::size_t pos = 0;
            if (!resolvePosition(action.pos, info, originalLen, pos)) continue;
            if (pos <= 5 || pos >= info.recordEnd) continue;
            tlsSplits.push_back(pos);
        }
        std::sort(tlsSplits.begin(), tlsSplits.end(), std::greater<std::size_t>());
        for (std::size_t pos : tlsSplits) {
            if (tlsRecordSplit(buffer, pos)) {
                outcome.usedTlsRec = true;
            }
        }
    }

    auto adjustForTlsRec = [&tlsSplits, originalLen](std::size_t pos) {
        std::size_t adjusted = pos;
        for (std::size_t split : tlsSplits) {
            if (split <= pos) adjusted += 5;
        }
        if (adjusted > originalLen + tlsSplits.size() * 5) {
            adjusted = originalLen + tlsSplits.size() * 5;
        }
        return adjusted;
    };

    std::size_t lp = 0;
    for (const auto& action : plan.actions) {
        if (action.type == ActionType::TLS_REC) continue;

        std::size_t pos = 0;
        if (!resolvePosition(action.pos, info, originalLen, pos)) {
            SKY_LOGD("Desync: cannot resolve position '%s' for host '%s'",
                     action.pos.c_str(), host.c_str());
            continue;
        }
        pos = adjustForTlsRec(pos);
        if (pos > buffer.size()) pos = buffer.size();
        if (pos <= lp) continue;

        switch (action.type) {
            case ActionType::SPLIT: {
                std::string error;
                if (!sendAll(fd, buffer.data() + lp, pos - lp, error)) {
                    outcome.error = error;
                    outcome.fatal = true;
                    return outcome;
                }
                if (!waitUntilSent(fd, 50)) {
                    SKY_LOGD("Desync split boundary was not confirmed for '%s'", host.c_str());
                }
                lp = pos;
                break;
            }

            case ActionType::DISORDER: {
                if (!sendWithLowTtl(fd, buffer.data() + lp, pos - lp, 1, restoreTtl)) {
                    outcome.error = "disorder send failed";
                    outcome.fatal = true;
                    return outcome;
                }
                outcome.usedDisorder = true;
                lp = pos;
                break;
            }

            case ActionType::FAKE: {
                const std::size_t chunkLen = pos - lp;
                if (info.kind == PayloadKind::TLS_CLIENT_HELLO &&
                    supportsFakeZeroCopy()) {
                    std::vector<std::uint8_t> originalChunk(buffer.begin() + lp,
                                                            buffer.begin() + pos);
                    const std::vector<std::uint8_t> fake =
                        fakePayload(action.payload, originalChunk);
                    const int fakeTtl = ttlOverride > 0
                                            ? ttlOverride
                                            : (action.ttl > 0 ? action.ttl : 8);
                    bool fatal = false;
                    if (sendFakePrefix(fd, fake.data(), fake.size(),
                                       originalChunk.data(), originalChunk.size(),
                                       chunkLen, fakeTtl, restoreTtl, fatal)) {
                        outcome.usedFake = true;
                        lp = pos;
                        break;
                    }
                    if (fatal) {
                        outcome.error = "fake send failed";
                        outcome.fatal = true;
                        return outcome;
                    }
                    SKY_LOGD("Desync: fake unavailable for '%s', using disorder",
                             host.c_str());
                }

                if (!sendWithLowTtl(fd, buffer.data() + lp, pos - lp, 1, restoreTtl)) {
                    outcome.error = "fake fallback send failed";
                    outcome.fatal = true;
                    return outcome;
                }
                outcome.usedDisorder = true;
                lp = pos;
                break;
            }

            case ActionType::OOB: {
                const std::size_t oobLen = std::min<std::size_t>(pos - lp + 1,
                                                                 buffer.size() - lp);
                const ssize_t n = ::send(fd, buffer.data() + lp, oobLen, MSG_OOB | MSG_NOSIGNAL);
                if (n < 0) {
                    std::string error;
                    if (!sendAll(fd, buffer.data() + lp, pos - lp, error)) {
                        outcome.error = error;
                        outcome.fatal = true;
                        return outcome;
                    }
                }
                lp = std::min(pos + 1, buffer.size());
                break;
            }

            case ActionType::TLS_REC:
                break;
        }

        sleepMs(action.delayMs);
    }

    if (lp < buffer.size()) {
        std::string error;
        if (!sendAll(fd, buffer.data() + lp, buffer.size() - lp, error)) {
            outcome.error = error;
            outcome.fatal = true;
            return outcome;
        }
    }

    outcome.ok = true;
    return outcome;
}

bool DesyncEngine::sendUdpFakes(int fd, const UdpPlan& plan,
                                const struct sockaddr* destination,
                                socklen_t destinationLen, int ttlOverride) {
    if (plan.fakeCount <= 0) return true;
    const std::vector<std::uint8_t> payload = fakePayload(plan.payload, {});
    if (payload.empty()) return false;

    const int fakeTtl = ttlOverride > 0 ? ttlOverride
                                        : (plan.ttl > 0 ? plan.ttl : 8);
    if (!setTtl(fd, fakeTtl)) return false;
    bool ok = true;
    int sendError = 0;
    for (int i = 0; i < plan.fakeCount; ++i) {
        ssize_t n = -1;
        do {
            n = destination
                ? ::sendto(fd, payload.data(), payload.size(), MSG_NOSIGNAL,
                           destination, destinationLen)
                : ::send(fd, payload.data(), payload.size(), MSG_NOSIGNAL);
        } while (n < 0 && errno == EINTR);

        if (n < 0 || static_cast<std::size_t>(n) != payload.size()) {
            ok = false;
            sendError = n < 0 ? errno : EIO;
            break;
        }
    }
    if (!ok) {
        SKY_LOGD("Desync UDP fake send failed: %s", std::strerror(sendError));
    }
    const bool restored = setTtl(fd, m_defaultTtl);
    if (!restored) {
        SKY_LOGW("Desync UDP fake could not restore TTL=%d", m_defaultTtl);
    }
    return ok && restored;
}

}
