#ifndef TLS_UTILS_H
#define TLS_UTILS_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace skyunblock {

enum class PayloadKind {
    UNKNOWN,
    TLS_CLIENT_HELLO,
    HTTP_REQUEST
};

struct PayloadInfo {
    PayloadKind kind = PayloadKind::UNKNOWN;
    std::string host;
    std::size_t hostOffset = 0;
    std::size_t hostLength = 0;
    std::size_t recordEnd = 0;
    bool valid = false;
};

PayloadInfo analyzePayload(const std::uint8_t* data, std::size_t len);
bool resolvePosition(const std::string& spec, const PayloadInfo& info,
                     std::size_t len, std::size_t& out);

bool tlsRecordSplit(std::vector<std::uint8_t>& data, std::size_t pos);
std::vector<std::uint8_t> buildClientHello(const std::string& host);

std::string toLowerAscii(std::string value);

}

#endif
