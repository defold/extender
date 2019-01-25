int g_MyValue = 20;

int test_function(int a)
{
	return a + 1;
}

extern "C" void b_insert(int key, int value)
{
	g_MyValue = g_MyValue + test_function(value);
}

extern "C" int b_get(int key)
{
    return g_MyValue + test_function(key);
}


extern "C" void a_insert(int key, int value)
{
	g_MyValue = g_MyValue + test_function(value);
}

extern "C" int a_get(int key)
{
    return g_MyValue + test_function(key);
}