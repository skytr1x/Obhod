#include "skycore.h"

#include <cstdio>
#include <fstream>
#include <iterator>
#include <sstream>

#include "json.h"
#include "sky_log.h"

namespace skyunblock {

SkyCore::SkyCore(const std::string& configPath, ProtectorCallback protector,
                 ListenerCallback listener, EventCallback event)
    : m_configPath(configPath),
      m_protector(std::move(protector)),
      m_listener(std::move(listener)),
      m_event(std::move(event)) {
    loadProfile();
    m_selector = std::make_unique<StrategySelector>(m_profile);
    if (!m_configPath.empty()) {
        const std::size_t slash = m_configPath.find_last_of('/');
        const std::string dir = slash == std::string::npos
                                    ? std::string(".")
                                    : m_configPath.substr(0, slash);
        m_selector->setCachePath(dir + "/strategy_cache");
    }
    updateState(State::IDLE, "SkyCore initialized");
}

void SkyCore::setNetworkKey(const std::string& networkKey) {
    if (!m_selector) return;
    const std::size_t loaded = m_selector->setNetworkKey(networkKey);
    const std::string key = networkKey.empty() ? "default" : networkKey;
    SKY_LOGI("SkyCore: network key '%s', %zu cached strategies", key.c_str(), loaded);
    if (m_event) {
        m_event("[NET] " + key + " (" + std::to_string(loaded) + " cached)");
    }
}

SkyCore::~SkyCore() {
    stop();
}

void SkyCore::loadProfile() {
    std::string text;
    if (!m_configPath.empty()) {
        std::ifstream input(m_configPath, std::ios::binary);
        if (input) {
            text.assign(std::istreambuf_iterator<char>(input),
                        std::istreambuf_iterator<char>());
        }
    }

    if (!text.empty()) {
        std::string error;
        std::shared_ptr<Profile> parsed = parseProfile(text, error);
        if (parsed) {
            m_profile = std::move(parsed);
            m_socksPort.store(m_profile->socksPort);
            SKY_LOGI("SkyCore: loaded profile '%s' from %s (%zu strategies)",
                     m_profile->updated.c_str(), m_configPath.c_str(),
                     m_profile->strategies.size());
            return;
        }
        SKY_LOGE("SkyCore: profile %s is invalid: %s; using built-in profile",
                 m_configPath.c_str(), error.c_str());
    } else {
        SKY_LOGW("SkyCore: no profile at %s; using built-in profile",
                 m_configPath.c_str());
    }

    m_profile = defaultProfile();
    m_socksPort.store(m_profile->socksPort);
}

void SkyCore::start() {
    if (m_running.exchange(true)) return;

    updateState(State::STARTING, "Starting SOCKS5 server...");

    Socks5Server::ProtectorCallback socks5Protector = [this](int fd) -> bool {
        return m_protector ? m_protector(fd) : false;
    };

    Socks5Server::EventCallback socks5Event = [this](const std::string& message) {
        if (m_event) m_event(message);
    };

    if (!m_socks5Server.start(static_cast<uint16_t>(m_socksPort.load()),
                              m_selector.get(), socks5Protector, socks5Event)) {
        m_running = false;
        SKY_LOGE("SkyCore: failed to start SOCKS5 server on port %d", m_socksPort.load());
        updateState(State::ERROR, "SOCKS5 server failed to start");
        return;
    }

    const std::shared_ptr<Profile>& profile = m_profile;
    updateState(State::RUNNING,
                "SkyCore running - SOCKS5 on 127.0.0.1:" +
                    std::to_string(m_socksPort.load()) + " (profile: " +
                    (profile ? profile->updated : std::string("none")) + ")");
}

void SkyCore::stop() {
    if (!m_running.exchange(false)) return;

    updateState(State::STOPPING, "Stopping SkyCore...");
    m_socks5Server.stop();
    updateState(State::IDLE, "SkyCore stopped");
}

std::string SkyCore::getStatsJson() {
    const std::shared_ptr<Profile>& profile = m_profile;
    std::ostringstream ss;
    const int actualPort = m_socks5Server.isRunning()
                               ? static_cast<int>(m_socks5Server.port())
                               : m_socksPort.load();
    ss << "{\"status\":\"" << (m_running.load() ? "running" : "stopped") << "\""
       << ",\"socks5_port\":" << actualPort
       << ",\"active_connections\":" << m_socks5Server.activeConnections()
       << ",\"total_connections\":" << m_socks5Server.totalConnections()
       << ",\"bytes_in\":" << m_socks5Server.bytesIn()
       << ",\"bytes_out\":" << m_socks5Server.bytesOut()
       << ",\"profile\":\"" << jsonEscape(profile ? profile->updated : "") << "\""
       << ",\"strategies\":"
       << (profile ? profile->strategies.size() : 0)
       << "}";
    return ss.str();
}

void SkyCore::updateState(State newState, const std::string& message) {
    SKY_LOGI("SkyCore state %d: %s", static_cast<int>(newState), message.c_str());
    if (m_listener) {
        m_listener(static_cast<int>(newState), message);
    }
}

}
