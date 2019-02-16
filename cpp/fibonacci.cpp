#include "benjmark.h"
#include <inttypes.h>

int64_t fibonacci(int64_t x) {
  return x <= 1? x : fibonacci(x - 1) + fibonacci(x - 2);
}

struct Setup {
  int64_t input(const nlohmann::json& src) const {
    return src;
  }

  int64_t compute(int64_t x) const {
    return fibonacci(x);
  }

  nlohmann::json output(int64_t x) const {
    return x;
  }
};

int main(int argc, const char** argv) {
  std::string input_file = argv[1];
  std::string output_file = argv[2];

  Setup setup;
  bj::perform(setup, input_file, output_file);
  
  return 0;
}
