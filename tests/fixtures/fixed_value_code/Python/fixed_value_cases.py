GLOBAL_FIXED = 7
global_uninitialized = None
global_mutated = 1
global_items = []

def mutate_globals():
    global global_mutated
    global_mutated = 2
    global_items.append(2)

def consume(value): return value

def local_cases(source, control_parameter, reassigned_parameter, updated_parameter, items_parameter):
    initialized_local = 7
    consume(initialized_local)
    assigned_later_local = None
    assigned_later_local = source
    consume(assigned_later_local)
    many_reads_local = 8
    consume(many_reads_local); consume(many_reads_local)
    reassigned_local = source
    reassigned_local = 2
    updated_local = source
    updated_local += 1
    normalized_local = source
    normalized_local = abs(normalized_local)
    mutated_items = []
    mutated_items.append(source)
    indexed_items = []
    indexed_items[0] = source
    sorted_items = []
    sorted_items.sort()
    if control_parameter:
        consume(control_parameter)
    reassigned_parameter = source
    updated_parameter += 1
    items_parameter.append(source)

def loop_cases(values):
    before_loop = values
    for value in values:
        consume(before_loop)
        inside_loop = value
    first_in_loop = None
    for value in values:
        first_in_loop = value
    changed_in_loop = values
    for value in values:
        changed_in_loop = value

def branch_cases(flag, source):
    if flag:
        exclusive_branch = source
    else:
        exclusive_branch = 0
    default_then_replace = source
    if flag:
        default_then_replace = 0
    if flag:
        independent_ifs = source
    if source:
        independent_ifs = 0
