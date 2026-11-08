#include "strategy_selector.h"

#include <algorithm>
#include <fstream>
#include <iostream>
#include <iterator>
#include <string>
#include <vector>

#ifndef SKY_LIST_PROFILE_PATH
#error "SKY_LIST_PROFILE_PATH must point to the List strategy profile"
#endif

namespace {

bool require(bool condition, const char* message) {
    if (condition) return true;
    std::cerr << "FAIL: " << message << '\n';
    return false;
}

}  // namespace

int main() {
    std::ifstream profileFile(SKY_LIST_PROFILE_PATH);
    if (!require(static_cast<bool>(profileFile), "List profile must be readable")) {
        return 1;
    }
    const std::string json(
        (std::istreambuf_iterator<char>(profileFile)),
        std::istreambuf_iterator<char>());
    std::string error;
    auto profile = skyunblock::parseProfile(json, error);
    if (!require(static_cast<bool>(profile), "List profile must parse")) {
        std::cerr << error << '\n';
        return 1;
    }

    skyunblock::StrategySelector selector(profile);
    if (!require(!selector.quicFallbackEnabled(),
                 "List must keep QUIC fallback disabled")) {
        return 1;
    }

    auto candidates = selector.candidates("discord.com", false, "tcp-443");
    if (!require(candidates.size() == 18,
                 "List should expose its base strategies and fake TTL variants")) {
        return 1;
    }
    if (!require(candidates.front().key() == "multisplit-mobile",
                 "List default strategy order should be preserved")) {
        return 1;
    }
    if (!require(candidates.back().direct,
                 "Direct routing should remain the final candidate")) {
        return 1;
    }
    for (const std::string& key : {
             "split-tlsrec", "split-delay", "fake-tls@2", "fake-tls@6",
             "fake-split@2", "fake-split@6"}) {
        const auto found = std::find_if(
            candidates.begin(), candidates.end(),
            [&key](const skyunblock::StrategyChoice& choice) {
                return choice.key() == key;
            });
        if (!require(found != candidates.end(),
                     "List must contain the configured combined strategy/TTL")) {
            std::cerr << "Missing: " << key << '\n';
            return 1;
        }
    }

    selector.setNetworkStrategyOrder("split-delay,split-sni");
    candidates = selector.candidates("discord.com", false, "tcp-443");
    if (!require(candidates.size() == 18 &&
                     candidates[0].key() == "split-delay" &&
                     candidates[1].key() == "split-sni",
                 "Network order should reorder candidates without dropping variants")) {
        return 1;
    }

    const auto outside = selector.candidates("outside.example", false, "tcp-443");
    if (!require(outside.size() == 1 && outside.front().direct,
                 "Hosts outside the include list should stay direct")) {
        return 1;
    }

    selector.markHostUnavailable("discord.com", "tcp-443");
    if (!require(selector.hostUnavailableCached("discord.com", "tcp-443"),
                 "A failed host should enter the short cooldown")) {
        return 1;
    }
    selector.markSuccess("discord.com", "split-sni", 100, "tcp-443");
    if (!require(!selector.hostUnavailableCached("discord.com", "tcp-443"),
                 "A later successful strategy should clear the cooldown")) {
        return 1;
    }
    selector.markHostUnavailable("discord.com", "tcp-443");
    selector.setNetworkKey("test-network");
    if (!require(!selector.hostUnavailableCached("discord.com", "tcp-443"),
                 "Changing networks should clear the host cooldown")) {
        return 1;
    }

    std::cout << "strategy selector tests passed\n";
    return 0;
}
