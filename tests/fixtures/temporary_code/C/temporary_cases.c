extern int transform(int value);
extern void consume(int value);

void positive_single(int source) {
    int temp_single = transform(source);
    consume(temp_single);
}

void update_cases(int source) {
    int plus_equal = transform(source);
    consume(plus_equal);
    plus_equal += source;
    consume(plus_equal);
    int post_increment = transform(source);
    consume(post_increment);
    post_increment++;
    consume(post_increment);
    int pre_increment = transform(source);
    consume(pre_increment);
    ++pre_increment;
    consume(pre_increment);
}

void loop_cases(int *items, int count) {
    for (int i = 0; i < count; ++i) {
        int loop_fresh = transform(items[i]);
        consume(loop_fresh);
    }
    int loop_old = transform(count);
    for (int i = 0; i < count; ++i) {
        consume(loop_old);
        loop_old = transform(items[i]);
    }
}

