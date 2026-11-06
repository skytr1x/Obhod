#include "tls_utils.h"

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <random>

#include "sky_log.h"

namespace skyunblock {

namespace {

std::uint16_t readU16(const std::uint8_t* p) {
    return static_cast<std::uint16_t>((static_cast<std::uint16_t>(p[0]) << 8) | p[1]);
}

void writeU16(std::vector<std::uint8_t>& out, std::uint16_t v) {
    out.push_back(static_cast<std::uint8_t>(v >> 8));
    out.push_back(static_cast<std::uint8_t>(v & 0xff));
}

void writeU24(std::vector<std::uint8_t>& out, std::uint32_t v) {
    out.push_back(static_cast<std::uint8_t>((v >> 16) & 0xff));
    out.push_back(static_cast<std::uint8_t>((v >> 8) & 0xff));
    out.push_back(static_cast<std::uint8_t>(v & 0xff));
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

std::vector<std::uint8_t> makeRandom(std::size_t n) {
    std::vector<std::uint8_t> out(n);
    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_int_distribution<int> dist(0, 255);
    for (auto& b : out) b = static_cast<std::uint8_t>(dist(gen));
    return out;
}

}

std::string toLowerAscii(std::string value) {
    for (char& c : value) {
        c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    }
    return value;
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

std::vector<std::uint8_t> buildClientHello(const std::string& host) {
    std::vector<std::uint8_t> body;
    body.reserve(256);

    // legacy_version
    body.push_back(0x03);
    body.push_back(0x03);

    // random
    const auto random = makeRandom(32);
    body.insert(body.end(), random.begin(), random.end());

    // legacy_session_id: empty is allowed.
    body.push_back(0x00);

    // cipher suites
    writeU16(body, 8);
    writeU16(body, 0x1301); // TLS_AES_128_GCM_SHA256
    writeU16(body, 0x1302); // TLS_AES_256_GCM_SHA384
    writeU16(body, 0xc02f); // ECDHE_RSA_AES128_GCM_SHA256
    writeU16(body, 0xc030); // ECDHE_RSA_AES256_GCM_SHA384

    // compression methods
    body.push_back(0x01);
    body.push_back(0x00);

    // extensions
    std::vector<std::uint8_t> ext;

    // server_name
    {
        const std::size_t hostLen = host.size();
        writeU16(ext, 0x0000);
        writeU16(ext, static_cast<std::uint16_t>(2 + 1 + 2 + hostLen));
        writeU16(ext, static_cast<std::uint16_t>(1 + 2 + hostLen));
        ext.push_back(0x00);
        writeU16(ext, static_cast<std::uint16_t>(hostLen));
        ext.insert(ext.end(), host.begin(), host.end());
    }

    // supported_groups: x25519, secp256r1
    writeU16(ext, 0x000a);
    writeU16(ext, 6);
    writeU16(ext, 4);
    writeU16(ext, 0x001d);
    writeU16(ext, 0x0017);

    // ec_point_formats: uncompressed
    writeU16(ext, 0x000b);
    writeU16(ext, 2);
    ext.push_back(0x01);
    ext.push_back(0x00);

    // signature_algorithms
    writeU16(ext, 0x000d);
    writeU16(ext, 10);
    writeU16(ext, 8);
    writeU16(ext, 0x0403); // ecdsa_secp256r1_sha256
    writeU16(ext, 0x0804); // rsa_pss_rsae_sha256
    writeU16(ext, 0x0401); // rsa_pkcs1_sha256
    writeU16(ext, 0x0503); // ecdsa_secp384r1_sha384

    // supported_versions
    writeU16(ext, 0x002b);
    writeU16(ext, 5);
    ext.push_back(0x04);
    writeU16(ext, 0x0304);
    writeU16(ext, 0x0303);

    // key_share: x25519
    {
        const auto key = makeRandom(32);
        writeU16(ext, 0x0033);
        writeU16(ext, 38);
        writeU16(ext, 36);
        writeU16(ext, 0x001d);
        writeU16(ext, 32);
        ext.insert(ext.end(), key.begin(), key.end());
    }

    writeU16(ext, 0x0010);
    writeU16(ext, 14);
    writeU16(ext, 12);
    ext.push_back(0x02);
    ext.push_back('h');
    ext.push_back('2');
    ext.push_back(0x08);
    ext.insert(ext.end(), {'h', 't', 't', 'p', '/', '1', '.', '1'});

    writeU16(body, static_cast<std::uint16_t>(ext.size()));
    body.insert(body.end(), ext.begin(), ext.end());

    std::vector<std::uint8_t> out;
    out.reserve(body.size() + 9);
    out.push_back(0x16);
    out.push_back(0x03);
    out.push_back(0x01);
    writeU16(out, static_cast<std::uint16_t>(body.size() + 4));
    out.push_back(0x01);
    writeU24(out, static_cast<std::uint32_t>(body.size()));
    out.insert(out.end(), body.begin(), body.end());
    return out;
}

}
