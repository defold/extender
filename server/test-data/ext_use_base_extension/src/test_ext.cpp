
// The "test-data" folder is part of the upload folder in our integration tests
// For actual extensions it would be "ext/include/ext.h"
#include <test-data/ext/include/ext.h>
#include <stdio.h>

extern "C"
{
	void ext_use_base_extension() // Need to have the symbol specified in the ext.manifest
	{
        printf("Hello From TestUseBaseExtension\n");
        Test();
	}
}

