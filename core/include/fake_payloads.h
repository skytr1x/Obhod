#ifndef FAKE_PAYLOADS_H
#define FAKE_PAYLOADS_H

#include <cstddef>
#include <cstdint>

namespace skyunblock {

extern const std::uint8_t kTlsGoogle[];
extern const std::size_t kTlsGoogleLen;

extern const std::uint8_t kTls4pda[];
extern const std::size_t kTls4pdaLen;

extern const std::uint8_t kQuicGoogle[];
extern const std::size_t kQuicGoogleLen;

extern const std::uint8_t kStun[];
extern const std::size_t kStunLen;

}

#endif
