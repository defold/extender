
extern int blib_mul(int x, int y);

int alib_add(int x, int y)
{
	return x + y;
}

int alib_mul(int x, int y)
{
	return blib_mul(x, y);
}
