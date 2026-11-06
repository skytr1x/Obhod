#ifndef HOST_FILTER_H
#define HOST_FILTER_H

#include <string>
#include <unordered_set>
#include <mutex>
#include <arpa/inet.h>

namespace skyunblock {

class HostFilter {
public:
    HostFilter() = default;
    bool addIp(const std::string& ip) {
        struct in_addr addr;
        if (inet_pton(AF_INET, ip.c_str(), &addr) != 1) {
            return false;
        }

        std::lock_guard<std::mutex> lock(m_mutex);
        m_filteredIps.insert(addr.s_addr);
        return true;
    }

    bool isFiltered(uint32_t ip) const {
        std::lock_guard<std::mutex> lock(m_mutex);
        return m_filteredIps.find(ip) != m_filteredIps.end();
    }

    void clear() {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_filteredIps.clear();
    }

private:
    mutable std::mutex m_mutex;
    std::unordered_set<uint32_t> m_filteredIps;
};

}

#endif
