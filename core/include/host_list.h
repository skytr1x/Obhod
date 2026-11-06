#ifndef HOST_LIST_H
#define HOST_LIST_H

#include <string>
#include <vector>

namespace skyunblock {

class HostList {
public:
    HostList() = default;

    void add(const std::string& pattern);
    void addText(const std::string& text); // newline/space separated
    void clear();
    bool empty() const { return m_patterns.empty(); }
    bool matches(const std::string& host) const;
    std::size_t size() const { return m_patterns.size(); }

private:
    struct Pattern {
        std::string text;
        bool anchored = false;
        bool wildcard = false;
    };

    std::vector<Pattern> m_patterns;
};

std::string normalizeHost(std::string host);
bool isRussianDomain(const std::string& host);

}

#endif
