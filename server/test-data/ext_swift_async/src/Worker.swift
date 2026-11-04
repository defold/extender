import Foundation

final class Result: @unchecked Sendable {
    var value = 0
}

func compute() async -> Int {
    await withCheckedContinuation { continuation in
        continuation.resume(returning: 42)
    }
}

@objc(SwiftAsyncWorker)
public class Worker: NSObject {
    @objc public func message() -> String {
        let done = DispatchSemaphore(value: 0)
        let result = Result()
        Task {
            result.value = await compute()
            done.signal()
        }
        done.wait()
        return "swift-async-marker-\(result.value)"
    }
}
