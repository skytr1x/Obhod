#ifndef SKY_STRATEGY_SELECTOR_H
#define SKY_STRATEGY_SELECTOR_H

#include <chrono>
#include <cstddef>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "profile.h"

namespace skyunblock {

struct StrategyChoice {
    std::shared_ptr<const Profile> owner;
    const StrategyDef* strategy = nullptr;
    int ttl = 0;
    bool direct = false;
    bool tunnel = false;

    std::string key() const {
        if (direct) return "direct";
        if (tunnel) return "tunnel";
        if (!strategy) return "none";
        if (ttl > 0) return strategy->id + "@" + std::to_string(ttl);
        return strategy->id;
    }
};

class StrategySelector {
public:
    explicit StrategySelector(std::shared_ptr<Profile> profile);

    StrategyChoice findStrategy(const std::string& id) const;
    bool quicFallbackEnabled() const;
    StrategyChoice select(const std::string& host, bool udp = false,
                          const std::string& scope = std::string()) const;
    std::vector<StrategyChoice> candidates(const std::string& host, bool udp = false,
                                           const std::string& scope = std::string()) const;

    void markSuccess(const std::string& host, const std::string& strategyId,
                     long latencyMs = -1, const std::string& scope = std::string());
    void markFailure(const std::string& host, const std::string& strategyId,
                     const std::string& scope = std::string());
    bool allowAdaptiveAttempt(const std::string& host,
                              const std::string& strategyId,
                              const std::string& scope);
    void markHostUnavailable(const std::string& host, const std::string& scope);
    bool hostUnavailableCached(const std::string& host, const std::string& scope) const;

    void setCachePath(const std::string& basePath);
    std::size_t setNetworkKey(const std::string& networkKey);
    void setNetworkStrategyOrder(const std::string& tcpOrder);
    int maxStrategyAttempts() const;
    bool adaptiveChainEnabled() const;
    bool shouldDesyncHost(const std::string& host) const;
    bool desyncsAllHosts() const;

    std::string cachedStrategy(const std::string& host,
                               const std::string& scope = std::string()) const;

private:
    struct HostState {
        struct Metric {
            double failures = 0.0;
            std::chrono::steady_clock::time_point failedAt{};
            int attempts = 0;
            int successes = 0;
            long latencyMs = -1;
        };

        std::string preferred;
        std::unordered_map<std::string, Metric> metrics;
        std::chrono::steady_clock::time_point updated;
        std::chrono::steady_clock::time_point unavailableUntil{};
    };

    std::shared_ptr<Profile> m_profile;
    mutable std::mutex m_mutex;
    mutable std::unordered_map<std::string, HostState> m_cache;
    std::unordered_map<std::string,
                       std::deque<std::chrono::steady_clock::time_point>>
        m_adaptiveAttempts;
    std::string m_cacheBasePath;
    std::string m_networkKey = "default";
    std::vector<std::string> m_networkTcpOrder;
    std::string m_networkPreferredTcp;
    std::string m_networkPreferredUdp;

    StrategyChoice routeLocked(const std::string& host, bool udp,
                               const std::string& scope) const;
    std::vector<StrategyChoice> candidatesLocked(const std::string& host, bool udp,
                                                  const std::string& scope) const;
    static bool isUsable(const StrategyDef* strategy, bool udp);

    std::string cacheFileLocked() const;
    void loadCacheLocked();
    void saveCacheLocked();
    static double decayedFailures(const HostState::Metric& metric,
                                  std::chrono::steady_clock::time_point now);
};

}

#endif
