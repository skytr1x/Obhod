#include "json.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <sstream>
#include <utility>

namespace skyunblock {

namespace {

class Parser {
public:
    explicit Parser(const std::string& text) : m_text(text) {}

    bool parse(JsonValue& out, std::string& error) {
        skipWhitespace();
        if (!parseValue(out)) {
            error = m_error.empty() ? "invalid JSON" : m_error;
            return false;
        }
        skipWhitespace();
        if (m_pos != m_text.size()) {
            error = "trailing characters at offset " + std::to_string(m_pos);
            return false;
        }
        return true;
    }

private:
    const std::string& m_text;
    std::size_t m_pos = 0;
    std::string m_error;

    void skipWhitespace() {
        while (m_pos < m_text.size()) {
            const char c = m_text[m_pos];
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                ++m_pos;
            } else if (c == '/' && m_pos + 1 < m_text.size() && m_text[m_pos + 1] == '/') {
                // Tolerate // comments in hand-edited profiles.
                m_pos += 2;
                while (m_pos < m_text.size() && m_text[m_pos] != '\n') ++m_pos;
            } else if (c == '/' && m_pos + 1 < m_text.size() && m_text[m_pos + 1] == '*') {
                m_pos += 2;
                while (m_pos + 1 < m_text.size() &&
                       !(m_text[m_pos] == '*' && m_text[m_pos + 1] == '/')) {
                    ++m_pos;
                }
                if (m_pos + 1 < m_text.size()) m_pos += 2;
            } else {
                break;
            }
        }
    }

    bool fail(const std::string& message) {
        if (m_error.empty()) m_error = message + " at offset " + std::to_string(m_pos);
        return false;
    }

    bool parseValue(JsonValue& out) {
        skipWhitespace();
        if (m_pos >= m_text.size()) return fail("unexpected end of input");

        const char c = m_text[m_pos];
        switch (c) {
            case '{': return parseObject(out);
            case '[': return parseArray(out);
            case '"': {
                std::string s;
                if (!parseString(s)) return false;
                out = JsonValue(std::move(s));
                return true;
            }
            case 't':
                if (match("true")) { out = JsonValue(true); return true; }
                return fail("invalid literal");
            case 'f':
                if (match("false")) { out = JsonValue(false); return true; }
                return fail("invalid literal");
            case 'n':
                if (match("null")) { out = JsonValue(); return true; }
                return fail("invalid literal");
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return parseNumber(out);
                return fail("unexpected character");
        }
    }

    bool match(const char* literal) {
        const std::size_t len = std::char_traits<char>::length(literal);
        if (m_text.compare(m_pos, len, literal) != 0) return false;
        m_pos += len;
        return true;
    }

    bool parseObject(JsonValue& out) {
        ++m_pos; // {
        out.type = JsonValue::Type::Object;
        skipWhitespace();
        if (m_pos < m_text.size() && m_text[m_pos] == '}') {
            ++m_pos;
            return true;
        }
        while (m_pos < m_text.size()) {
            skipWhitespace();
            std::string key;
            if (!parseString(key)) return false;
            skipWhitespace();
            if (m_pos >= m_text.size() || m_text[m_pos] != ':') return fail("expected ':'");
            ++m_pos;
            JsonValue value;
            if (!parseValue(value)) return false;
            out.objectValue[std::move(key)] = std::move(value);
            skipWhitespace();
            if (m_pos >= m_text.size()) return fail("unterminated object");
            if (m_text[m_pos] == ',') {
                ++m_pos;
                continue;
            }
            if (m_text[m_pos] == '}') {
                ++m_pos;
                return true;
            }
            return fail("expected ',' or '}'");
        }
        return fail("unterminated object");
    }

    bool parseArray(JsonValue& out) {
        ++m_pos; // [
        out.type = JsonValue::Type::Array;
        skipWhitespace();
        if (m_pos < m_text.size() && m_text[m_pos] == ']') {
            ++m_pos;
            return true;
        }
        while (m_pos < m_text.size()) {
            JsonValue value;
            if (!parseValue(value)) return false;
            out.arrayValue.push_back(std::move(value));
            skipWhitespace();
            if (m_pos >= m_text.size()) return fail("unterminated array");
            if (m_text[m_pos] == ',') {
                ++m_pos;
                continue;
            }
            if (m_text[m_pos] == ']') {
                ++m_pos;
                return true;
            }
            return fail("expected ',' or ']'");
        }
        return fail("unterminated array");
    }

