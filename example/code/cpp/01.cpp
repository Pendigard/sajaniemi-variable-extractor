#include <iostream>
#include <vector>
using namespace std;

void swap(vector<int>& v, int i, int j) {
    int temp = v[i];
    v[i] = v[j];
    v[j] = temp;
}