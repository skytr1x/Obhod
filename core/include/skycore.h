#ifndef SKYCORE_H
#define SKYCORE_H

#include <atomic>
#include <functional>
#include <memory>
#include <mutex>
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
    void setTunFd(int fd);
    void setStrategyProfile(const std::string& profileJson);
    void setNetworkKey(const std::string& networkKey);
    std::string getStatsJson();
    std::string probeNetwork();
    std::string probeHost(const std::string& host, int port);

private:
    std::string m_configPath;
    ProtectorCallback m_protector;
    ListenerCallback m_listener;
    EventCallback m_event;
    State m_state = State::IDLE;

    std::shared_ptr<Profile> m_profile;
    mutable std::mutex m_profileMutex;
    std::unique_ptr<StrategySelector> m_selector;

    int m_tunFd = -1;
    std::atomic<int> m_socksPort{10800};
    Socks5Server m_socks5Server;
    std::atomic<bool> m_running{false};

    void loadProfile();
    void updateState(State newState, const std::string& message);
};

}

#endif
