#ifndef SKY_PROFILE_H
#define SKY_PROFILE_H

#include <memory>
#include <string>
#include <vector>

#include "host_list.h"

namespace skyunblock {

enum class ActionType {
    SPLIT,
    DISORDER,
    FAKE,
    TLS_REC,
    OOB
};

struct TcpAction {
    ActionType type = ActionType::SPLIT;
    std::string pos = "sni";
    int ttl = 8;
    int ttlRangeMin = 0;
    int ttlRangeMax = 0;
    int repeats = 1;
    std::string payload = "tls_google";
    int delayMs = 0;
};

struct TcpPlan {
    std::vector<TcpAction> actions;
    bool tcpNodelay = true;
    int defaultTtl = 64;
    int defaultDelay = 0;
    bool valid = false;
};

struct UdpPlan {
    int fakeCount = 0;
    int ttl = 8;
    int ttlRangeMin = 0;
    int ttlRangeMax = 0;
    std::string payload = "quic_google";
    bool valid = false;
};

struct StrategyDef {
    std::string id;
    int cost = 10;
    TcpPlan tcp;
    UdpPlan udp;
    bool tunnel = false;
    bool direct = false;
};

struct HostRule {
    std::vector<std::string> hosts;
    std::string strategy;
};

struct Profile {
    int version = 1;
    std::string updated;
    int socksPort = 10800;
    int defaultTtl = 64;
    bool tcpNodelay = true;
    bool directRu = true;
    int delayMs = 0;
    bool quicFallback = true;

    std::vector<StrategyDef> strategies;
    std::vector<HostRule> rules;
    HostList include;
    HostList exclude;

    std::vector<std::string> defaultTcp;
    std::vector<std::string> defaultUdp;

    bool valid = false;

    const StrategyDef* findStrategy(const std::string& id) const;
    std::vector<const StrategyDef*> orderedStrategies() const;
    std::string ruleStrategy(const std::string& host) const;
};

std::shared_ptr<Profile> parseProfile(const std::string& json, std::string& error);
std::shared_ptr<Profile> defaultProfile();

}

#endif
