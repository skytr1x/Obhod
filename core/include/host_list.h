#ifndef HOST_LIST_H
#define HOST_LIST_H

#include <string>
#include <unordered_set>
#include <vector>

namespace skyunblock {

class HostList {
public:
    HostList() = default;

    void add(const std::string& pattern);
    void addText(const std::string& text);
    void clear();
    bool empty() const { return m_domains.empty() && m_patterns.empty(); }
    bool matches(const std::string& host) const;

private:
    struct Pattern {
        std::string text;
        bool anchored = false;
        bool wildcard = false;
    };

    std::unordered_set<std::string> m_domains;
    std::vector<Pattern> m_patterns;
};

std::string normalizeHost(std::string host);
bool isRussianDomain(const std::string& host);

}

#endif
