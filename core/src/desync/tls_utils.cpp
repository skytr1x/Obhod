#include "tls_utils.h"

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstdlib>
#include <cstring>

#include "sky_log.h"

namespace skyunblock {

namespace {

std::uint16_t readU16(const std::uint8_t* p) {
    return static_cast<std::uint16_t>((static_cast<std::uint16_t>(p[0]) << 8) | p[1]);
}

bool isHttpMethod(const std::uint8_t* data, std::size_t len) {
    static const char* methods[] = {
        "GET ", "POST ", "HEAD ", "PUT ", "DELETE ", "OPTIONS ", "PATCH ", "CONNECT "
    };
    for (const char* m : methods) {
        const std::size_t n = std::strlen(m);
        if (len >= n && std::memcmp(data, m, n) == 0) return true;
    }
    return false;
}

bool iequals(const char* a, const char* b, std::size_t n) {
    for (std::size_t i = 0; i < n; ++i) {
        if (std::tolower(static_cast<unsigned char>(a[i])) !=
            std::tolower(static_cast<unsigned char>(b[i]))) {
            return false;
        }
    }
    return true;
}

PayloadInfo parseHttp(const std::uint8_t* data, std::size_t len) {
    PayloadInfo info;
    info.kind = PayloadKind::HTTP_REQUEST;

    const std::size_t scan = std::min<std::size_t>(len, 8192);
    std::size_t hostLine = std::string::npos;
    std::size_t lineStart = 0;

    for (std::size_t i = 0; i + 1 < scan; ++i) {
        if (data[i] == '\n') {
            lineStart = i + 1;
            break;
        }
    }
    if (lineStart == 0) return info;

    for (std::size_t i = lineStart; i < scan;) {
        std::size_t lineEnd = i;
        while (lineEnd < scan && data[lineEnd] != '\n') ++lineEnd;
        std::size_t contentEnd = lineEnd;
        if (contentEnd > i && data[contentEnd - 1] == '\r') --contentEnd;
        if (contentEnd == i) break; // empty line: end of headers

        if (contentEnd - i >= 5 && iequals(reinterpret_cast<const char*>(data + i), "Host:", 5)) {
            hostLine = i + 5;
            break;
        }
        i = lineEnd + 1;
        if (lineEnd >= scan) break;
    }

    if (hostLine == std::string::npos) return info;

    std::size_t p = hostLine;
    while (p < scan && (data[p] == ' ' || data[p] == '\t')) ++p;
    std::size_t e = p;
    while (e < scan && data[e] != '\r' && data[e] != '\n' && data[e] != ' ' && data[e] != '\t') ++e;
    if (e <= p) return info;

    info.host.assign(reinterpret_cast<const char*>(data + p), e - p);
    info.hostOffset = p;
    info.hostLength = e - p;

    const std::size_t colon = info.host.find(':');
    if (colon != std::string::npos) {
        if (colon > 0) info.hostLength = colon;
        info.host.resize(colon);
    }
    info.valid = true;
    return info;
}

PayloadInfo parseTls(const std::uint8_t* data, std::size_t len) {
    PayloadInfo info;
    info.kind = PayloadKind::TLS_CLIENT_HELLO;

    if (len < 9) return info;
    if (data[0] != 0x16) return info;
    if (data[1] != 0x03) return info;
    if (data[5] != 0x01) return info; // handshake: ClientHello

    const std::uint16_t recordLen = readU16(data + 3);
    std::size_t recordEnd = 5u + recordLen;
    if (recordEnd > len) recordEnd = len;
    info.recordEnd = recordEnd;

    std::size_t p = 9;

    if (p + 2 + 32 > recordEnd) return info;
    p += 2;  // legacy_version
    p += 32; // random

    if (p + 1 > recordEnd) return info;
    const std::uint8_t sessionLen = data[p++];
    if (p + sessionLen > recordEnd) return info;
    p += sessionLen;

    if (p + 2 > recordEnd) return info;
    const std::uint16_t cipherLen = readU16(data + p);
    p += 2;
    if (p + cipherLen > recordEnd) return info;
    p += cipherLen;

    if (p + 1 > recordEnd) return info;
    const std::uint8_t compLen = data[p++];
    if (p + compLen > recordEnd) return info;
    p += compLen;

    if (p + 2 > recordEnd) return info;
    const std::uint16_t extTotal = readU16(data + p);
    p += 2;
    const std::size_t extEnd = std::min<std::size_t>(recordEnd, p + extTotal);

    while (p + 4 <= extEnd) {
        const std::uint16_t extType = readU16(data + p);
        const std::uint16_t extLen = readU16(data + p + 2);
        p += 4;
        if (p + extLen > extEnd) break;

        if (extType == 0x0000) { // server_name
            std::size_t q = p;
            if (q + 2 > p + extLen) break;
            const std::uint16_t listLen = readU16(data + q);
            q += 2;
            const std::size_t listEnd = std::min<std::size_t>(p + extLen, q + listLen);
            while (q + 3 <= listEnd) {
                const std::uint8_t nameType = data[q++];
                const std::uint16_t nameLen = readU16(data + q);
                q += 2;
                if (q + nameLen > listEnd) break;
                if (nameType == 0) { // host_name
                    info.host.assign(reinterpret_cast<const char*>(data + q), nameLen);
                    info.hostOffset = q;
                    info.hostLength = nameLen;
                    info.valid = true;
                    return info;
                }
                q += nameLen;
            }
        }
        p += extLen;
    }

    info.valid = true;
    return info;
}

std::size_t midSldOffset(const std::string& host) {
    if (host.empty()) return 0;
    std::string h = host;
    if (!h.empty() && h.back() == '.') h.pop_back();

    const std::size_t last = h.rfind('.');
    if (last == std::string::npos) return h.size() / 2;

    const std::size_t prev = (last == 0) ? std::string::npos : h.rfind('.', last - 1);
    const std::size_t start = (prev == std::string::npos) ? 0 : prev + 1;
    if (last <= start) return h.size() / 2;
    const std::size_t sldLen = last - start;
    return start + sldLen / 2;
}

}

PayloadInfo analyzePayload(const std::uint8_t* data, std::size_t len) {
    if (!data || len == 0) return {};
    if (len >= 5 && data[0] == 0x16 && data[1] == 0x03) {
        return parseTls(data, len);
    }
    if (isHttpMethod(data, len)) {
        return parseHttp(data, len);
    }
    return {};
}

bool resolvePosition(const std::string& spec, const PayloadInfo& info,
                     std::size_t len, std::size_t& out) {
    std::string s = spec;
    while (!s.empty() && std::isspace(static_cast<unsigned char>(s.front()))) s.erase(s.begin());
    while (!s.empty() && std::isspace(static_cast<unsigned char>(s.back()))) s.pop_back();
    if (s.empty()) return false;

    if (s == "end") {
        out = len;
        return true;
    }
    if (s == "sni" || s == "host") {
        if (!info.valid || info.hostLength == 0) return false;
        out = info.hostOffset;
        return true;
    }
    if (s == "sni_end" || s == "host_end" || s == "sni+e" || s == "host+e") {
        if (!info.valid || info.hostLength == 0) return false;
        out = info.hostOffset + info.hostLength;
        return true;
    }
    if (s == "midsld" || s == "sni+midsld") {
        if (!info.valid || info.hostLength == 0) return false;
        out = info.hostOffset + midSldOffset(info.host);
        return true;
    }
    if (s.size() > 4 && (s.compare(0, 4, "sni+") == 0 || s.compare(0, 5, "host+") == 0)) {
        const std::size_t prefix = (s.compare(0, 4, "sni+") == 0) ? 4 : 5;
        if (!info.valid || info.hostLength == 0) return false;
        char* endp = nullptr;
        const long delta = std::strtol(s.c_str() + prefix, &endp, 10);
        if (!endp || *endp != '\0') return false;
        const long pos = static_cast<long>(info.hostOffset) + delta;
        if (pos < 0) return false;
        out = static_cast<std::size_t>(pos);
        return true;
    }

    char* endp = nullptr;
    errno = 0;
    const long v = std::strtol(s.c_str(), &endp, 10);
    if (!endp || *endp != '\0' || errno != 0) return false;
    long pos = v;
    if (pos < 0) pos = static_cast<long>(len) + pos;
    if (pos < 0) pos = 0;
    if (static_cast<std::size_t>(pos) > len) pos = static_cast<long>(len);
    out = static_cast<std::size_t>(pos);
    return true;
}

bool tlsRecordSplit(std::vector<std::uint8_t>& data, std::size_t pos) {
    if (data.size() < 5 || data[0] != 0x16) return false;
    const std::size_t recordEnd = 5u + readU16(data.data() + 3);
    if (recordEnd > data.size()) return false;
    if (pos <= 5 || pos >= recordEnd) return false;

    const std::size_t tailLen = data.size() - recordEnd;
    const std::size_t secondLen = recordEnd - pos;

    data.resize(data.size() + 5);

    if (tailLen > 0) {
        std::memmove(data.data() + recordEnd + 5, data.data() + recordEnd, tailLen);
    }
    std::memmove(data.data() + pos + 5, data.data() + pos, secondLen);

    // rewrite the first record header
    const std::uint16_t firstLen = static_cast<std::uint16_t>(pos - 5);
    data[3] = static_cast<std::uint8_t>(firstLen >> 8);
    data[4] = static_cast<std::uint8_t>(firstLen & 0xff);

    // new record header at pos
    data[pos] = 0x16;
    data[pos + 1] = data[1];
    data[pos + 2] = data[2];
    data[pos + 3] = static_cast<std::uint8_t>(secondLen >> 8);
    data[pos + 4] = static_cast<std::uint8_t>(secondLen & 0xff);

    return true;
}

}
