#pragma once

#include <string>
#include <map>

namespace dmStdTest {

	void Combine(const std::string& a, const std::string& b, std::string& out);

	void Insert(std::map<int, std::string>& m, int key, const std::string& b);

};
