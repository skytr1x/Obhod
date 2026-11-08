#include "strategy_selector.h"

#include <algorithm>
#include <arpa/inet.h>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <ctime>
#include <fstream>
#include <set>
#include <sys/stat.h>
#include <unistd.h>

#include "host_list.h"
#include "sky_log.h"

namespace skyunblock {

namespace {
constexpr auto kCacheTtl = std::chrono::hours(6);
constexpr std::size_t kMaxCacheEntries = 4096;
constexpr double kFailureHalfLifeHours = 2.0;

bool isDirectId(const std::string& id) {
    return id == "direct" || id == "bypass" || id == "none";
}

bool isTunnelId(const std::string& id) {
    return id == "tunnel" || id == "proxy";
}

bool isDirectStrategy(const StrategyDef* strategy) {
    return strategy && (strategy->direct || isDirectId(strategy->id));
}

bool isIpLiteral(const std::string& host) {
    in_addr ipv4{};
    in6_addr ipv6{};
    return inet_pton(AF_INET, host.c_str(), &ipv4) == 1 ||
           inet_pton(AF_INET6, host.c_str(), &ipv6) == 1;
}

std::string sanitizeCacheName(const std::string& key) {
    std::string out;
    out.reserve(std::min<std::size_t>(key.size(), 64));
    for (char c : key) {
        if (out.size() >= 64) break;
        const unsigned char uc = static_cast<unsigned char>(c);
        if (std::isalnum(uc) || c == '-' || c == '_' || c == '.') {
            out.push_back(c);
        } else {
            out.push_back('_');
        }
    }
    return out.empty() ? "default" : out;
}

std::string stateKeyFor(const std::string& host, const std::string& scope) {
    const std::string normalized = normalizeHost(host);
    if (scope.empty()) return normalized;
    return normalized + "|" + scope;
}

std::vector<int> ttlValuesFor(const StrategyDef* strategy, bool udp) {
    int minTtl = 0;
    int maxTtl = 0;

    if (udp) {
        minTtl = strategy->udp.ttlRangeMin;
        maxTtl = strategy->udp.ttlRangeMax;
    } else {
        for (const TcpAction& action : strategy->tcp.actions) {
            if (action.type != ActionType::FAKE) continue;
            if (action.ttlRangeMin <= 0) continue;
            minTtl = action.ttlRangeMin;
            maxTtl = action.ttlRangeMax > 0 ? action.ttlRangeMax : action.ttlRangeMin;
            break;
        }
    }

    if (minTtl <= 0) return {0};
    if (maxTtl < minTtl) maxTtl = minTtl;

    maxTtl = std::min(maxTtl, minTtl + 15);
    std::vector<int> values;
    values.reserve(static_cast<std::size_t>(maxTtl - minTtl + 1));
    for (int ttl = minTtl; ttl <= maxTtl; ++ttl) values.push_back(ttl);
    return values;
}

}

StrategySelector::StrategySelector(std::shared_ptr<Profile> profile)
    : m_profile(profile ? std::move(profile) : defaultProfile()) {}

StrategyChoice StrategySelector::findStrategy(const std::string& id) const {
    std::lock_guard<std::mutex> lock(m_mutex);
    StrategyChoice choice;
    choice.owner = m_profile;
    choice.strategy = m_profile ? m_profile->findStrategy(id) : nullptr;
    return choice;
}

bool StrategySelector::quicFallbackEnabled() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_profile && m_profile->quicFallback;
}

std::string StrategySelector::cacheFileLocked() const {
    if (m_cacheBasePath.empty()) return {};
    return m_cacheBasePath + "_" + sanitizeCacheName(m_networkKey) + ".txt";
}

void StrategySelector::setCachePath(const std::string& basePath) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_cacheBasePath = basePath;
    m_cache.clear();
    m_networkPreferredTcp.clear();
    m_networkPreferredUdp.clear();
    loadCacheLocked();
}

std::size_t StrategySelector::setNetworkKey(const std::string& networkKey) {
    const std::string key = networkKey.empty() ? "default" : networkKey;
    std::lock_guard<std::mutex> lock(m_mutex);
    if (key != m_networkKey) {
        m_networkKey = key;
        m_cache.clear();
        m_networkTcpOrder.clear();
        m_networkPreferredTcp.clear();
        m_networkPreferredUdp.clear();
    }
    loadCacheLocked();
    return m_cache.size();
}

