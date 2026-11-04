#include <dmsdk/sdk.h>
#import <Foundation/Foundation.h>
#import "SwiftAsync-Swift.h"

static dmExtension::Result Init(dmExtension::Params* params)
{
    SwiftAsyncWorker* worker = [[SwiftAsyncWorker alloc] init];
    dmLogInfo("%s", [[worker message] UTF8String]);
    return dmExtension::RESULT_OK;
}

DM_DECLARE_EXTENSION(SwiftAsync, "SwiftAsync", 0, 0, Init, 0, 0, 0)
