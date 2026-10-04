static const int global_fixed = 7;
static int global_uninitialized;
static int global_mutated = 1;
static int global_items[4] = {0};
extern void consume(int);
void mutate_globals(void) { global_mutated = 2; global_items[0] = 2; }

void locals(int source, int read_parameter, int reassigned_parameter, int *items_parameter) {
    const int const_local = 7;
    int initialized_local = 8;
    int assigned_later_local; assigned_later_local = source;
    int reassigned_local = source; reassigned_local = 2;
    int updated_local = source; updated_local += 1;
    int post_increment = source; post_increment++;
    consume(read_parameter);
    reassigned_parameter = source;
    items_parameter[0] = source;
    consume(const_local + initialized_local + assigned_later_local);
}

void loops(int *values, int count) {
    int before_loop = count;
    for (int i = 0; i < count; ++i) { consume(before_loop); }
    for (int i = 0; i < count; ++i) { int inside_loop = values[i]; consume(inside_loop); }
    int changed_in_loop = count;
    for (int i = 0; i < count; ++i) { changed_in_loop = values[i]; }
}
