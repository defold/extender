#include <dmsdk/sdk.h>
#import <Foundation/Foundation.h>
#import "SwiftSpan-Swift.h"

static dmExtension::Result Init(dmExtension::Params* params)
{
    SwiftSpanWorker* worker = [[SwiftSpanWorker alloc] init];
    dmLogInfo("%s", [[worker message] UTF8String]);
    return dmExtension::RESULT_OK;
}

DM_DECLARE_EXTENSION(SwiftSpan, "SwiftSpan", 0, 0, Init, 0, 0, 0)