void StrategySelector::setNetworkStrategyOrder(const std::string& tcpOrder) {
    std::vector<std::string> order;
    std::size_t begin = 0;
    while (begin < tcpOrder.size()) {
        const std::size_t end = tcpOrder.find(',', begin);
        std::string id = tcpOrder.substr(
            begin, end == std::string::npos ? std::string::npos : end - begin);
        const std::size_t first = id.find_first_not_of(" \t\r\n");
        if (first != std::string::npos) {
            const std::size_t last = id.find_last_not_of(" \t\r\n");
            id = id.substr(first, last - first + 1);
            if (std::find(order.begin(), order.end(), id) == order.end()) {
                order.push_back(std::move(id));
            }
        }
        if (end == std::string::npos) break;
        begin = end + 1;
    }

    std::lock_guard<std::mutex> lock(m_mutex);
    m_networkTcpOrder = std::move(order);
}

void StrategySelector::loadCacheLocked() {
    if (m_cacheBasePath.empty()) return;
    const std::string path = cacheFileLocked();
    if (path.empty()) return;

    struct stat info {};
    if (::stat(path.c_str(), &info) != 0) return;
    const auto age = std::chrono::system_clock::now() -
                     std::chrono::system_clock::from_time_t(info.st_mtime);
    if (age > kCacheTtl) {
        SKY_LOGI("StrategySelector: cache %s is stale, ignoring", path.c_str());
        return;
    }

    std::ifstream input(path);
    if (!input) return;

    const auto now = std::chrono::steady_clock::now();
    std::string line;
    std::size_t loaded = 0;
    while (std::getline(input, line)) {
        constexpr const char kTcpPreferred[] = "# network-tcp-preferred ";
        constexpr const char kUdpPreferred[] = "# network-udp-preferred ";
        if (line.compare(0, sizeof(kTcpPreferred) - 1, kTcpPreferred) == 0) {
            m_networkPreferredTcp = line.substr(sizeof(kTcpPreferred) - 1);
            continue;
        }
        if (line.compare(0, sizeof(kUdpPreferred) - 1, kUdpPreferred) == 0) {
            m_networkPreferredUdp = line.substr(sizeof(kUdpPreferred) - 1);
            continue;
        }
        if (line.empty() || line[0] == '#') continue;
        const std::size_t tab = line.find('\t');
        if (tab == std::string::npos || tab == 0) continue;
        const std::string key = line.substr(0, tab);
        const std::string host = normalizeHost(line.substr(tab + 1));
        if (host.empty() || key.empty()) continue;
        if (key.find_first_of(" \t\r\n") != std::string::npos) continue;
        if (loaded >= kMaxCacheEntries) break;
        HostState& state = m_cache[host];
        state.preferred = key;
        state.updated = now;
        ++loaded;
    }

    if (loaded > 0) {
        SKY_LOGI("StrategySelector: loaded %zu cached strategies for network '%s'",
                 loaded, m_networkKey.c_str());
    }
}

void StrategySelector::saveCacheLocked() {
    if (m_cacheBasePath.empty()) return;
    const std::string path = cacheFileLocked();
    if (path.empty()) return;

    const std::string temp = path + ".tmp";
    {
        std::ofstream output(temp, std::ios::trunc);
        if (!output) return;
        output << "# skyunblock strategy cache v1\n";
        output << "# network " << m_networkKey << "\n";
        if (!m_networkPreferredTcp.empty()) {
            output << "# network-tcp-preferred " << m_networkPreferredTcp << "\n";
        }
        if (!m_networkPreferredUdp.empty()) {
            output << "# network-udp-preferred " << m_networkPreferredUdp << "\n";
        }
        std::size_t written = 0;
        for (const auto& entry : m_cache) {
            if (entry.second.preferred.empty()) continue;
            if (written >= kMaxCacheEntries) break;
            output << entry.second.preferred << '\t' << entry.first << '\n';
            ++written;
        }
        if (!output) return;
    }
    if (::rename(temp.c_str(), path.c_str()) != 0) {
        ::unlink(temp.c_str());
    }
}

