#ifndef SKY_STRATEGY_SELECTOR_H
#define SKY_STRATEGY_SELECTOR_H

#include <chrono>
#include <cstddef>
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

    bool valid() const {
        return direct || tunnel || strategy != nullptr;
    }

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
    StrategySelector();
    explicit StrategySelector(std::shared_ptr<Profile> profile);

    void setProfile(std::shared_ptr<Profile> profile);
    std::shared_ptr<const Profile> profile() const;
    StrategyChoice select(const std::string& host, bool udp = false) const;
    std::vector<StrategyChoice> candidates(const std::string& host, bool udp = false) const;

    void markSuccess(const std::string& host, const std::string& strategyId);
    void markFailure(const std::string& host, const std::string& strategyId);
    void clearCache();

    void setCachePath(const std::string& basePath);
    std::size_t setNetworkKey(const std::string& networkKey);
    std::size_t cacheSize() const;

    std::string cachedStrategy(const std::string& host) const;

    bool isDirectHost(const std::string& host) const;
    bool shouldInspect(const std::string& host) const;

private:
    struct HostState {
        std::string preferred;
        std::unordered_map<std::string, int> failures;
        std::chrono::steady_clock::time_point updated;
    };

    std::shared_ptr<Profile> m_profile;
    mutable std::mutex m_mutex;
    mutable std::unordered_map<std::string, HostState> m_cache;
    std::string m_cacheBasePath;
    std::string m_networkKey = "default";

    StrategyChoice routeLocked(const std::string& host, bool udp) const;
    std::vector<StrategyChoice> candidatesLocked(const std::string& host, bool udp) const;
    static bool isUsable(const StrategyDef* strategy, bool udp);

    std::string cacheFileLocked() const;
    void loadCacheLocked();
    void saveCacheLocked();
    void clearCacheLocked(bool removeFile);
};

const char* actionTypeName(ActionType type);

}

#endif
