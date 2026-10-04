struct Node { int field; };
void use(int);
int acquire();
bool unpredictable();
void cursor_cases(Node *begin, Node **objects, int **args, int **ptrs, int *out, int *values, int n, int stride, int offset) {
    for (Node *direct = begin; direct != begin + n; ++direct) {
        use(direct->field);
    }
    for (int *producer = values; producer != values + n;) {
        use(*producer++);
    }
    for (int *pre = values; pre != values + n;) {
        use(*(++pre));
    }
    for (int index = 0; index < n; ++index) {
        use(*args[index]);
    }
    for (int row = 0; row < n; ++row) {
        *(out + row * stride + offset) = values[row];
    }
    for (int object_index = 0; object_index < n; ++object_index) {
        use(objects[object_index]->field);
    }
    for (int ptr_index = 0; ptr_index < n; ++ptr_index) {
        *(ptrs[ptr_index]) = 1;
    }
    int *cross = values;
    for (int a = 0; a < n; ++a) { use(*cross); }
    for (int b = 0; b < n; ++b) { cross++; }
}
void unbraced(int n) {
    int h = acquire();
    while (h < 0)
        h += 360;
    use(h);
    int ind = 0;
    for (int a = 0; a < n; ++a)
        for (int b = 0; b < n; ++b)
            for (int c = 0; c < n; ++c)
                use(ind++);
    int two = 0;
    for (int a = 0; a < n; ++a)
        for (int b = 0; b < n; ++b)
            use(two++);
    int braced = 0;
    for (int a = 0; a < n; ++a) { use(braced++); }
    int after = acquire();
    while (n > 0)
        use(n);
    after *= 100;
    use(after);
    int lambda_only = 0;
    for (int a = 0; a < n; ++a) {
        auto unused = [&]() { lambda_only++; };
    }
    int score = acquire();
    score *= 100;
    use(score);
    int dynamic = 0;
    while (dynamic < n) dynamic += acquire();
    int guarded = 0;
    while (guarded < n) { if (unpredictable()) guarded++; }
}
void ranges(int kind) {
    double values[3] = {1, 2, 3};
    switch (kind) {
        case 0: {
            auto factor = values[0] + values[1];
            for (auto &u : values) u *= factor;
            break;
        }
    }
    double total = 0;
    for (auto element : values)
        total += element;
    use(total);
    for (auto &u : values) use(u);
}

template <typename Values>
void unbraced_cursors(Values &values) {
    for (auto unbraced_it = values.begin(); unbraced_it != values.end(); ++unbraced_it)
        use(*unbraced_it);
}
void unbraced_producer(int *values, int n) {
    for (int *rpit = values; rpit != values + n;)
        use(*rpit++);
}
void braced_normalization() {
    int braced_h = acquire();
    while (braced_h < 0) { braced_h += 360; }
    use(braced_h);
}

void pointer_offset(int *begin, int n) {
    for (int *typed = begin; typed < begin + n; typed++) { use(*(typed - 1)); }
    for (int *nested = begin; nested < begin + n; nested++) {
        for (int other = 0; other < n; other++) use(*nested);
    }
}