double StrategySelector::decayedFailures(const HostState::Metric& metric,
                                         std::chrono::steady_clock::time_point now) {
    if (metric.failures <= 0.0) return 0.0;
    const double ageHours =
        std::chrono::duration<double, std::ratio<3600>>(now - metric.failedAt).count();
    return metric.failures * std::exp2(-ageHours / kFailureHalfLifeHours);
}

bool StrategySelector::isUsable(const StrategyDef* strategy, bool udp) {
    if (!strategy) return false;
    if (strategy->direct || isDirectId(strategy->id)) return true;
    if (strategy->tunnel || isTunnelId(strategy->id)) return true;
    if (udp) return strategy->udp.fakeCount > 0;
    if (strategy->tcp.actions.empty()) return false;
    return strategy->tcp.valid || !strategy->tcp.actions.empty();
}

StrategyChoice StrategySelector::routeLocked(const std::string& rawHost, bool udp,
                                             const std::string& scope) const {
    StrategyChoice choice;
    choice.owner = m_profile;
    if (!m_profile) return choice;

    const std::string host = normalizeHost(rawHost);

    if (!host.empty()) {
        const std::string rule = m_profile->ruleStrategy(host);
        if (!rule.empty()) {
            if (isDirectId(rule)) {
                choice.direct = true;
                return choice;
            }
            if (isTunnelId(rule)) {
                choice.tunnel = true;
                return choice;
            }
            const StrategyDef* strategy = m_profile->findStrategy(rule);
            if (strategy && isUsable(strategy, udp)) {
                choice.strategy = strategy;
                return choice;
            }
            if (strategy && !udp) {
                choice.direct = true;
                return choice;
            }
        }

        if (m_profile->exclude.matches(host)) {
            choice.direct = true;
            return choice;
        }
        if (m_profile->directRu && isRussianDomain(host)) {
            choice.direct = true;
            return choice;
        }
        const bool udpIpHasNoDomain = udp && isIpLiteral(host);
        if (!m_profile->include.empty() && !m_profile->include.matches(host) &&
            !udpIpHasNoDomain) {
            choice.direct = true;
            return choice;
        }
    }

    const std::vector<StrategyChoice> list = candidatesLocked(host, udp, scope);
    if (!list.empty()) return list.front();
    choice.direct = true;
    return choice;
}

