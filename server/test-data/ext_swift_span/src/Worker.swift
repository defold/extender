import Foundation

// The conformance refers to the metadata of MutableRawSpan, which the OS has only from version 26
// and the optimizer can't remove, unlike a plain use of the span.
public protocol NamedSpan: ~Copyable, ~Escapable {
    static var name: String { get }
}

extension MutableRawSpan: NamedSpan {
    public static var name: String { "raw" }
}

@objc(SwiftSpanWorker)
public class Worker: NSObject {
    @objc public func message() -> String {
        "swift-span-marker-\(MutableRawSpan.name)"
    }
}
