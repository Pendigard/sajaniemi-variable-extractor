#include <stddef.h>

typedef struct Tracker {
    int previous_member;
    int current_member;
    int reset_member;
} Tracker;

static void observe(int value) {
    (void)value;
}

int direct_before(const int *values, size_t count) {
    int current_before = 0;
    int previous_before = 0;
    for (size_t i = 0; i < count; ++i) {
        previous_before = current_before;
        current_before = values[i];
        observe(previous_before);
    }
    return previous_before;
}

int direct_after(const int *values, size_t count) {
    int current_after = 0;
    int previous_after = -1;
    for (size_t i = 0; i < count; ++i) {
        current_after = values[i];
        observe(previous_after);
        previous_after = current_after;
    }
    return previous_after;
}

void tracker_init(Tracker *tracker) {
    tracker->previous_member = 0;
    tracker->current_member = 0;
    tracker->reset_member = 0;
}

void tracker_update(Tracker *tracker, int value) {
    tracker->previous_member = tracker->current_member;
    tracker->current_member = value;
    tracker->reset_member = tracker->current_member;
}

void tracker_reset(Tracker *tracker) {
    tracker->reset_member = 0;
}

int tracker_get_previous(const Tracker *tracker) {
    return tracker->previous_member;
}

int no_evolution(const int *values, size_t count) {
    int master_static = 1;
    int follower_static = 0;
    for (size_t i = 0; i < count; ++i) {
        follower_static = master_static;
    }
    return follower_static;
}