std::vector<StrategyChoice> StrategySelector::candidatesLocked(const std::string& rawHost,
                                                               bool udp,
                                                               const std::string& scope) const {
    std::vector<StrategyChoice> out;
    if (!m_profile) return out;

    const std::string host = normalizeHost(rawHost);
    const StrategyDef* ruleStrategy = nullptr;
    if (!host.empty()) {
        const std::string rule = m_profile->ruleStrategy(host);
        if (!rule.empty()) {
            StrategyChoice choice;
            choice.owner = m_profile;
            if (isDirectId(rule)) {
                choice.direct = true;
                out.push_back(std::move(choice));
                return out;
            }
            if (isTunnelId(rule)) {
                choice.tunnel = true;
                out.push_back(std::move(choice));
                return out;
            }
            const StrategyDef* strategy = m_profile->findStrategy(rule);
            if (strategy && isUsable(strategy, udp)) {
                ruleStrategy = strategy;
            }
        }
        if (m_profile->exclude.matches(host) ||
            (m_profile->directRu && isRussianDomain(host)) ||
            (!m_profile->include.empty() && !m_profile->include.matches(host) &&
             !(udp && isIpLiteral(host)))) {
            StrategyChoice choice;
            choice.owner = m_profile;
            choice.direct = true;
            out.push_back(std::move(choice));
            return out;
        }
    }

    const std::vector<std::string>& defaults =
        udp ? m_profile->defaultUdp : m_profile->defaultTcp;

    std::vector<const StrategyDef*> ordered;
    std::set<const StrategyDef*> seen;

    auto addById = [&](const std::string& id) {
        const StrategyDef* strategy = m_profile->findStrategy(id);
        if (!strategy || !isUsable(strategy, udp) || seen.count(strategy)) {
            return;
        }
        seen.insert(strategy);
        ordered.push_back(strategy);
    };

    if (ruleStrategy) {
        seen.insert(ruleStrategy);
        ordered.push_back(ruleStrategy);
    }

    const std::string& networkPreferred = udp
        ? m_networkPreferredUdp
        : m_networkPreferredTcp;
    if (!networkPreferred.empty()) {
        const std::size_t ttlSeparator = networkPreferred.find('@');
        addById(networkPreferred.substr(0, ttlSeparator));
    }
    if (!udp) {
        for (const auto& id : m_networkTcpOrder) addById(id);
    }
    for (const auto& id : defaults) addById(id);

    std::vector<const StrategyDef*> byCost;
    for (const auto& strategy : m_profile->strategies) {
        if (strategy.id == "mtproto-obfs-split") continue;
        if (isUsable(&strategy, udp)) {
            byCost.push_back(&strategy);
        }
    }
    std::stable_sort(byCost.begin(), byCost.end(),
                     [](const StrategyDef* a, const StrategyDef* b) {
                         return a->cost < b->cost;
                     });
    for (const StrategyDef* strategy : byCost) {
        if (seen.count(strategy)) continue;
        seen.insert(strategy);
        ordered.push_back(strategy);
    }

    const HostState* state = nullptr;
    auto it = m_cache.find(stateKeyFor(host, scope));
    if (it != m_cache.end()) {
        if (std::chrono::steady_clock::now() - it->second.updated < kCacheTtl) {
            state = &it->second;
        }
    }

    std::vector<StrategyChoice> expanded;
    StrategyChoice directChoice;
    bool hasDirect = false;

    for (const StrategyDef* strategy : ordered) {
        if (isDirectStrategy(strategy)) {
            if (!hasDirect) {
                directChoice.owner = m_profile;
                directChoice.strategy = strategy;
                directChoice.direct = true;
                hasDirect = true;
            }
            continue;
        }

        const std::vector<int> ttls = ttlValuesFor(strategy, udp);
        for (int ttl : ttls) {
            StrategyChoice choice;
            choice.owner = m_profile;
            choice.strategy = strategy;
            choice.ttl = ttl;
            expanded.push_back(std::move(choice));
        }
    }

    if (!networkPreferred.empty()) {
        auto found = std::find_if(expanded.begin(), expanded.end(),
                                  [&](const StrategyChoice& choice) {
                                      return choice.key() == networkPreferred;
                                  });
        if (found == expanded.end()) {
            const std::size_t ttlSeparator = networkPreferred.find('@');
            const std::string preferredId = networkPreferred.substr(0, ttlSeparator);
            found = std::find_if(expanded.begin(), expanded.end(),
                                 [&](const StrategyChoice& choice) {
                                     return choice.strategy &&
                                            choice.strategy->id == preferredId;
                                 });
        }
        if (found != expanded.end()) {
            std::rotate(expanded.begin(), found, found + 1);
        }
    }

    if (state && !state->preferred.empty()) {
        auto found = std::find_if(expanded.begin(), expanded.end(),
                                  [&](const StrategyChoice& choice) {
                                      return choice.key() == state->preferred;
                                  });
        if (found != expanded.end()) {
            std::rotate(expanded.begin(), found, found + 1);
        }
    }

    if (state && !state->metrics.empty()) {
        const auto now = std::chrono::steady_clock::now();
        auto metricFor = [&](const StrategyChoice& choice) {
            auto it = state->metrics.find(choice.key());
            return it == state->metrics.end() ? nullptr : &it->second;
        };
        std::stable_sort(expanded.begin(), expanded.end(),
                         [&](const StrategyChoice& a, const StrategyChoice& b) {
                             const bool ap = a.key() == state->preferred;
                             const bool bp = b.key() == state->preferred;
                             if (ap != bp) return ap;

                             const HostState::Metric* am = metricFor(a);
                             const HostState::Metric* bm = metricFor(b);
                             const double af = am ? decayedFailures(*am, now) : 0.0;
                             const double bf = bm ? decayedFailures(*bm, now) : 0.0;
                             if (af != bf) return af < bf;

                             const bool aTested = am && am->attempts > 0;
                             const bool bTested = bm && bm->attempts > 0;
                             if (aTested != bTested) return aTested;
                             const double ar = aTested
                                 ? static_cast<double>(am->successes) / am->attempts : 0.0;
                             const double br = bTested
                                 ? static_cast<double>(bm->successes) / bm->attempts : 0.0;
                             if (ar != br) return ar > br;

                             const long al = am && am->latencyMs > 0 ? am->latencyMs : 1L << 30;
                             const long bl = bm && bm->latencyMs > 0 ? bm->latencyMs : 1L << 30;
                             return al < bl;
                         });
    }

    for (auto& choice : expanded) {
        out.push_back(std::move(choice));
    }

    if (hasDirect) {
        out.push_back(std::move(directChoice));
    } else {
        StrategyChoice choice;
        choice.owner = m_profile;
        choice.direct = true;
        out.push_back(std::move(choice));
    }

    return out;
}

