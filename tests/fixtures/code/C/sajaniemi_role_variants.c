#include <stddef.h>

int role_variants(const int *values, size_t length) {
    const int fixed_value = 7;
    int stepper_plus_equal = 0;
    int stepper_assignment = 0;
    int gatherer_total = 0;
    int most_wanted_holder = values[0];
    int best_minimum = values[0];

    while (stepper_plus_equal < (int)length) {
        stepper_plus_equal += 1;
    }

    while (stepper_assignment < (int)length) {
        stepper_assignment = stepper_assignment + 1;
    }

    for (size_t index = 0; index < length; index += 1) {
        int value = values[index];
        gatherer_total += value;
        if (value > most_wanted_holder) {
            most_wanted_holder = value;
        }
        if (value < best_minimum) {
            best_minimum = value;
        }
    }

    return fixed_value + gatherer_total + most_wanted_holder + best_minimum;
}
