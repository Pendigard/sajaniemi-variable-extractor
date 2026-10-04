class Box:
    def __init__(self, value):
        self.value = value
        self.other = value


class NestedBox:
    def __init__(self, value):
        self.inner = Box(value)


def transform(value):
    return value


def observe(value):
    return value


def direct_before(values):
    current_before = 0
    previous_before = None
    for value in values:
        previous_before = current_before
        current_before = value
        observe(previous_before)
    return previous_before


def direct_after(values):
    current_after = 0
    previous_after = 0
    for value in values:
        current_after = value
        observe(previous_after)
        previous_after = current_after
    return previous_after


def multiple_sites(values):
    current_multiple = 0
    previous_multiple = -1
    for value in values:
        if value > 0:
            previous_multiple = current_multiple
        if value <= 0:
            previous_multiple = current_multiple
        current_multiple = value
        observe(previous_multiple)
    return previous_multiple


def multiple_loops(left, right):
    current_loops = 0
    previous_loops = None
    for value in left:
        previous_loops = current_loops
        current_loops = value
        observe(previous_loops)
    for value in right:
        current_loops = value
        observe(previous_loops)
        previous_loops = current_loops
    return previous_loops


def fixed_offsets(values):
    fixed_offset = 3
    current_plus = 0
    previous_plus = None
    current_reverse_plus = 0
    previous_reverse_plus = None
    current_minus = 0
    previous_minus = None
    current_fixed_offset = 0
    previous_fixed_offset = None
    for value in values:
        previous_plus = current_plus + 1
        current_plus = value
        previous_reverse_plus = 2 + current_reverse_plus
        current_reverse_plus = value
        previous_minus = current_minus - 1
        current_minus = value
        previous_fixed_offset = current_fixed_offset + fixed_offset
        current_fixed_offset = value
        observe(previous_plus)
        observe(previous_reverse_plus)
        observe(previous_minus)
        observe(previous_fixed_offset)
    return previous_plus, previous_reverse_plus, previous_minus, previous_fixed_offset


def projections(values):
    field_root = Box(0)
    previous_field = None
    indexed_root = [0]
    previous_indexed_literal = None
    fixed_index = 0
    indexed_fixed_root = [0]
    previous_indexed_fixed = None
    for value in values:
        previous_field = field_root.value
        field_root = Box(value)
        previous_indexed_literal = indexed_root[0]
        indexed_root = [value]
        previous_indexed_fixed = indexed_fixed_root[fixed_index]
        indexed_fixed_root = [value]
        observe(previous_field)
        observe(previous_indexed_literal)
        observe(previous_indexed_fixed)
    return previous_field, previous_indexed_literal, previous_indexed_fixed


def recursive_projections(values):
    nested_root = NestedBox(0)
    previous_nested = None
    mixed_root = [Box(0)]
    previous_mixed = None
    sibling_root = Box(0)
    sibling_follower = None
    different_index_root = [0, 0]
    different_index_follower = None
    for value in values:
        previous_nested = nested_root.inner.value
        nested_root.inner.value = value
        observe(previous_nested)

        previous_mixed = mixed_root[0].value
        mixed_root[0].value = value
        observe(previous_mixed)

        sibling_follower = sibling_root.value
        sibling_root.other = value
        observe(sibling_follower)

        different_index_follower = different_index_root[0]
        different_index_root[1] = value
        observe(different_index_follower)
    return previous_nested, previous_mixed, sibling_follower, different_index_follower


def negative_rhs(values):
    first_master = 0
    second_master = 0
    two_sources = None
    dynamic_offset = 0
    dynamic_offset_follower = None
    fixed_left = None
    multiplied = None
    transformed = None
    duplicated_source = None
    self_dependent = 0
    mutated_follower = []
    for value in values:
        two_sources = first_master if value > 0 else second_master
        first_master = value
        second_master = -value
        dynamic_offset_follower = first_master + dynamic_offset
        dynamic_offset = value
        fixed_left = 1 - first_master
        multiplied = first_master * 2
        transformed = transform(first_master)
        duplicated_source = first_master + first_master
        self_dependent = first_master + self_dependent
        mutated_follower = first_master
        mutated_follower.append(value)
    return two_sources, dynamic_offset_follower, fixed_left, multiplied, transformed, duplicated_source, self_dependent


def negative_context(values):
    no_evolution_master = 1
    no_evolution = None
    outside_master = 0
    outside_write = None
    dynamic_index = 0
    indexed_root = [0, 1]
    dynamic_index_follower = None
    compound_index_follower = None
    for value in values:
        no_evolution = no_evolution_master
        outside_write = outside_master
        outside_master = value
        dynamic_index_follower = indexed_root[dynamic_index]
        compound_index_follower = indexed_root[dynamic_index + 1]
        dynamic_index = value
        indexed_root = [value, value]
    outside_write = outside_master
    return no_evolution, outside_write, dynamic_index_follower, compound_index_follower


