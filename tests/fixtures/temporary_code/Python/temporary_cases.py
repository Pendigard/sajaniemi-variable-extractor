def consume(value):
    return value


def transform(value):
    return value


module_value = transform(1)
consume(module_value)


class DeclarativeBody:
    descriptor_field = transform(1)
    consume(descriptor_field)
    class_attribute = transform(2)
    consume(class_attribute)

    def __init__(self, value):
        self.instance_member = transform(value)
        consume(self.instance_member)

    def executable_method(self, value):
        method_temporary = transform(value)
        consume(method_temporary)


def positive_single(source):
    temp_single = transform(source)
    consume(temp_single)


def positive_two_writes(first, second):
    temp_two_writes = transform(first)
    consume(temp_two_writes)
    temp_two_writes = transform(second)
    consume(temp_two_writes)


def negative_invalid_second(first):
    invalid_second = transform(first)
    consume(invalid_second)
    invalid_second = 0
    consume(invalid_second)


def negative_updates(value):
    plus_equal = transform(value)
    consume(plus_equal)
    plus_equal += value
    consume(plus_equal)
    self_left = transform(value)
    consume(self_left)
    self_left = self_left + value
    consume(self_left)
    self_right = transform(value)
    consume(self_right)
    self_right = value + self_right
    consume(self_right)


def positive_same_line(value):
    same_line = transform(value); consume(same_line)


def positive_minified_intervals(first, second):
    minified_intervals = transform(first); consume(minified_intervals); minified_intervals = transform(second); consume(minified_intervals)


def negative_artifacts(value):
    function_alias = lambda item: transform(item)
    consume(function_alias)
    indirect_items = transform(value)
    consume(indirect_items)
    indirect_items[0] = value
    consume(indirect_items)


def negative_unread_intervals(first, second):
    first_write_unread = transform(first)
    first_write_unread = transform(second)
    consume(first_write_unread)
    last_write_unread = transform(first)
    consume(last_write_unread)
    last_write_unread = transform(second)


def negative_branch_reachability(condition, first, second):
    if condition:
        branch_unread = transform(first)
    else:
        branch_unread = transform(second)
        return second
    consume(branch_unread)


def positive_five_reads(value):
    five_reads = transform(value)
    consume(five_reads)
    consume(five_reads)
    consume(five_reads)
    consume(five_reads)
    consume(five_reads)


def negative_six_reads(value):
    six_reads = transform(value)
    consume(six_reads)
    consume(six_reads)
    consume(six_reads)
    consume(six_reads)
    consume(six_reads)
    consume(six_reads)


def positive_distance_five(value):
    distance_five = transform(value)
    consume(value)
    consume(value)
    consume(value)
    consume(value)
    consume(distance_five)


def negative_distance_six(value):
    distance_six = transform(value)
    consume(value)
    consume(value)
    consume(value)
    consume(value)
    consume(value)
    consume(distance_six)


def negative_one_long_interval(first, second):
    one_long_interval = transform(first)
    consume(one_long_interval)
    one_long_interval = transform(second)
    consume(first)
    consume(first)
    consume(first)
    consume(first)
    consume(first)
    consume(one_long_interval)


def positive_loop(items):
    for item in items:
        loop_fresh = transform(item)
        consume(loop_fresh)


def negative_loop_old_value(items, initial):
    loop_old = initial
    for item in items:
        consume(loop_old)
        loop_old = transform(item)


def negative_loop_conditional(items):
    for item in items:
        if item:
            loop_conditional = transform(item)
        consume(loop_conditional)


def positive_loop_two_branches(items):
    for item in items:
        if item:
            branch_complete = transform(item)
        else:
            branch_complete = transform(0)
        consume(branch_complete)


def negative_loop_missing_branch(items):
    for item in items:
        if item:
            branch_missing = transform(item)
        consume(branch_missing)


def negative_continue_before(items):
    for item in items:
        if item is None:
            continue
        continue_before = transform(item)
        consume(continue_before)


def positive_continue_after(items):
    for item in items:
        continue_after = transform(item)
        if item is None:
            continue
        consume(continue_after)


def negative_nested_loop(groups):
    nested_escape = transform(groups)
    for group in groups:
        for item in group:
            nested_escape = transform(item)
        consume(nested_escape)


def conflicts(values):
    temporary_fixed = transform(values)
    consume(temporary_fixed)
    stepper_conflict = 0
    while stepper_conflict < len(values):
        stepper_conflict += 1
    gatherer_conflict = 0
    for value in values:
        gatherer_conflict += transform(value)
    recent_conflict = None
    for value in values:
        recent_conflict = transform(value)
        consume(recent_conflict)
