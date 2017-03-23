#include <stdio.h>

int alib_mul(int x, int y);

extern "C"
{
	void Test()
	{
        printf("Hello Test\n");
        printf("10 + 20 = %d\n", alib_mul(10, 20));
	}
}
