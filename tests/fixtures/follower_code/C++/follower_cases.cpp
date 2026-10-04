#include <vector>

static void observe(int value) {
    (void)value;
}

int direct_before(const std::vector<int>& values) {
    int current_before = 0;
    int previous_before = 0;
    for (int value : values) {
        previous_before = current_before;
        current_before = value;
        observe(previous_before);
    }
    return previous_before;
}

class Tracker {
public:
    Tracker() : previous_member(0), current_member(0), reset_member(0) {}

    void update(int value) {
        previous_member = current_member;
        current_member = value;
        reset_member = current_member;
    }

    void reset() {
        reset_member = 0;
    }

    int get_previous() const {
        return previous_member;
    }

private:
    int previous_member;
    int current_member;
    int reset_member;
};
