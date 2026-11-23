import Foundation

enum Helper {
    static var suffix: String {
        return ObjCBridge.name() + String(extraValue())
    }
}
