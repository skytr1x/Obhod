#include "profile.h"
#include "strategy_selector.h"

#include <fstream>
#include <iostream>
#include <iterator>
#include <memory>
#include <set>
#include <string>

namespace {

bool expect(const std::string& actual, const std::string& expected,
            const char* description) {
    if (actual == expected) return true;
    std::cerr << description << ": expected " << expected << ", got " << actual
              << '\n';
    return false;
}

}

int main() {
    std::ifstream input(SKY_LIST_PROFILE_PATH, std::ios::binary);
    if (!input) {
        std::cerr << "Unable to open list profile\n";
        return 1;
    }

    const std::string json(std::istreambuf_iterator<char>(input), {});
    std::string error;
    auto profile = skyunblock::parseProfile(json, error);
    if (!profile) {
        std::cerr << "Unable to parse list profile: " << error << '\n';
        return 1;
    }

    skyunblock::StrategySelector selector(std::move(profile));
    bool passed = true;
    passed &= expect(selector.select("cdn.discordapp.com").key(), "multisplit-mobile",
                     "Listed TCP host should use the List tactic");
    const auto listCandidates = selector.candidates("cdn.discordapp.com", false);
    std::set<std::string> candidateStrategies;
    for (const auto& candidate : listCandidates) {
        candidateStrategies.insert(candidate.direct
            ? "direct"
            : candidate.strategy ? candidate.strategy->id : candidate.key());
    }
    if (candidateStrategies.size() != 7 ||
        listCandidates.front().key() != "multisplit-mobile" ||
        listCandidates.back().key() != "direct") {
        std::cerr << "List profile should try its fallback tactics before direct\n";
        passed = false;
    }
    passed &= expect(selector.select("unlisted.example").key(), "direct",
                     "Unlisted host should remain direct");
    passed &= expect(selector.select("discord.com", true).key(), "direct",
                     "UDP should not use a desync strategy");

    skyunblock::HostList largeList;
    for (int index = 0; index < 74318; ++index) {
        largeList.add("domain" + std::to_string(index) + ".example");
    }
    if (!largeList.matches("sub.domain74317.example") ||
        largeList.matches("notdomain74317.example")) {
        std::cerr << "Large domain lists should preserve suffix-boundary matching\n";
        passed = false;
    }
    return passed ? 0 : 1;
}
