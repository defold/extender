#include <stdio.h>

int blib_add(int x, int y);
int blib_mul(int x, int y);

extern "C"
{
	void Test()
	{
        printf("Hello Test\n");
        printf("10 + 20 = %d\n", blib_add(10, 20));
        printf("10 * 20 = %d\n", blib_mul(10, 20));
	}
}