    bool parseString(std::string& out) {
        if (m_pos >= m_text.size() || m_text[m_pos] != '"') return fail("expected string");
        ++m_pos;
        out.clear();
        while (m_pos < m_text.size()) {
            const unsigned char c = static_cast<unsigned char>(m_text[m_pos++]);
            if (c == '"') return true;
            if (c == '\\') {
                if (m_pos >= m_text.size()) return fail("unterminated escape");
                const char e = m_text[m_pos++];
                switch (e) {
                    case '"': out.push_back('"'); break;
                    case '\\': out.push_back('\\'); break;
                    case '/': out.push_back('/'); break;
                    case 'b': out.push_back('\b'); break;
                    case 'f': out.push_back('\f'); break;
                    case 'n': out.push_back('\n'); break;
                    case 'r': out.push_back('\r'); break;
                    case 't': out.push_back('\t'); break;
                    case 'u': {
                        unsigned int cp = 0;
                        if (!parseHex4(cp)) return false;
                        if (cp >= 0xD800 && cp <= 0xDBFF) {
                            // Surrogate pair.
                            if (m_pos + 1 < m_text.size() && m_text[m_pos] == '\\' &&
                                m_text[m_pos + 1] == 'u') {
                                m_pos += 2;
                                unsigned int low = 0;
                                if (!parseHex4(low)) return false;
                                if (low >= 0xDC00 && low <= 0xDFFF) {
                                    cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                                }
                            }
                        }
                        appendUtf8(out, cp);
                        break;
                    }
                    default:
                        return fail("invalid escape");
                }
            } else {
                if (c < 0x20) return fail("control character in string");
                out.push_back(static_cast<char>(c));
            }
        }
        return fail("unterminated string");
    }

    bool parseHex4(unsigned int& value) {
        if (m_pos + 4 > m_text.size()) return fail("short unicode escape");
        value = 0;
        for (int i = 0; i < 4; ++i) {
            const char c = m_text[m_pos++];
            value <<= 4;
            if (c >= '0' && c <= '9') value |= static_cast<unsigned int>(c - '0');
            else if (c >= 'a' && c <= 'f') value |= static_cast<unsigned int>(c - 'a' + 10);
            else if (c >= 'A' && c <= 'F') value |= static_cast<unsigned int>(c - 'A' + 10);
            else return fail("invalid unicode escape");
        }
        return true;
    }

    static void appendUtf8(std::string& out, unsigned int cp) {
        if (cp <= 0x7F) {
            out.push_back(static_cast<char>(cp));
        } else if (cp <= 0x7FF) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp <= 0xFFFF) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }

    bool parseNumber(JsonValue& out) {
        const std::size_t start = m_pos;
        if (m_text[m_pos] == '-') ++m_pos;
        if (m_pos >= m_text.size()) return fail("invalid number");
        if (m_text[m_pos] == '0') {
            ++m_pos;
        } else if (m_text[m_pos] >= '1' && m_text[m_pos] <= '9') {
            while (m_pos < m_text.size() && m_text[m_pos] >= '0' && m_text[m_pos] <= '9') ++m_pos;
        } else {
            return fail("invalid number");
        }
        if (m_pos < m_text.size() && m_text[m_pos] == '.') {
            ++m_pos;
            if (m_pos >= m_text.size() || m_text[m_pos] < '0' || m_text[m_pos] > '9') {
                return fail("invalid number");
            }
            while (m_pos < m_text.size() && m_text[m_pos] >= '0' && m_text[m_pos] <= '9') ++m_pos;
        }
        if (m_pos < m_text.size() && (m_text[m_pos] == 'e' || m_text[m_pos] == 'E')) {
            ++m_pos;
            if (m_pos < m_text.size() && (m_text[m_pos] == '+' || m_text[m_pos] == '-')) ++m_pos;
            if (m_pos >= m_text.size() || m_text[m_pos] < '0' || m_text[m_pos] > '9') {
                return fail("invalid number");
            }
            while (m_pos < m_text.size() && m_text[m_pos] >= '0' && m_text[m_pos] <= '9') ++m_pos;
        }

        const std::string token = m_text.substr(start, m_pos - start);
        char* endp = nullptr;
        const double value = std::strtod(token.c_str(), &endp);
        if (!endp || *endp != '\0' || !std::isfinite(value)) return fail("invalid number");
        out = JsonValue(value);
        return true;
    }
};

} // namespace

const JsonValue* JsonValue::find(const std::string& key) const {
    if (type != Type::Object) return nullptr;
    auto it = objectValue.find(key);
    return it == objectValue.end() ? nullptr : &it->second;
}

const JsonValue* JsonValue::at(std::size_t index) const {
    if (type != Type::Array || index >= arrayValue.size()) return nullptr;
    return &arrayValue[index];
}

std::string JsonValue::asString(const std::string& defaultValue) const {
    return type == Type::String ? stringValue : defaultValue;
}

int JsonValue::asInt(int defaultValue) const {
    if (type != Type::Number) return defaultValue;
    return static_cast<int>(numberValue);
}

bool JsonValue::asBool(bool defaultValue) const {
    if (type == Type::Bool) return boolValue;
    if (type == Type::Number) return numberValue != 0.0;
    return defaultValue;
}

std::vector<std::string> JsonValue::asStringList() const {
    std::vector<std::string> out;
    if (type == Type::String) {
        out.push_back(stringValue);
    } else if (type == Type::Array) {
        for (const auto& item : arrayValue) {
            if (item.type == Type::String) out.push_back(item.stringValue);
            else if (item.type == Type::Number) {
                std::ostringstream ss;
                ss << static_cast<long long>(item.numberValue);
                out.push_back(ss.str());
            }
        }
    }
    return out;
}

bool parseJson(const std::string& text, JsonValue& out, std::string& error) {
    Parser parser(text);
    out = JsonValue();
    return parser.parse(out, error);
}

std::string jsonEscape(const std::string& value) {
    std::string out;
    out.reserve(value.size() + 8);
    for (unsigned char c : value) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[7];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out.push_back(static_cast<char>(c));
                }
        }
    }
    return out;
}

} // namespace skyunblock
