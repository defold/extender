#include <stdio.h>

extern "C"
{
	void MyExtension()
	{
        printf("Hello Test\n");
	}
}
