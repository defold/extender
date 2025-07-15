package com.defold.extender.services.cocoapods;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public interface IConfigParser {
    public Map<String, String> parse(String moduleName, String podName, File xcconfig) throws IOException;
}
