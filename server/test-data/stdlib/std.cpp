#include "std.h"

void dmStdTest::Combine(const std::string& a, const std::string& b, std::string& out)
{
	out = a + b;
}

void dmStdTest::Insert(std::map<int, std::string>& m, int key, const std::string& b)
{
	m[key] = b;
}