import Foundation

@objc(SwiftExtGreeter)
public class Greeter: NSObject {
    @objc public func message() -> String {
        return "swift-ext-marker-" + Helper.suffix
    }
}