StrategyChoice StrategySelector::select(const std::string& host, bool udp,
                                        const std::string& scope) const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return routeLocked(host, udp, scope);
}

std::vector<StrategyChoice> StrategySelector::candidates(const std::string& host,
                                                         bool udp,
                                                         const std::string& scope) const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return candidatesLocked(host, udp, scope);
}

void StrategySelector::markSuccess(const std::string& rawHost, const std::string& strategyId,
                                   long latencyMs, const std::string& scope) {
    if (strategyId.empty()) return;
    const std::string host = normalizeHost(rawHost);
    const std::string stateKey = stateKeyFor(host, scope);
    std::lock_guard<std::mutex> lock(m_mutex);
    const auto now = std::chrono::steady_clock::now();
    HostState& state = m_cache[stateKey];
    state.preferred = strategyId;
    for (auto& entry : state.metrics) {
        HostState::Metric& metric = entry.second;
        metric.failures = decayedFailures(metric, now) * 0.5;
        if (metric.failures < 0.01) metric.failures = 0.0;
        metric.failedAt = now;
    }
    HostState::Metric& metric = state.metrics[strategyId];
    ++metric.attempts;
    ++metric.successes;
    if (latencyMs > 0) {
        metric.latencyMs = metric.latencyMs <= 0
            ? latencyMs
            : (metric.latencyMs * 3 + latencyMs) / 4;
    }
    state.updated = now;
    if (!isDirectId(strategyId) && !isTunnelId(strategyId)) {
        if (scope.rfind("udp-", 0) == 0) {
            m_networkPreferredUdp = strategyId;
        } else if (scope.rfind("tcp-", 0) == 0) {
            m_networkPreferredTcp = strategyId;
        }
    }
    saveCacheLocked();
    SKY_LOGI("StrategySelector: '%s' works for '%s'", strategyId.c_str(), host.c_str());
}

void StrategySelector::markFailure(const std::string& rawHost,
                                   const std::string& strategyId,
                                   const std::string& scope) {
    if (strategyId.empty()) return;
    const std::string host = normalizeHost(rawHost);
    const std::string stateKey = stateKeyFor(host, scope);
    std::lock_guard<std::mutex> lock(m_mutex);
    const auto now = std::chrono::steady_clock::now();
    HostState& state = m_cache[stateKey];
    HostState::Metric& metric = state.metrics[strategyId];
    metric.failures = decayedFailures(metric, now) + 1.0;
    metric.failedAt = now;
    ++metric.attempts;
    if (state.preferred == strategyId) {
        state.preferred.clear();
        saveCacheLocked();
    }
    state.updated = now;
    SKY_LOGD("StrategySelector: '%s' failed for '%s' (weight %.1f)",
             strategyId.c_str(), host.c_str(), metric.failures);
}

std::string StrategySelector::cachedStrategy(const std::string& rawHost,
                                             const std::string& scope) const {
    const std::string host = normalizeHost(rawHost);
    std::lock_guard<std::mutex> lock(m_mutex);
    auto it = m_cache.find(stateKeyFor(host, scope));
    if (it == m_cache.end()) return {};
    if (std::chrono::steady_clock::now() - it->second.updated >= kCacheTtl) return {};
    return it->second.preferred;
}

}
