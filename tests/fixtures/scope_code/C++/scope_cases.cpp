class ScopeBox {
public:
    ScopeBox(int seed)
        : stored_{seed}, values_{seed, seed + 1} {
        int constructor_local = stored_;
    }

    int method(int value) {
        int shared_name = value;
        return shared_name;
    }

private:
    int stored_;
    int values_[2];
};

int cpp_prototype(int cpp_prototype_value);

extern "C" int cpp_external_definition(int external_value) {
    int external_local = external_value;
    return external_local;
}

[[nodiscard]] inline int cpp_attributed_definition(int attributed_value) {
    int attributed_local = attributed_value;
    return attributed_local;
}

#define SCOPE_API_EXPORT
SCOPE_API_EXPORT int cpp_exported_definition(int exported_value) {
    int exported_local = exported_value;
    return exported_local;
}

struct Result { int value; };
struct Input { int value; };

int previous();
extern "C" int parse(const char *text) {
    int parse_result = text ? 1 : 0;
    return parse_result;
}

[[nodiscard]] inline Result parse(Input input) {
    Result attributed_result{input.value};
    return attributed_result;
}

#define API_EXPORT
API_EXPORT Result parse_exported(Input input) {
    Result exported_result{input.value};
    return exported_result;
}

int lambda_owner(int seed) {
    int enclosing_value = seed;
    auto captured = [enclosing_value](int delta) {
        int lambda_local = enclosing_value + delta;
        return lambda_local;
    };
    auto first = [](int x) { int first_local = x; return first_local; }; auto second = [](int x) { int second_local = x; return second_local; };
    return captured(seed) + first(seed) + second(seed);
}
