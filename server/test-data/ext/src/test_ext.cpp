#include <stdio.h>
#include "test_ext.h"

int alib_add(int x, int y);

extern "C"
{
	void Test()
	{
        printf("Hello Test\n");
        printf("10 + 20 = %d\n", alib_add(10, 20));
	}
}
