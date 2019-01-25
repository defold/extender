
extern int alib_mul(int x, int y);

int blib_add(int x, int y)
{
	return x + y;
}

int blib_mul(int x, int y)
{
	return alib_mul(x, y);
}

