#ifndef DESYNC_ENGINE_H
#define DESYNC_ENGINE_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <sys/socket.h>
#include <vector>

#include "profile.h"

namespace skyunblock {

struct SendOutcome {
    bool ok = false;
    bool usedFake = false;
    bool usedDisorder = false;
    bool usedTlsRec = false;
    bool fatal = false;
    std::string error;
};

class DesyncEngine {
public:
    explicit DesyncEngine(int defaultTtl = 64);

    SendOutcome sendWithPlan(int fd, const std::uint8_t* data, std::size_t len,
                             const TcpPlan& plan, const std::string& host,
                             int ttlOverride = 0);

    bool sendUdpFakes(int fd, const UdpPlan& plan,
                      const struct sockaddr* destination, socklen_t destinationLen,
                      int ttlOverride = 0);

    static bool supportsFakeZeroCopy();
    static std::vector<std::uint8_t> fakePayload(const std::string& name,
                                                 const std::vector<std::uint8_t>& original);

private:
    bool sendAll(int fd, const std::uint8_t* data, std::size_t len, std::string& error);
    bool setNodelay(int fd, bool enabled);
    bool setTtl(int fd, int ttl);
    bool sendFakePrefix(int fd, const std::uint8_t* fake, std::size_t fakeLen,
                        const std::uint8_t* original, std::size_t originalLen,
                        std::size_t chunkLen, int ttl, int restoreTtl,
                        bool& fatal);
    bool sendWithLowTtl(int fd, const std::uint8_t* data, std::size_t len,
                        int ttl, int restoreTtl);

    int m_defaultTtl;
};

}

#endif
