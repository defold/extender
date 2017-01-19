package com.defold.extender;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

class PlatformConfig {

    public List<String> compileArgs;
    public Map<String, Object> context = new HashMap<>();
    public String exePrefix;
    public String exeExt;
    public String shlibRe;
    public String stlibRe;
    public String sourceRe;
    public String compileCmd;
    public String linkCmd;
    public String libCmd;
}
