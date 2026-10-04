"""Readable counterexamples for role confusions seen in heuristic extraction."""


def normalize(value):
    return value * 2


def negative_role_examples(values, panel):
    walker = 0
    while walker < len(values):
        walker += 1

    items = list(values)
    i = 0
    while i < len(items):
        print(items[i])
        i += 1

    for nested_value in values:
        panel.ids.append(nested_value)

    reverting_flag = False
    for flag_value in values:
        if flag_value < 0:
            reverting_flag = True
        if flag_value == 0:
            reverting_flag = False

    container = 0
    container += 1

    unqualified_maximum = 0
    for maximum_value in values:
        unqualified_maximum = maximum_value

    long_lived_temporary = normalize(len(values))
    padding_one = 1
    padding_two = 2
    padding_three = 3
    padding_four = 4
    padding_five = 5
    padding_six = 6
    print(long_lived_temporary)

    return (
        walker,
        i,
        reverting_flag,
        container,
        unqualified_maximum,
        padding_one + padding_two + padding_three + padding_four + padding_five + padding_six,
    )
