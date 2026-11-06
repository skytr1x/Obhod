#ifndef SKY_PROBER_H
#define SKY_PROBER_H

#include <functional>
#include <string>
#include <vector>

#include "strategy_selector.h"

namespace skyunblock {

struct ProbeAttempt {
    std::string strategy;
    bool tcpConnected = false;
    bool tlsResponse = false;
    bool success = false;
    long connectMs = -1;
    long responseMs = -1;
    int responseBytes = 0;
    std::string error;
};

struct ProbeResult {
    std::string host;
    int port = 443;
    bool dnsOk = false;
    std::string address;
    long dnsMs = -1;
    bool success = false;
    std::string strategy;
    long latencyMs = -1;
    std::vector<ProbeAttempt> attempts;
};

class Prober {
public:
    using ProtectorCallback = std::function<bool(int)>;
    using EventCallback = std::function<void(const std::string&)>;

    Prober(StrategySelector* selector, ProtectorCallback protector,
           int connectTimeoutMs = 5000, int responseTimeoutMs = 7000,
           EventCallback event = nullptr);

    ProbeResult probe(const std::string& host, int port = 443);
    std::string probeJson(const std::string& host, int port = 443);

    static std::string resultToJson(const ProbeResult& result);

private:
    StrategySelector* m_selector;
    ProtectorCallback m_protector;
    int m_connectTimeoutMs;
    int m_responseTimeoutMs;
    EventCallback m_event;
};

}

#endif
