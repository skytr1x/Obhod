#include "profile.h"

#include <algorithm>
#include <cstdlib>
#include <sstream>

#include "json.h"
#include "sky_log.h"
#include "tls_utils.h"

namespace skyunblock {

namespace {

std::string numberToString(double value) {
    std::ostringstream ss;
    if (value == static_cast<long long>(value)) {
        ss << static_cast<long long>(value);
    } else {
        ss << value;
    }
    return ss.str();
}

std::vector<std::string> positionList(const JsonValue* value, const std::string& fallback) {
    std::vector<std::string> out;
    if (!value) return out;
    if (value->isString()) {
        out.push_back(value->stringValue);
    } else if (value->isNumber()) {
        out.push_back(numberToString(value->numberValue));
    } else if (value->isBool()) {
        if (value->boolValue) out.push_back(fallback);
    } else if (value->isArray()) {
        for (const auto& item : value->arrayValue) {
            if (item.isString()) out.push_back(item.stringValue);
            else if (item.isNumber()) out.push_back(numberToString(item.numberValue));
            else if (item.isBool() && item.boolValue) out.push_back(fallback);
        }
    }
    return out;
}

std::string getString(const JsonValue* object, const char* key, const std::string& fallback) {
    if (!object) return fallback;
    const JsonValue* value = object->find(key);
    if (!value) return fallback;
    if (value->isString()) return value->stringValue;
    if (value->isNumber()) return numberToString(value->numberValue);
    return fallback;
}

int getInt(const JsonValue* object, const char* key, int fallback) {
    if (!object) return fallback;
    const JsonValue* value = object->find(key);
    if (!value) return fallback;
    if (value->isNumber()) return static_cast<int>(value->numberValue);
    if (value->isString()) return std::atoi(value->stringValue.c_str());
    if (value->isBool()) return value->boolValue ? 1 : 0;
    return fallback;
}

bool getBool(const JsonValue* object, const char* key, bool fallback) {
    if (!object) return fallback;
    const JsonValue* value = object->find(key);
    if (!value) return fallback;
    if (value->isBool()) return value->boolValue;
    if (value->isNumber()) return value->numberValue != 0.0;
    if (value->isString()) return value->stringValue == "true" || value->stringValue == "1";
    return fallback;
}

bool getTtlRange(const JsonValue* value, int& minTtl, int& maxTtl) {
    int low = 0;
    int high = 0;
    if (!value) return false;

    if (value->isNumber()) {
        low = high = static_cast<int>(value->numberValue);
    } else if (value->isString()) {
        const std::string text = value->stringValue;
        const std::size_t dash = text.find('-');
        if (dash != std::string::npos) {
            low = std::atoi(text.substr(0, dash).c_str());
            high = std::atoi(text.substr(dash + 1).c_str());
        } else {
            low = high = std::atoi(text.c_str());
        }
    } else if (value->isArray()) {
        const std::vector<JsonValue>& items = value->arrayValue;
        if (items.empty()) return false;
        if (items[0].isNumber()) low = static_cast<int>(items[0].numberValue);
        else if (items[0].isString()) low = std::atoi(items[0].stringValue.c_str());
        high = low;
        if (items.size() >= 2) {
            if (items[1].isNumber()) high = static_cast<int>(items[1].numberValue);
            else if (items[1].isString()) high = std::atoi(items[1].stringValue.c_str());
        }
    } else if (value->isObject()) {
        low = getInt(value, "min", getInt(value, "start", 0));
        high = getInt(value, "max", getInt(value, "end", low));
    }

    if (low <= 0) return false;
    if (high <= 0) high = low;
    if (low > high) std::swap(low, high);
    minTtl = std::max(1, std::min(low, 255));
    maxTtl = std::max(minTtl, std::min(high, 255));
    return true;
}

TcpAction makeAction(ActionType type, const std::string& pos) {
    TcpAction action;
    action.type = type;
    action.pos = pos;
    return action;
}

std::string defaultPosFor(ActionType type) {
    switch (type) {
        case ActionType::SPLIT:    return "sni";
        case ActionType::DISORDER: return "1";
        case ActionType::FAKE:     return "sni_end";
        case ActionType::TLS_REC:  return "sni";
        case ActionType::OOB:      return "sni";
    }
    return "sni";
}

bool parseActionType(const std::string& name, ActionType& type) {
    if (name == "split" || name == "multisplit") type = ActionType::SPLIT;
    else if (name == "disorder" || name == "multidisorder") type = ActionType::DISORDER;
    else if (name == "fake") type = ActionType::FAKE;
    else if (name == "tlsrec" || name == "tls_record_split" || name == "tls-record") type = ActionType::TLS_REC;
    else if (name == "oob" || name == "disoob") type = ActionType::OOB;
    else return false;
    return true;
}

TcpAction parseActionObject(const JsonValue& value, const TcpPlan& defaults) {
    TcpAction action;
    action.ttl = defaults.defaultTtl == 64 ? 8 : defaults.defaultTtl;
    action.payload = "tls_google";
    action.repeats = 1;

    if (value.isString()) {
        ActionType type = ActionType::SPLIT;
        parseActionType(value.stringValue, type);
        action.type = type;
        action.pos = defaultPosFor(type);
        return action;
    }

    const std::string name = getString(&value, "type",
        getString(&value, "action", "split"));
    if (!parseActionType(name, action.type)) {
        SKY_LOGW("Profile: unknown action type '%s'", name.c_str());
        action.type = ActionType::SPLIT;
    }
    action.pos = getString(&value, "pos", defaultPosFor(action.type));
    action.ttl = getInt(&value, "ttl", action.ttl);
    if (getTtlRange(value.find("ttl_range"), action.ttlRangeMin, action.ttlRangeMax)) {
        action.ttl = action.ttlRangeMin;
    }
    action.repeats = getInt(&value, "repeats", action.repeats);
    action.payload = getString(&value, "payload", action.payload);
    action.delayMs = getInt(&value, "delay_ms", defaults.defaultDelay);
    return action;
}

void parseExplicitOrder(const JsonValue& order, const JsonValue* object,
                        TcpPlan& plan) {
    std::size_t splitIndex = 0;
    const std::vector<std::string> splitPositions =
        positionList(object ? object->find("split_pos") : nullptr, "sni");
    const std::string disorderPos =
        getString(object, "disorder_pos", "1");
    const std::string tlsRecPos =
        getString(object, "tlsrec_pos", "sni");
    const std::string oobPos =
        getString(object, "oob_pos", "sni");
    const std::string fakePos =
        getString(object, "fake_pos", "sni_end");

    for (const auto& item : order.arrayValue) {
        const std::string name = item.isString() ? item.stringValue : std::string();
        ActionType type = ActionType::SPLIT;
        if (!parseActionType(name, type)) {
            SKY_LOGW("Profile: unknown action '%s'", name.c_str());
            continue;
        }
        TcpAction action;
        action.type = type;
        action.ttl = plan.defaultTtl == 64 ? 8 : plan.defaultTtl;
        action.delayMs = plan.defaultDelay;

        switch (type) {
            case ActionType::SPLIT:
                action.pos = splitIndex < splitPositions.size()
                                 ? splitPositions[splitIndex++]
                                 : defaultPosFor(type);
                break;
            case ActionType::DISORDER: action.pos = disorderPos; break;
            case ActionType::TLS_REC:  action.pos = tlsRecPos;  break;
            case ActionType::OOB:      action.pos = oobPos;     break;
            case ActionType::FAKE:
                action.pos = fakePos;
                if (object) {
                    const JsonValue* fake = object->find("fake");
                    action.ttl = getInt(fake, "ttl", action.ttl);
                    if (getTtlRange(fake ? fake->find("ttl_range") : nullptr,
                                    action.ttlRangeMin, action.ttlRangeMax)) {
                        action.ttl = action.ttlRangeMin;
                    }
                    action.payload = getString(fake, "payload", action.payload);
                    action.repeats = getInt(fake, "repeats", action.repeats);
                    action.delayMs = getInt(fake, "delay_ms", action.delayMs);
                }
                break;
        }
        plan.actions.push_back(std::move(action));
    }
}

void inferActions(const JsonValue& tcp, TcpPlan& plan) {
    const JsonValue* fake = tcp.find("fake");
    const JsonValue* split = tcp.find("split_pos");
    const JsonValue* disorder = tcp.find("disorder");
    const JsonValue* tlsrec = tcp.find("tls_record_split");
    const JsonValue* oob = tcp.find("oob");
    const bool multisplit = getBool(&tcp, "multisplit", false);

    if (fake) {
        TcpAction action;
        action.type = ActionType::FAKE;
        action.pos = getString(fake, "pos",
            split && split->isString() ? split->stringValue : "sni_end");
        action.ttl = getInt(fake, "ttl", 8);
        if (getTtlRange(fake->find("ttl_range"), action.ttlRangeMin,
                        action.ttlRangeMax)) {
            action.ttl = action.ttlRangeMin;
        }
        action.payload = getString(fake, "payload", "tls_google");
        action.repeats = getInt(fake, "repeats", 1);
        action.delayMs = getInt(fake, "delay_ms", plan.defaultDelay);
        plan.actions.push_back(std::move(action));
    }

    std::vector<std::string> splitPositions = positionList(split, "sni");
    if (multisplit && splitPositions.empty()) {
        splitPositions = {"1", "sni"};
    }
    for (const auto& pos : splitPositions) {
        plan.actions.push_back(makeAction(ActionType::SPLIT, pos));
    }

    for (const auto& pos : positionList(tlsrec, "sni")) {
        plan.actions.push_back(makeAction(ActionType::TLS_REC, pos));
    }

    if (disorder) {
        std::string pos = "1";
        if (disorder->isString()) pos = disorder->stringValue;
        else if (disorder->isNumber()) pos = numberToString(disorder->numberValue);
        plan.actions.push_back(makeAction(ActionType::DISORDER, pos));
    }

    if (oob) {
        std::string pos = "sni";
        if (oob->isString()) pos = oob->stringValue;
        else if (oob->isNumber()) pos = numberToString(oob->numberValue);
        plan.actions.push_back(makeAction(ActionType::OOB, pos));
    }
}

void parseTcpPlan(const JsonValue* tcp, TcpPlan& plan, int defaultTtl,
                  bool defaultNodelay, int defaultDelay) {
    plan.defaultTtl = defaultTtl;
    plan.tcpNodelay = defaultNodelay;
    plan.defaultDelay = defaultDelay;
    plan.valid = true;
    if (!tcp) return;

    if (tcp->isArray()) {
        for (const auto& item : tcp->arrayValue) {
            plan.actions.push_back(parseActionObject(item, plan));
        }
        return;
    }
    if (!tcp->isObject()) return;

    plan.tcpNodelay = getBool(tcp, "tcp_nodelay", defaultNodelay);
    plan.defaultTtl = getInt(tcp, "default_ttl", defaultTtl);
    plan.defaultDelay = getInt(tcp, "delay_ms", defaultDelay);

    const JsonValue* actions = tcp->find("actions");
    if (actions && actions->isArray()) {
        for (const auto& item : actions->arrayValue) {
            plan.actions.push_back(parseActionObject(item, plan));
        }
        return;
    }

    const JsonValue* order = tcp->find("order");
    if (order && order->isArray()) {
        parseExplicitOrder(*order, tcp, plan);
        return;
    }

    const JsonValue* desync = tcp->find("desync");
    if (desync && desync->isObject()) {
        inferActions(*desync, plan);
        return;
    }

    inferActions(*tcp, plan);
}

UdpPlan parseUdpPlan(const JsonValue* udp, int defaultTtl) {
    UdpPlan plan;
    plan.ttl = defaultTtl == 64 ? 8 : defaultTtl;
    plan.valid = true;
    if (!udp) return plan;

    if (udp->isObject()) {
        const JsonValue* fake = udp->find("fake");
        if (fake && fake->isObject()) {
            plan.fakeCount = getInt(fake, "count",
                getInt(fake, "repeats", 6));
            plan.ttl = getInt(fake, "ttl", plan.ttl);
            if (getTtlRange(fake->find("ttl_range"), plan.ttlRangeMin,
                            plan.ttlRangeMax)) {
                plan.ttl = plan.ttlRangeMin;
            }
            plan.payload = getString(fake, "payload", "quic_google");
        } else {
            plan.fakeCount = getInt(udp, "fake_count", 0);
            plan.ttl = getInt(udp, "ttl", plan.ttl);
            if (getTtlRange(udp->find("ttl_range"), plan.ttlRangeMin,
                            plan.ttlRangeMax)) {
                plan.ttl = plan.ttlRangeMin;
            }
            plan.payload = getString(udp, "payload", "quic_google");
        }
    } else if (udp->isString()) {
        plan.payload = udp->stringValue;
        plan.fakeCount = 1;
    }
    return plan;
}

void addHosts(HostList& list, const JsonValue* value) {
    if (!value) return;
    if (value->isString()) {
        list.addText(value->stringValue);
    } else if (value->isArray()) {
        for (const auto& item : value->arrayValue) {
            if (item.isString()) list.addText(item.stringValue);
        }
    }
}

void addRules(std::vector<HostRule>& rules, const JsonValue* value,
              const std::string& strategy) {
    if (!value) return;
    HostRule rule;
    rule.strategy = strategy;
    if (value->isString()) {
        rule.hosts.push_back(value->stringValue);
    } else if (value->isArray()) {
        for (const auto& item : value->arrayValue) {
            if (item.isString()) rule.hosts.push_back(item.stringValue);
        }
    }
    if (!rule.hosts.empty()) rules.push_back(std::move(rule));
}

}

std::shared_ptr<Profile> parseProfile(const std::string& text, std::string& error) {
    JsonValue root;
    if (!parseJson(text, root, error)) return nullptr;
    if (!root.isObject()) {
        error = "profile root must be an object";
        return nullptr;
    }

    auto profile = std::make_shared<Profile>();
    profile->updated = getString(&root, "updated", "");
    profile->socksPort = getInt(&root, "socks_port", 10800);
    profile->defaultTtl = getInt(&root, "default_ttl", 64);
    profile->tcpNodelay = getBool(&root, "tcp_nodelay", true);
    profile->directRu = getBool(&root, "direct_ru", true);
    profile->delayMs = getInt(&root, "delay_ms", 0);
    profile->quicFallback = getBool(&root, "quic_fallback", true);
    profile->directFirst = getBool(&root, "direct_first", false);
    profile->adaptiveChain = getBool(&root, "adaptive_chain", false);
    profile->adaptiveAttemptsPerMinute =
        std::clamp(getInt(&root, "adaptive_attempts_per_minute", 0), 0, 60);
    profile->maxStrategyAttempts =
        std::clamp(getInt(&root, "max_strategy_attempts", 10), 1, 16);

    const JsonValue* routing = root.find("routing");
    if (routing) {
        profile->directRu = getBool(routing, "direct_ru", profile->directRu);
        profile->quicFallback = getBool(routing, "quic_fallback", profile->quicFallback);
        addRules(profile->rules, routing->find("tunnel_domains"), "tunnel");
        addRules(profile->rules, routing->find("direct_domains"), "direct");
    }

    const JsonValue* defaults = root.find("defaults");
    if (defaults && defaults->isObject()) {
        profile->defaultTtl = getInt(defaults, "ttl", profile->defaultTtl);
        profile->tcpNodelay = getBool(defaults, "tcp_nodelay", profile->tcpNodelay);
        profile->delayMs = getInt(defaults, "delay_ms", profile->delayMs);

        const JsonValue* defaultTcp = defaults->find("tcp");
        if (defaultTcp && defaultTcp->isArray()) {
            profile->defaultTcp = defaultTcp->asStringList();
        }
        const JsonValue* defaultUdp = defaults->find("udp");
        if (defaultUdp && defaultUdp->isArray()) {
            profile->defaultUdp = defaultUdp->asStringList();
        }

        const JsonValue* desync = defaults->find("desync");
        if (desync && desync->isObject()) {
            StrategyDef strategy;
            strategy.id = "default";
            strategy.cost = 0;
            parseTcpPlan(desync, strategy.tcp, profile->defaultTtl,
                         profile->tcpNodelay, profile->delayMs);
            profile->strategies.push_back(std::move(strategy));
            profile->defaultTcp.insert(profile->defaultTcp.begin(), "default");
        }
    }

    const JsonValue* strategies = root.find("strategies");
    if (strategies && strategies->isArray()) {
        for (const auto& item : strategies->arrayValue) {
            if (!item.isObject()) continue;
            StrategyDef strategy;
            strategy.id = getString(&item, "id", "");
            if (strategy.id.empty()) {
                strategy.id = "strategy-" + std::to_string(profile->strategies.size());
            }
            strategy.cost = getInt(&item, "cost", 10);
            strategy.adaptive = getBool(&item, "adaptive", false);
            strategy.tunnel = getString(&item, "action", "") == "tunnel";
            strategy.direct = getString(&item, "action", "") == "direct";

            const JsonValue* tcp = item.find("tcp");
            if (!tcp) tcp = item.find("desync");
            parseTcpPlan(tcp, strategy.tcp, profile->defaultTtl,
                         profile->tcpNodelay, profile->delayMs);
            strategy.tcp.tcpNodelay = getBool(&item, "tcp_nodelay",
                                              strategy.tcp.tcpNodelay);
            strategy.tcp.defaultDelay = getInt(&item, "delay_ms",
                                               strategy.tcp.defaultDelay);

            strategy.udp = parseUdpPlan(item.find("udp"), profile->defaultTtl);
            if (!strategy.tunnel && !strategy.direct && strategy.tcp.actions.empty() &&
                strategy.udp.fakeCount == 0) {
                strategy.direct = true;
            }
            profile->strategies.push_back(std::move(strategy));
        }
    }

    const JsonValue* hosts = root.find("hosts");
    if (hosts) {
        addHosts(profile->include, hosts->find("include"));
        addHosts(profile->exclude, hosts->find("exclude"));
    }
    addHosts(profile->include, root.find("include"));
    addHosts(profile->exclude, root.find("exclude"));

    const JsonValue* rules = root.find("rules");
    if (rules && rules->isArray()) {
        for (const auto& item : rules->arrayValue) {
            if (!item.isObject()) continue;
            HostRule rule;
            rule.strategy = getString(&item, "strategy", getString(&item, "id", ""));
            const JsonValue* ruleHosts = item.find("hosts");
            if (!ruleHosts) ruleHosts = item.find("host");
            if (ruleHosts) {
                if (ruleHosts->isString()) rule.hosts.push_back(ruleHosts->stringValue);
                else if (ruleHosts->isArray()) rule.hosts = ruleHosts->asStringList();
            }
            if (!rule.hosts.empty() && !rule.strategy.empty()) {
                profile->rules.push_back(std::move(rule));
            }
        }
    }

    const JsonValue* domains = root.find("domains");
    if (domains && domains->isObject()) {
        addRules(profile->rules, domains->find("direct"), "direct");
        addRules(profile->rules, domains->find("tunnel"), "tunnel");
    }

    if (profile->strategies.empty()) {
        error = "profile has no strategies";
        return nullptr;
    }

    error.clear();
    return profile;
}

const StrategyDef* Profile::findStrategy(const std::string& id) const {
    for (const auto& strategy : strategies) {
        if (strategy.id == id) return &strategy;
    }
    return nullptr;
}

std::string Profile::ruleStrategy(const std::string& host) const {
    if (host.empty()) return {};
    for (const auto& rule : rules) {
        for (const auto& pattern : rule.hosts) {
            HostList one;
            one.add(pattern);
            if (one.matches(host)) return rule.strategy;
        }
    }
    return {};
}

std::shared_ptr<Profile> defaultProfile() {
    auto profile = std::make_shared<Profile>();
    profile->updated = "builtin";
    profile->defaultTtl = 64;
    profile->tcpNodelay = true;
    profile->delayMs = 0;

    auto add = [&profile](const std::string& id, int cost,
                          const std::vector<TcpAction>& actions,
                          bool direct = false) {
        StrategyDef strategy;
        strategy.id = id;
        strategy.cost = cost;
        strategy.direct = direct;
        strategy.tcp.defaultTtl = profile->defaultTtl;
        strategy.tcp.tcpNodelay = profile->tcpNodelay;
        strategy.tcp.actions = actions;
        strategy.tcp.valid = true;
        strategy.udp.valid = true;
        profile->strategies.push_back(std::move(strategy));
    };

    {
        TcpAction fake;
        fake.type = ActionType::FAKE;
        fake.pos = "sni_end";
        fake.ttl = 8;
        fake.payload = "tls_google";
        TcpAction split;
        split.type = ActionType::SPLIT;
        split.pos = "sni_end";
        add("fake-sni", 1, {fake, split});
    }
    {
        TcpAction action;
        action.type = ActionType::SPLIT;
        action.pos = "sni";
        add("split-sni", 2, {action});
    }
    {
        TcpAction action;
        action.type = ActionType::SPLIT;
        action.pos = "1";
        add("split-1", 3, {action});
    }
    {
        TcpAction first;
        first.type = ActionType::SPLIT;
        first.pos = "1";
        TcpAction second;
        second.type = ActionType::SPLIT;
        second.pos = "8";
        second.delayMs = 2;
        TcpAction third;
        third.type = ActionType::SPLIT;
        third.pos = "56";
        third.delayMs = 2;
        add("mtproto-obfs-split", 2, {first, second, third});
    }
    {
        TcpAction action;
        action.type = ActionType::TLS_REC;
        action.pos = "sni";
        add("tlsrec-sni", 4, {action});
    }
    {
        TcpAction action;
        action.type = ActionType::DISORDER;
        action.pos = "1";
        add("disorder-1", 5, {action});
    }
    add("direct", 99, {}, true);

    {
        StrategyDef strategy;
        strategy.id = "fake-quic";
        strategy.cost = 1;
        strategy.tcp.valid = true;
        strategy.udp.valid = true;
        strategy.udp.fakeCount = 6;
        strategy.udp.ttl = 8;
        strategy.udp.payload = "quic_google";
        profile->strategies.push_back(std::move(strategy));
        profile->defaultUdp = {"fake-quic"};
    }

    profile->defaultTcp = {"fake-sni", "split-sni", "split-1", "tlsrec-sni", "disorder-1"};
    return profile;
}

}