class Tracker:
    def __init__(self):
        self.previous_member = None
        self.current_member = 0
        self.reset_member = None

    def update(self, value):
        self.previous_member = self.current_member
        self.current_member = value
        self.reset_member = self.current_member

    def reset(self):
        self.reset_member = 0

    def get_previous(self):
        return self.previous_member


class OtherTracker:
    def __init__(self):
        self.previous_member = None
        self.current_member = 0

    def update(self, value):
        self.previous_member = self.current_member
        self.current_member = value

    def get_previous(self):
        return self.previous_member


class MemberCopyNegatives:
    def __init__(self):
        self.current_only = None
        self.previous_parameter = None
        self.price = None
        self.external_without_transition = None

    def set_current(self, new_value):
        self.current_only = new_value

    def copy_parameter(self, input_parameter):
        self.previous_parameter = input_parameter

    def acquire_price(self, result):
        self.price = result["price"]

    def copy_for_external_read(self, input_parameter):
        self.external_without_transition = input_parameter

    def get_external_without_transition(self):
        return self.external_without_transition


def cycle_negatives(values):
    no_post_master = None
    no_post_master_source = 0
    read_before_only = None
    read_before_source = 0
    overwritten_before_read = None
    overwritten_source = 0
    dependent_follower = None
    dependent_master = 0
    guarded_follower = None
    guarded_master = 0
    ancestor_guarded = None
    ancestor_master = 0
    mutating_master = [0]
    mutating_follower = None
    transitive_master = 0
    transitive_follower = None
    for value in values:
        no_post_master = no_post_master_source
        no_post_master_source = value

        read_before_only = read_before_source
        observe(read_before_only)
        read_before_source = value

        overwritten_before_read = overwritten_source
        overwritten_source = value
        overwritten_before_read = overwritten_source
        observe(overwritten_before_read)

        dependent_follower = dependent_master
        dependent_master = dependent_follower + 1
        observe(dependent_follower)

        if guarded_follower is not None:
            guarded_follower = guarded_master
        guarded_master = value
        observe(guarded_follower)

        if ancestor_guarded is not None:
            if value > 0:
                ancestor_guarded = ancestor_master
        ancestor_master = value
        observe(ancestor_guarded)

        mutating_follower = mutating_master[0]
        mutating_master.append(mutating_follower)
        observe(mutating_follower)

        transitive_follower = transitive_master
        transitive_tmp = transitive_follower + 1
        transitive_master = transitive_tmp
        observe(transitive_follower)
    return (
        no_post_master, read_before_only, overwritten_before_read, dependent_follower,
        guarded_follower, ancestor_guarded, mutating_follower, transitive_follower,
    )


def allowed_conditional_read(values):
    conditional_master = 0
    conditional_previous = None
    for value in values:
        conditional_previous = conditional_master
        conditional_master = value
        if conditional_previous is not None:
            observe(conditional_previous)
    return conditional_previous


def parallel_cycle(initial, values):
    parallel_previous = None
    parallel_current = initial
    for parallel_next in values:
        observe(parallel_current)
        parallel_previous, parallel_current = parallel_current, parallel_next
        observe(parallel_previous)
    return parallel_previous


def parallel_backedge(initial):
    backedge_previous = None
    backedge_current = initial
    while backedge_previous != backedge_current:
        observe(backedge_current)
        backedge_next = advance(backedge_current)
        backedge_previous, backedge_current = backedge_current, backedge_next
    return backedge_previous


def parallel_three(initial, values):
    three_previous = None
    three_current = initial
    three_aux = 0
    for three_next in values:
        three_previous, three_current, three_aux = three_current, three_next, 1
        observe(three_previous)
    return three_previous


def parallel_negatives(initial, values):
    parallel_static = None
    parallel_static_master = initial
    parallel_no_read = None
    parallel_no_read_master = initial
    parallel_pre_only = None
    parallel_pre_master = initial
    parallel_other_source = None
    parallel_other_master = initial
    parallel_other = initial
    parallel_swap = None
    parallel_swap_master = initial
    parallel_starred = None
    parallel_starred_master = initial
    parallel_nested = None
    parallel_nested_master = initial
    observe(parallel_pre_only)
    for value in values:
        parallel_static, parallel_other = parallel_static_master, value
        observe(parallel_static)
        parallel_no_read, parallel_no_read_master = parallel_no_read_master, value
        parallel_pre_only, parallel_pre_master = parallel_pre_master, value
        parallel_other_source, parallel_other_master = parallel_other, value
        observe(parallel_other_source)
        parallel_swap, parallel_swap_master = parallel_swap_master, parallel_swap
        observe(parallel_swap)
        parallel_starred, *rest = parallel_starred_master, value
        observe(parallel_starred)
        (parallel_nested, parallel_other), parallel_nested_master = (parallel_nested_master, value), value
        observe(parallel_nested)
    return parallel_static, parallel_no_read, parallel_other_source, parallel_swap
