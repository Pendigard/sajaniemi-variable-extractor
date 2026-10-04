#include <algorithm>
#include <string>
#include <vector>

class MemberStore {
public:
    void add(int value) {
        this->member_items.push_back(value);
    }

private:
    std::vector<int> member_items;
};

class AmbiguousStore {
private:
    std::vector<int> ambiguous_items;
};

void collection_cases(int value) {
    std::vector<int> organizer_sort{3, 1, 2};
    std::sort(organizer_sort.begin(), organizer_sort.end());

    std::vector<int> organizer_reverse{1, 2, 3};
    std::reverse(organizer_reverse.begin(), organizer_reverse.end());

    std::vector<int> organizer_swap{1, 2, 3};
    std::swap(organizer_swap[0], organizer_swap[1]);

    std::vector<int> fixed_collection{1, 2, 3};
    int fixed_read = fixed_collection[0];
    fixed_read += fixed_collection[0];
    fixed_read += fixed_collection[1];
    fixed_read += fixed_collection[2];
    fixed_read += fixed_collection[0];
    fixed_read += fixed_collection[1];

    std::vector<int> container_values;
    container_values.push_back(value);
    container_values.pop_back();
    std::sort(container_values.begin(), container_values.end());

    std::vector<int> replaced_only{value};
    replaced_only[0] = value;

    std::string path_text = "root";
    path_text += "/child";

    std::string tmp_file = "file.txt";
    std::remove(tmp_file.c_str());
}
