def normalize(value):
    return value * 2


def collection_cases(value):
    organizer_sort = [3, 1, 2]
    organizer_sort.sort()

    organizer_reverse = [1, 2, 3]
    organizer_reverse.reverse()

    organizer_shuffle = [1, 2, 3]
    shuffle(organizer_shuffle)

    organizer_tuple = [1, 2, 3]
    organizer_tuple[1], organizer_tuple[0] = organizer_tuple[0], organizer_tuple[1]

    organizer_manual = [1, 2, 3]
    swap_tmp = organizer_manual[0]
    organizer_manual[0] = organizer_manual[1]
    organizer_manual[1] = swap_tmp

    fixed_collection = [1, 2, 3]
    print(fixed_collection[0])

    container_append = []
    container_append.append(value)

    container_both = []
    container_both.append(value)
    container_both.pop()
    container_both.sort()

    transformed_only = [value]
    transformed_only[0] = normalize(transformed_only[0])

    replaced_only = [value]
    replaced_only[0] = value

    updated_element = [value]
    updated_element[0] += value

    rebound_collection = [value]
    rebound_collection = organizer_sort

    updated_binding = [value]
    updated_binding += organizer_sort

    path_text = "root"
    path_text += "/child"

    collection_by_name_only = value

    scalar_from_index = organizer_sort[0]

    ambiguous_factory = unknown_factory()

    remove_target = "file.txt"
    remove(remove_target)

    return organizer_sort, organizer_reverse, container_append, container_both
