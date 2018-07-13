
// myextension.cpp
// Extension lib defines
#define LIB_NAME "MyExtension"
#define MODULE_NAME "myextension"

// include the Defold SDK
#include <dmsdk/sdk.h>
#include <string>
#include <stdio.h>
#include "std.h"

std::string Test(const std::string& a, const std::string& b)
{
    return a + b;
}

// Combines two strings
static int Combine(lua_State* L)
{
    // Check and get parameter string from stack
    const char* str1 = luaL_checkstring(L, 1);
    const char* str2 = luaL_checkstring(L, 2);

    if (str1 && str2)
    {
        std::string a = str1;
        std::string b = str2;
        std::string out;
        dmStdTest::Combine(a, b, out);

        lua_pushlstring(L, out.c_str(), out.size());
        return 1;
    }
    std::string c = Test("Hello", "World");

    lua_pushlstring(L, c.c_str(), c.size());
    return 1;
}

// Functions exposed to Lua
static const luaL_reg Module_methods[] =
{
    {"combine", Combine},
    {0, 0}
};

static void LuaInit(lua_State* L)
{
    int top = lua_gettop(L);

    // Register lua names
    luaL_register(L, MODULE_NAME, Module_methods);

    lua_pop(L, 1);
    assert(top == lua_gettop(L));
}

dmExtension::Result AppInitializeMyExtension(dmExtension::AppParams* params)
{
    return dmExtension::RESULT_OK;
}

dmExtension::Result InitializeMyExtension(dmExtension::Params* params)
{
    // Init Lua
    LuaInit(params->m_L);
    printf("Registered %s Extension\n", MODULE_NAME);
    return dmExtension::RESULT_OK;
}

dmExtension::Result AppFinalizeMyExtension(dmExtension::AppParams* params)
{
    return dmExtension::RESULT_OK;
}

dmExtension::Result FinalizeMyExtension(dmExtension::Params* params)
{
    return dmExtension::RESULT_OK;
}


// Defold SDK uses a macro for setting up extension entry points:
//
// DM_DECLARE_EXTENSION(symbol, name, app_init, app_final, init, update, on_event, final)

DM_DECLARE_EXTENSION(MyExtension, LIB_NAME, AppInitializeMyExtension, AppFinalizeMyExtension, InitializeMyExtension, 0, 0, FinalizeMyExtension)
