#include <stddef.h>
#include <stdlib.h>

static int compare_ints(const void *left, const void *right) {
    const int lhs = *(const int *)left;
    const int rhs = *(const int *)right;
    return (lhs > rhs) - (lhs < rhs);
}

void collection_cases(void) {
    int organizer_qsort[] = {3, 1, 2};
    qsort(organizer_qsort, 3, sizeof(int), compare_ints);

    int replaced_only[] = {1, 2, 3};
    replaced_only[0] = 9;

    int fixed_collection[] = {1, 2, 3};
    const int fixed_read = fixed_collection[0];

    char path_text[32] = "root";
    path_text[0] = 'R';

    const char *file_name = "file.txt";
    remove(file_name);
}
