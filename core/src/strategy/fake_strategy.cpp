#include "strategy.h"
#include <iostream>

namespace skyunblock {

class FakeStrategy : public Strategy {
public:
    FakeStrategy(const std::vector<uint8_t>& fakePayload) : m_fakePayload(fakePayload) {}

    std::vector<Packet> process(const Packet& packet) override {
        Packet fake;
        fake.data = m_fakePayload;
        fake.sequence = packet.sequence - 1;

        return {fake, packet};
    }

    std::string getName() const override {
        return "FakeStrategy";
    }

private:
    std::vector<uint8_t> m_fakePayload;
};

}
