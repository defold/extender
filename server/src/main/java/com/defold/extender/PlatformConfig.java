package com.defold.extender;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

class PlatformConfig {

    public Map<String, String> env;
    public Map<String, Object> context = new HashMap<>();
    public String exePrefix; // deprecated
    public String exeExt; // deprecated
    public String writeLibPattern;
    public String writeExePattern;
    public String shlibRe;
    public String stlibRe;
    public String sourceRe;
    public String javaSourceRe;
    public String compileCmd;
    public String linkCmd;
    public String libCmd;
    public String javacCmd;
    public String jarCmd;
    public String dxCmd;
    public String mtCmd;
    public List<String> allowedLibs;
    public List<String> allowedFlags;
    public List<String> allowedSymbols;
}
