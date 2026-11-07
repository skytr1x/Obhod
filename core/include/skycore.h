#ifndef SKYCORE_H
#define SKYCORE_H

#include <atomic>
#include <functional>
#include <memory>
#include <string>

#include "profile.h"
#include "socks5_server.h"
#include "strategy_selector.h"

namespace skyunblock {

enum class State {
    IDLE,
    STARTING,
    RUNNING,
    DEGRADED,
    ERROR,
    STOPPING
};

class SkyCore {
public:
    using ProtectorCallback = std::function<bool(int)>;
    using ListenerCallback  = std::function<void(int, const std::string&)>;
    using EventCallback     = std::function<void(const std::string&)>;

    SkyCore(const std::string& configPath, ProtectorCallback protector,
            ListenerCallback listener, EventCallback event = nullptr);
    ~SkyCore();

    void start();
    void stop();
    void setNetworkKey(const std::string& networkKey);
    std::string getStatsJson();

private:
    std::string m_configPath;
    ProtectorCallback m_protector;
    ListenerCallback m_listener;
    EventCallback m_event;

    std::shared_ptr<Profile> m_profile;
    std::unique_ptr<StrategySelector> m_selector;

    std::atomic<int> m_socksPort{10800};
    Socks5Server m_socks5Server;
    std::atomic<bool> m_running{false};

    void loadProfile();
    void updateState(State newState, const std::string& message);
};

}

#endif
