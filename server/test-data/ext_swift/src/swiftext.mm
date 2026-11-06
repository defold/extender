#include <dmsdk/sdk.h>
#import <Foundation/Foundation.h>
#import "SwiftExt-Swift.h"

static dmExtension::Result Init(dmExtension::Params* params)
{
    SwiftExtGreeter* greeter = [[SwiftExtGreeter alloc] init];
    dmLogInfo("%s", [[greeter message] UTF8String]);
    return dmExtension::RESULT_OK;
}

DM_DECLARE_EXTENSION(SwiftExt, "SwiftExt", 0, 0, Init, 0, 0, 0)
