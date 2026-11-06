#ifndef SPLIT_STRATEGY_H
#define SPLIT_STRATEGY_H

#include "strategy.h"

namespace skyunblock {

class SplitStrategy : public Strategy {
public:
    SplitStrategy(int splitPos) : m_splitPos(splitPos) {}

    std::vector<Packet> process(const Packet& packet) override {
        if (packet.data.size() <= (size_t)m_splitPos) {
            return {packet};
        }

        Packet p1, p2;
        p1.data.assign(packet.data.begin(), packet.data.begin() + m_splitPos);
        p1.sequence = packet.sequence;

        p2.data.assign(packet.data.begin() + m_splitPos, packet.data.end());
        p2.sequence = packet.sequence + m_splitPos;

        return {p1, p2};
    }

    std::string getName() const override {
        return "SplitStrategy";
    }

private:
    int m_splitPos;
};

}

#endif
