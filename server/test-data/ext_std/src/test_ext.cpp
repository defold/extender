#include <stdio.h>


extern "C"
{
	void Test()
	{
        printf("Hello Test\n");
        printf("10 + 20 = %d\n", blib_add(10, 20));
        printf("10 * 20 = %d\n", blib_mul(10, 20));
	}
}


// myextension.cpp
// Extension lib defines
#define LIB_NAME "MyExtension"
#define MODULE_NAME "myextension"

// include the Defold SDK
#include <dmsdk/sdk.h>

static int Rot13(lua_State* L)
{
    int top = lua_gettop(L);

    // Check and get parameter string from stack
    const char* str = luaL_checkstring(L, 1);

    // Allocate new string
    int len = strlen(str);
    char *rot = (char *) malloc(len + 1);

    // Iterate over the parameter string and create rot13 string
    for(int i = 0; i <= len; i++) {
        const char c = str[i];
        if((c >= 'A' && c <= 'M') || (c >= 'a' && c <= 'm')) {
            // Between A-M just add 13 to the char.
            rot[i] = c + 13;
        } else if((c >= 'N' && c <= 'Z') || (c >= 'n' && c <= 'z')) {
            // If rolling past 'Z' which happens below 'M', wrap back (subtract 13)
            rot[i] = c - 13;
        } else {
            // Leave character intact
            rot[i] = c;
        }
    }

    // Put the rotated string on the stack
    lua_pushstring(L, rot);

    // Free string memory. Lua has a copy by now.
    free(rot);

    // Assert that there is one item on the stack.
    assert(top + 1 == lua_gettop(L));

    // Return 1 item
    return 1;
}

// Functions exposed to Lua
static const luaL_reg Module_methods[] =
{
    {"rot13", Rot13},
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
