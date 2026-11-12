#include "strategy_selector.h"

#include <fstream>
#include <iostream>
#include <iterator>
#include <string>
#include <vector>

#ifndef SKY_ADAPTIVE_PROFILE_PATH
#error "SKY_ADAPTIVE_PROFILE_PATH must point to the Adaptive Desync Chain profile"
#endif

namespace {

bool require(bool condition, const char* message) {
    if (condition) return true;
    std::cerr << "FAIL: " << message << '\n';
    return false;
}

}

int main() {
    std::ifstream profileFile(SKY_ADAPTIVE_PROFILE_PATH);
    if (!require(static_cast<bool>(profileFile),
                 "Adaptive Desync Chain profile must be readable")) {
        return 1;
    }
    const std::string json(
        (std::istreambuf_iterator<char>(profileFile)),
        std::istreambuf_iterator<char>());
    std::string error;
    auto profile = skyunblock::parseProfile(json, error);
    if (!require(static_cast<bool>(profile),
                 "Adaptive Desync Chain profile must parse")) {
        std::cerr << error << '\n';
        return 1;
    }

    skyunblock::StrategySelector selector(profile);
    if (!require(profile->directFirst,
                 "Direct attempt must be enabled before desync profiles") ||
        !require(selector.quicFallbackEnabled(),
                 "QUIC fallback must be enabled for the chain")) {
        return 1;
    }

    const auto httpB7 = selector.findStrategy("adaptive-http-b7");
    if (!require(httpB7.strategy && httpB7.strategy->tcp.actions.size() == 2,
                 "HTTP technique B7 must be configured in the adaptive profile")) {
        return 1;
    }

    // simulate the runtime merge with the selected base profile
    profile->include.add("blocked.example");
    skyunblock::StrategyDef handoff;
    handoff.id = "list-handoff";
    handoff.cost = 7;
    handoff.tcp.valid = true;
    skyunblock::TcpAction handoffAction;
    handoffAction.type = skyunblock::ActionType::SPLIT;
    handoffAction.pos = "sni";
    handoff.tcp.actions.push_back(handoffAction);
    profile->strategies.push_back(handoff);
    profile->defaultTcp.push_back("list-handoff");

    const auto candidates = selector.candidates("blocked.example", false, "tcp-443");
    const std::vector<std::string> expected = {
        "direct", "adaptive-p1", "adaptive-p2", "adaptive-p3",
        "adaptive-p4", "adaptive-p5", "adaptive-p6", "list-handoff",
        "adaptive-http-b7"
    };
    if (!require(candidates.size() == expected.size(),
                 "All configured chain profiles must be candidates")) {
        return 1;
    }
    for (std::size_t index = 0; index < expected.size(); ++index) {
        if (!require(candidates[index].key() == expected[index],
                     "P0-P6 order must be preserved")) {
            std::cerr << "Unexpected candidate " << index << ": "
                      << candidates[index].key() << '\n';
            return 1;
        }
        if (index > 0 && index < expected.size() - 1 &&
            expected[index].rfind("adaptive-p", 0) == 0 &&
            !require(candidates[index].strategy->adaptive,
                     "P1-P6 must be charged to the adaptive attempt budget")) {
            return 1;
        }
    }
    const auto outside = selector.candidates("outside.example", false, "tcp-443");
    if (!require(outside.size() == 1 && outside.front().direct,
                 "Hosts outside the base include list must stay direct")) {
        return 1;
    }
    selector.markFailure("blocked.example", "adaptive-p1", "tcp-443");
    const auto afterFailure =
        selector.candidates("blocked.example", false, "tcp-443");
    if (!require(afterFailure.front().key() == "direct" &&
                     afterFailure[1].key() == "adaptive-p2",
                 "A failed cached profile must yield to the next untested profile")) {
        return 1;
    }

    selector.markFailure("blocked.example", "direct", "tcp-443");
    selector.markSuccess("blocked.example", "adaptive-p3", 120, "tcp-443");
    const auto afterDirectFailure =
        selector.candidates("blocked.example", false, "tcp-443");
    if (!require(afterDirectFailure.front().key() == "adaptive-p3" &&
                     afterDirectFailure[1].key() == "direct",
                 "A learned adaptive winner must precede a recently failed direct attempt")) {
        return 1;
    }
    for (int success = 0; success < 6; ++success) {
        selector.markSuccess("blocked.example", "adaptive-p3", 100, "tcp-443");
    }
    const auto afterDirectRecheck =
        selector.candidates("blocked.example", false, "tcp-443");
    if (!require(afterDirectRecheck.front().key() == "direct",
                 "Direct must be rechecked after repeated adaptive successes")) {
        return 1;
    }

    for (int attempt = 0; attempt < profile->adaptiveAttemptsPerMinute; ++attempt) {
        if (!require(selector.allowAdaptiveAttempt(
                         "blocked.example", "adaptive-p1", "tcp-443"),
                     "Attempts within the per-domain minute budget must be allowed")) {
            return 1;
        }
    }
    if (!require(!selector.allowAdaptiveAttempt(
                     "blocked.example", "adaptive-p1", "tcp-443"),
                 "The per-domain minute budget must stop additional adaptive attempts") ||
        !require(selector.allowAdaptiveAttempt(
                     "neighbor.example", "adaptive-p1", "tcp-443"),
                 "The attempt budget must be isolated per domain")) {
        return 1;
    }
    for (int attempt = 0; attempt < 20; ++attempt) {
        if (!require(selector.allowAdaptiveAttempt(
                         "blocked.example", "adaptive-p3", "tcp-443"),
                     "A cached adaptive winner must bypass an exhausted exploration budget")) {
            return 1;
        }
    }
    if (!require(!selector.allowAdaptiveAttempt(
                     "blocked.example", "adaptive-p2", "tcp-443"),
                 "Cached winner attempts must not change the exploration budget")) {
        return 1;
    }
    selector.setNetworkKey("next-network");
    if (!require(selector.allowAdaptiveAttempt(
                     "blocked.example", "adaptive-p1", "tcp-443"),
                 "Changing networks must clear the in-memory attempt budget")) {
        return 1;
    }

    std::cout << "adaptive desync tests passed\n";
    return 0;
}
