package com.defold.extender.services.spm;

/**
 * A Package.swift that reports through the build error whether manifest code can reach the
 * network: it connects to 127.0.0.1:port and then fails the build with the outcome.
 */
public final class SpmBeacon {

    public static final String MARKER = "SPM_BEACON";

    private SpmBeacon() {}

    public static String manifest(String name, int port) {
        return "// swift-tools-version:5.9\n"
            + "import PackageDescription\n"
            + "import Foundation\n"
            + "#if canImport(Darwin)\n"
            + "import Darwin\n"
            + "#endif\n"
            + "func probe() -> String {\n"
            + "    let fd = socket(AF_INET, SOCK_STREAM, 0)\n"
            + "    if fd < 0 { return \"socket FAILED errno=\\(errno)\" }\n"
            + "    var addr = sockaddr_in()\n"
            + "    addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)\n"
            + "    addr.sin_family = sa_family_t(AF_INET)\n"
            + "    addr.sin_port = UInt16(" + port + ").bigEndian\n"
            + "    addr.sin_addr.s_addr = inet_addr(\"127.0.0.1\")\n"
            + "    let rc = withUnsafePointer(to: &addr) { p in\n"
            + "        p.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in\n"
            + "            Darwin.connect(fd, sa, socklen_t(MemoryLayout<sockaddr_in>.size))\n"
            + "        }\n"
            + "    }\n"
            + "    close(fd)\n"
            + "    return rc == 0 ? \"connect OK\" : \"connect FAILED errno=\\(errno)\"\n"
            + "}\n"
            + "fatalError(\"" + MARKER + ": \\(probe())\")\n"
            + "let package = Package(name: \"" + name + "\",\n"
            + "    products: [.library(name: \"" + name + "\", targets: [\"" + name + "\"])],\n"
            + "    targets: [.target(name: \"" + name + "\")])\n";
    }
}
