extern int g_MyValue;

int test_function(int a)
{
	return a;
}

extern "C" void a_insert(int key, int value)
{
	g_MyValue = g_MyValue + test_function(value);
}

extern "C" int a_get(int key)
{
    return g_MyValue + test_function(key);
}