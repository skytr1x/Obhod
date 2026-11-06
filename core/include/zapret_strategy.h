#ifndef ZAPRET_STRATEGY_H
#define ZAPRET_STRATEGY_H

#include "strategy.h"
#include <iostream>
#include <vector>

namespace skyunblock {

class ZapretStrategy : public Strategy {
public:
    enum class Mode {
        FAKE,
        MULTISPLIT
    };

    struct Config {
        Mode mode;
        int splitPos = 1;
        int seqOvl = 0;
        std::vector<uint8_t> fakePayload;
        int repeats = 1;
    };

    ZapretStrategy(Config config) : m_config(config) {}

    std::vector<Packet> process(const Packet& packet) override {
        std::vector<Packet> result;

        if (m_config.mode == Mode::FAKE) {
            // send fake packets first
            for (int i = 0; i < m_config.repeats; ++i) {
                Packet fake = packet;
                fake.data = m_config.fakePayload;
                result.push_back(fake);
            }
            // send a real pacakge
            result.push_back(packet);
        }
        else if (m_config.mode == Mode::MULTISPLIT) {
            if (packet.data.size() <= (size_t)m_config.splitPos) {
                return {packet};
            }

            Packet p1, p2;
            p1.protocol = packet.protocol;
            p1.dest_ip = packet.dest_ip;
            p1.dest_port = packet.dest_port;
            p1.data.assign(packet.data.begin(), packet.data.begin() + m_config.splitPos);
            p1.sequence = packet.sequence;

            size_t overlap = (m_config.seqOvl < 0) ? 0 : (size_t)m_config.seqOvl;
            if (overlap > (size_t)m_config.splitPos) overlap = m_config.splitPos;

            p2.protocol = packet.protocol;
            p2.dest_ip = packet.dest_ip;
            p2.dest_port = packet.dest_port;
            p2.data.assign(packet.data.begin() + m_config.splitPos - overlap, packet.data.end());
            p2.sequence = packet.sequence + m_config.splitPos - overlap;

            result.push_back(p1);
            result.push_back(p2);
        }

        return result;
    }

    std::string getName() const override {
        return (m_config.mode == Mode::FAKE) ? "Zapret_Fake" : "Zapret_MultiSplit";
    }

private:
    Config m_config;
};

}

#endif
