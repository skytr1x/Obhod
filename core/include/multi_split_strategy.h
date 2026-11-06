#ifndef MULTI_SPLIT_STRATEGY_H
#define MULTI_SPLIT_STRATEGY_H

#include "strategy.h"

namespace skyunblock {

class MultiSplitStrategy : public Strategy {
public:
    struct Config {
        int splitPos;
        int seqOvl;
        std::vector<uint8_t> pattern;
    };

    MultiSplitStrategy(Config config) : m_config(config) {}

    std::vector<Packet> process(const Packet& packet) override {
        if (packet.data.size() <= (size_t)m_config.splitPos) {
            return {packet};
        }

        Packet p1, p2;
        p1.data.assign(packet.data.begin(), packet.data.begin() + m_config.splitPos);
        p1.sequence = packet.sequence;

        size_t overlap = (m_config.seqOvl < 0) ? 0 : m_config.seqOvl;
        if (overlap > (size_t)m_config.splitPos) overlap = m_config.splitPos;

        p2.data.assign(packet.data.begin() + m_config.splitPos - overlap, packet.data.end());
        p2.sequence = packet.sequence + m_config.splitPos - overlap;

        return {p1, p2};
    }

    std::string getName() const override {
        return "MultiSplitStrategy";
    }

private:
    Config m_config;
};

}

#endif
