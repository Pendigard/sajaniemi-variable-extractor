"""Small, explicit examples used to exercise the existing role predicates."""


def normalize(value):
    return value * 2


def role_examples(values):
    fixed_value = 10

    stepper = 0
    while stepper < len(values):
        stepper += 1

    gatherer = 0
    for gathered_value in values:
        gatherer += gathered_value

    gatherer_assignment = 0
    for assignment_value in values:
        gatherer_assignment = gatherer_assignment + assignment_value

    for walker in values:
        print(walker)

    current = 0
    previous = 0
    for follower_value in values:
        previous = current
        current = follower_value
        print(previous)

    most_recent_holder = None
    for recent_value in values:
        most_recent_holder = recent_value

    most_wanted_holder = float("-inf")
    for wanted_value in values:
        if wanted_value > most_wanted_holder:
            most_wanted_holder = wanted_value

    best_by_max = float("-inf")
    for maximum_value in values:
        best_by_max = max(best_by_max, maximum_value)

    best_by_min = float("inf")
    for minimum_value in values:
        if minimum_value < best_by_min:
            best_by_min = minimum_value

    one_way_flag = False
    for flag_value in values:
        if flag_value < 0:
            one_way_flag = True

    numeric_one_way_flag = 0
    for numeric_flag_value in values:
        if numeric_flag_value < 0:
            numeric_one_way_flag = 1
    if numeric_one_way_flag:
        print("negative value seen")

    inverse_one_way_flag = True
    for inverse_flag_value in values:
        if inverse_flag_value < 0:
            inverse_one_way_flag = False

    temporary = normalize(fixed_value)
    print(temporary)

    organizer_items = [3, 1, 2]
    organizer_items.sort()

    indexed_organizer_items = list(values)
    for organizer_index in range(len(indexed_organizer_items)):
        indexed_organizer_items[organizer_index] = normalize(indexed_organizer_items[organizer_index])

    permuted_organizer_items = [3, 2, 1]
    permuted_organizer_items.reverse()

    container_items = []
    for container_value in values:
        container_items.append(container_value)
    if container_items:
        container_items.pop()

    return (
        fixed_value,
        gatherer,
        gatherer_assignment,
        previous,
        most_recent_holder,
        most_wanted_holder,
        best_by_max,
        best_by_min,
        one_way_flag,
        numeric_one_way_flag,
        inverse_one_way_flag,
        organizer_items,
        indexed_organizer_items,
        permuted_organizer_items,
        container_items,
    )
