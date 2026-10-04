def read_value():
    return 1


def read_a():
    return 1


def read_b():
    return 2


class Profile:
    interest = None

    def __init__(self, interest):
        self.interest = interest

    def set_interest(self, new_interest):
        self.interest = new_interest

    def get_interest(self):
        return self.interest


class Box:
    value = None

    def __init__(self, value):
        self.value = value


class NewBox:
    created_value = None

    def __new__(cls, value):
        instance = super().__new__(cls)
        instance.created_value = value
        return instance


class State:
    dynamic_state = None


class Link:
    next = None
    value = 0


def dynamic_roles(collection, parent, fixed_step, unpredictable, head, start, condition, source_a):
    literal_stepper = 0
    fixed_stepper = 0
    anchor_stepper = 0
    dependent_stepper = 0
    while condition:
        literal_stepper += 1
        fixed_stepper += fixed_step
        anchor_stepper += 1
        dependent_stepper += anchor_stepper
        break

    dynamic_update = 0
    dynamic_update += collection[0]

    cycle_a = 0
    cycle_b = 0
    cycle_a += cycle_b
    cycle_b += cycle_a

    toggled = 0
    toggled = 1 - toggled

    fixed_guard_stepper = 0
    self_guard_stepper = 0
    guard_anchor = 0
    dependent_guard_stepper = 0
    while condition:
        if fixed_step:
            fixed_guard_stepper += 1
        if self_guard_stepper < 10:
            self_guard_stepper += fixed_step
        guard_anchor += 1
        if guard_anchor < 10:
            dependent_guard_stepper += 1
        break

    dynamic_flag = bool(read_value())
    dynamic_guard_stepper = 0
    if dynamic_flag:
        dynamic_guard_stepper += 1

    state = State()
    state.dynamic_state = bool(read_value())
    member_guard_stepper = 0
    if state.dynamic_state:
        member_guard_stepper += 1

    call_guard_stepper = 0
    if read_value():
        call_guard_stepper += 1

    indexed_guard_stepper = 0
    if collection[start]:
        indexed_guard_stepper += 1

    one_off_correction = read_value()
    one_off_correction *= 100
    print(one_off_correction)

    for numeric_index in range(len(collection)):
        print(collection[numeric_index])

    for element in collection:
        print(element)

    pointer = head
    while pointer:
        pointer = pointer.next
        print(pointer.value)

    node = start
    while node >= 0:
        node = parent[node]
        print(parent[node])

    current = None
    while condition:
        if source_a:
            current = read_a()
        else:
            current = read_b()
        print(current)

    corrected_time = 0
    while condition:
        corrected_time = read_value()
        corrected_time = corrected_time * 1000
        print(corrected_time)

    dynamic_correction = 0
    while condition:
        dynamic_correction = read_value()
        dynamic_correction += collection[0]
        print(dynamic_correction)

    conditional_correction = 0
    while condition:
        conditional_correction = read_value()
        if conditional_correction < 0:
            conditional_correction = -conditional_correction
        print(conditional_correction)

    shadowed_acquisition = 0
    while condition:
        shadowed_acquisition = read_a()
        shadowed_acquisition = read_b()
        print(shadowed_acquisition)

    best = collection[0]
    for candidate in collection:
        if candidate > best:
            best = candidate
    print(best)

    closest = 0
    for candidate_index in range(len(collection)):
        if collection[candidate_index] - fixed_step < collection[closest] - fixed_step:
            closest = candidate_index
    print(closest)

    max_profit = 0
    max_buy = 0
    max_sell = 0
    for buy_day in range(len(collection)):
        for sell_day in range(buy_day, len(collection)):
            profit = collection[sell_day] - collection[buy_day]
            if profit > max_profit:
                max_profit = profit
                max_buy = buy_day
                max_sell = sell_day
    print(max_profit, max_buy, max_sell)

    isolated = input()
    print(isolated)
