#include "host_list.h"

#include <algorithm>
#include <cctype>
#include <sstream>

namespace skyunblock {

namespace {

bool wildcardMatch(const std::string& pattern, const std::string& text) {
    // Classic '*' wildcard matcher, '*' matches any sequence of bytes.
    std::size_t p = 0;
    std::size_t t = 0;
    std::size_t star = std::string::npos;
    std::size_t mark = 0;

    while (t < text.size()) {
        if (p < pattern.size() && (pattern[p] == text[t] || pattern[p] == '?')) {
            ++p;
            ++t;
        } else if (p < pattern.size() && pattern[p] == '*') {
            star = p++;
            mark = t;
        } else if (star != std::string::npos) {
            p = star + 1;
            t = ++mark;
        } else {
            return false;
        }
    }
    while (p < pattern.size() && pattern[p] == '*') ++p;
    return p == pattern.size();
}

bool isSubdomainOrEqual(const std::string& host, const std::string& suffix) {
    if (suffix.empty()) return false;
    if (host == suffix) return true;
    if (host.size() <= suffix.size()) return false;
    const std::size_t pos = host.size() - suffix.size();
    return host[pos - 1] == '.' && host.compare(pos, suffix.size(), suffix) == 0;
}

} // namespace

std::string normalizeHost(std::string host) {
    // Strip surrounding whitespace.
    while (!host.empty() && std::isspace(static_cast<unsigned char>(host.front()))) {
        host.erase(host.begin());
    }
    while (!host.empty() && std::isspace(static_cast<unsigned char>(host.back()))) {
        host.pop_back();
    }

    // Strip a port if it is not an IPv6 literal.
    const std::size_t firstColon = host.find(':');
    if (firstColon != std::string::npos && firstColon == host.rfind(':')) {
        if (host.find('.') != std::string::npos || firstColon > 0) {
            host.resize(firstColon);
        }
    }

    if (!host.empty() && host.back() == '.') host.pop_back();

    for (char& c : host) {
        c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    }
    return host;
}

void HostList::add(const std::string& raw) {
    std::string pattern = raw;
    while (!pattern.empty() && std::isspace(static_cast<unsigned char>(pattern.front()))) {
        pattern.erase(pattern.begin());
    }
    while (!pattern.empty() && std::isspace(static_cast<unsigned char>(pattern.back()))) {
        pattern.pop_back();
    }
    if (pattern.empty() || pattern[0] == '#') return;

    Pattern p;
    if (!pattern.empty() && pattern[0] == '^') {
        p.anchored = true;
        pattern.erase(pattern.begin());
    }
    if (!pattern.empty() && pattern[0] == '=') {
        p.anchored = true;
        pattern.erase(pattern.begin());
    }
    if (!pattern.empty() && pattern.back() == '$') {
        p.anchored = true;
        pattern.pop_back();
    }
    p.text = normalizeHost(pattern);
    if (p.text.empty()) return;
    p.wildcard = p.text.find('*') != std::string::npos ||
                 p.text.find('?') != std::string::npos;
    m_patterns.push_back(std::move(p));
}

void HostList::addText(const std::string& text) {
    std::string token;
    for (char c : text) {
        if (c == '\n' || c == '\r' || c == '\t' || c == ' ' || c == ',' || c == ';') {
            if (!token.empty()) {
                add(token);
                token.clear();
            }
        } else {
            token.push_back(c);
        }
    }
    if (!token.empty()) add(token);
}

void HostList::clear() {
    m_patterns.clear();
}

bool HostList::matches(const std::string& rawHost) const {
    const std::string host = normalizeHost(rawHost);
    if (host.empty()) return false;

    for (const auto& p : m_patterns) {
        if (p.wildcard) {
            std::string pattern = p.text;
            // "*.example.com" should also match "example.com".
            if (pattern.size() > 2 && pattern.compare(0, 2, "*.") == 0) {
                if (isSubdomainOrEqual(host, pattern.substr(2))) return true;
            }
            if (wildcardMatch(pattern, host)) return true;
            continue;
        }

        if (p.anchored) {
            // Anchor at the start of the host name: match "example.com" and
            // "example.com.evil" is deliberately not matched because we stop
            // at a label boundary.
            if (host == p.text) return true;
            if (host.size() > p.text.size() &&
                host.compare(0, p.text.size(), p.text) == 0 &&
                host[p.text.size()] == '.') {
                return true;
            }
        } else {
            if (isSubdomainOrEqual(host, p.text)) return true;
        }
    }
    return false;
}

bool isRussianDomain(const std::string& rawHost) {
    const std::string host = normalizeHost(rawHost);
    auto endsWith = [&host](const char* suffix) {
        const std::size_t n = std::char_traits<char>::length(suffix);
        return host.size() >= n && host.compare(host.size() - n, n, suffix) == 0;
    };

    return endsWith(".ru") || host == "ru" ||
           endsWith(".su") || host == "su" ||
           endsWith(".рф") || endsWith(".xn--p1ai");
}

} // namespace skyunblock
