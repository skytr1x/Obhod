#ifndef STRATEGY_H
#define STRATEGY_H

#include <string>
#include <vector>
#include <memory>
#include <cstdint>

namespace skyunblock {

enum class Protocol {
    TCP,
    UDP,
    UNKNOWN
};

struct Packet {
    std::vector<uint8_t> data;
    int sequence;
    Protocol protocol;
    uint32_t dest_ip;
    uint16_t dest_port;
};

class Strategy {
public:
    virtual ~Strategy() = default;
    virtual std::vector<Packet> process(const Packet& packet) = 0;
    virtual std::string getName() const = 0;
};

}

#endif
