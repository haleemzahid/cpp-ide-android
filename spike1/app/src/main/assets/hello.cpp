// Compiled AT RUNTIME by on-device clang (bundled Termux toolchain).
// After the -Dmain=user_main_fn preprocessor trick, this function becomes
// int user_main_fn(int, char**) which the C++ runtime_shim.cpp calls via
// a matching extern declaration (same C++ name mangling).
#include <iostream>
#include <vector>
#include <numeric>

int main(int argc, char** argv) {
    std::vector<int> v{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    int sum = std::accumulate(v.begin(), v.end(), 0);
    std::cout << "Hello from JIT-compiled C++!" << std::endl;
    std::cout << "sum(1..10) = " << sum << std::endl;
    std::cout << "argc = " << argc << std::endl;
    for (int i = 0; i < argc; i++) {
        std::cout << "argv[" << i << "] = " << argv[i] << std::endl;
    }
    return 0;
}
