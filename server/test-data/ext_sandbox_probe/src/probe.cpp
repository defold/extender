// Part of the process sandbox integration fixture: gives the extension something to compile.
#include <dmsdk/sdk.h>

static dmExtension::Result AppInitializeSandboxProbe(dmExtension::AppParams* params)
{
    return dmExtension::RESULT_OK;
}

static dmExtension::Result InitializeSandboxProbe(dmExtension::Params* params)
{
    return dmExtension::RESULT_OK;
}

static dmExtension::Result AppFinalizeSandboxProbe(dmExtension::AppParams* params)
{
    return dmExtension::RESULT_OK;
}

static dmExtension::Result FinalizeSandboxProbe(dmExtension::Params* params)
{
    return dmExtension::RESULT_OK;
}

DM_DECLARE_EXTENSION(SandboxProbe, "SandboxProbe", AppInitializeSandboxProbe, AppFinalizeSandboxProbe, InitializeSandboxProbe, 0, 0, FinalizeSandboxProbe)
