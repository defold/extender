#include <dmsdk/sdk.h>

#if defined(DM_PLATFORM_IOS) || defined(DM_PLATFORM_OSX)
// The generated Swift interface header needs the platform UI types declared up front
// when compiled without clang modules
#if defined(DM_PLATFORM_IOS)
#import <UIKit/UIKit.h>
#else
#import <AppKit/AppKit.h>
#endif
#import <Sentry/Sentry.h>
// SentrySDK is implemented in Swift; its ObjC interface lives in the generated header
#import <Sentry/Sentry-Swift.h>
#endif

static dmExtension::Result AppInitializeSpmExt(dmExtension::AppParams* params)
{
#if defined(DM_PLATFORM_IOS) || defined(DM_PLATFORM_OSX)
    [SentrySDK startWithConfigureOptions:^(SentryOptions *options) {
        options.dsn = @"https://examplePublicKey@o0.ingest.sentry.io/0";
    }];
    dmLogInfo("SpmExt: Sentry started via Swift Package Manager dependency");
#endif
    return dmExtension::RESULT_OK;
}

static dmExtension::Result AppFinalizeSpmExt(dmExtension::AppParams* params)
{
    return dmExtension::RESULT_OK;
}

static dmExtension::Result InitializeSpmExt(dmExtension::Params* params)
{
    return dmExtension::RESULT_OK;
}

static dmExtension::Result FinalizeSpmExt(dmExtension::Params* params)
{
    return dmExtension::RESULT_OK;
}

DM_DECLARE_EXTENSION(SpmExt, "SpmExt", AppInitializeSpmExt, AppFinalizeSpmExt, InitializeSpmExt, 0, 0, FinalizeSpmExt)
