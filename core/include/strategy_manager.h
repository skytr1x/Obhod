#ifndef STRATEGY_MANAGER_H
#define STRATEGY_MANAGER_H

#include "strategy.h"
#include <map>
#include <string>
#include <memory>

namespace skyunblock {

class StrategyManager {
public:
    void setStrategy(std::unique_ptr<Strategy> strategy) {
        m_currentStrategy = std::move(strategy);
    }

    std::vector<Packet> processPacket(const Packet& packet) {
        if (m_currentStrategy) {
            return m_currentStrategy->process(packet);
        }
        return {packet};
    }

    std::string getCurrentStrategyName() const {
        return m_currentStrategy ? m_currentStrategy->getName() : "None";
    }

private:
    std::unique_ptr<Strategy> m_currentStrategy;
};

}

#endif
