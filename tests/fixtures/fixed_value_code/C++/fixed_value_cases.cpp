#include <algorithm>
#include <vector>
struct Box {
    int declaration_member = 1;
    int constructor_member;
    int changed_member = 0;
    int doubled_member;
    std::vector<int> items_member;
    Box(int input) : constructor_member(input), doubled_member(input) { doubled_member = 2; }
    void set(int value) { changed_member = value; }
    void mutate(int value) { items_member.push_back(value); }
};
struct Other { int changed_member = 3; };
struct Multiple { int member; Multiple() : member(1) {} Multiple(int value) : member(value) {} };
struct Partial { int member; Partial(bool enabled) { if (enabled) member = 1; } };
struct Lazy { int member; void ensure() { member = 1; } };
struct LoopMember { int member = 0; void reset() { for (int i=0;i<2;++i) member = i; } };
void cases(int source, const int read_parameter, std::vector<int> items_parameter) {
    const int const_local = 7;
    int reassigned_local = source; reassigned_local = 2;
    int updated_local = source; ++updated_local;
    std::vector<int> items;
    items.push_back(source); items[0] = source; std::sort(items.begin(), items.end());
    items_parameter.push_back(source);
    (void)const_local; (void)read_parameter;
}
