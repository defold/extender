package com.defold.extender;

// A build that failed because of the client's project or request (compiler errors,
// invalid manifests, unresolvable dependencies), as opposed to a fault in the server.
public class BuildFailedException extends ExtenderException {
    public BuildFailedException(String output) {
        super(output);
    }

    public BuildFailedException(Exception e, String output) {
        super(e, output);
    }
}
