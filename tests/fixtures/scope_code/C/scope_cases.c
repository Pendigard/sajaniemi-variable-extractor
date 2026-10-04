#define FUNCTION_LIKE(value) ((value) + 1)

int c_global_value = 3;

static int c_static_definition(int static_value) {
    int static_local = static_value;
    return static_local;
}

int c_prototype(int prototype_value);
int c_multiline_prototype(
    int multiline_value,
    int multiline_increment
);

int c_function(int value) {
    int shared_name = value;
    return shared_name;
}

int c_other_function(int value) {
    int shared_name = value + 1;
    return shared_name;
}

int previous_c_callable(int value) { return value; }
static int compute(int value) {
    int compute_result = value;
    return compute_result;
}

#define UNRELATED_MACRO 1

static int current_after_macro(int value) {
    int macro_separated_result = value;
    return macro_separated_result;
}

int first_same_line(int value) { return value; } int second_same_line(int value) {
    int second_line_result = value;
    return second_line_result;
}
