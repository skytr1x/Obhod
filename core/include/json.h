#ifndef SKY_JSON_H
#define SKY_JSON_H

#include <map>
#include <string>
#include <vector>

namespace skyunblock {

class JsonValue {
public:
    enum class Type { Null, Bool, Number, String, Array, Object };

    Type type = Type::Null;
    bool boolValue = false;
    double numberValue = 0.0;
    std::string stringValue;
    std::vector<JsonValue> arrayValue;
    std::map<std::string, JsonValue> objectValue;

    JsonValue() = default;
    explicit JsonValue(bool v) : type(Type::Bool), boolValue(v) {}
    explicit JsonValue(double v) : type(Type::Number), numberValue(v) {}
    explicit JsonValue(std::string v) : type(Type::String), stringValue(std::move(v)) {}

    bool isNull() const { return type == Type::Null; }
    bool isObject() const { return type == Type::Object; }
    bool isArray() const { return type == Type::Array; }
    bool isString() const { return type == Type::String; }
    bool isNumber() const { return type == Type::Number; }
    bool isBool() const { return type == Type::Bool; }

    const JsonValue* find(const std::string& key) const;

    std::vector<std::string> asStringList() const;
};

bool parseJson(const std::string& text, JsonValue& out, std::string& error);

std::string jsonEscape(const std::string& value);

}

#endif
