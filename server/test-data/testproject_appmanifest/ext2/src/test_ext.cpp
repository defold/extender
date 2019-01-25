#include <stdio.h>

extern "C" {
	void  	b_insert(int key, int value);
	int  	b_get(int key);
}

extern "C"
{
	void Ext2()
	{
        printf("Hello Ext2\n");
        b_insert(20,30);
        printf("get 20 = %d\n", b_get(20));
	}
}
