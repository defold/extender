#include <stdio.h>

extern "C" {
	void  	a_insert(int key, int value);
	int  	a_get(int key);
}

extern "C"
{
	void Ext1()
	{
        printf("Hello Ext1\n");
        a_insert(10,20);
        printf("get 10 = %d\n", a_get(10));
	}
}
