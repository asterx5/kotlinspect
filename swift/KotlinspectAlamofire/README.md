# KotlinspectAlamofire

Records [Alamofire](https://github.com/Alamofire/Alamofire) (and Moya) traffic in
[Kotlinspect](https://github.com/asterx5/kotlinspect), the network inspector for iOS, Android and
desktop apps.

This repository is published from the Kotlinspect repository (`swift/KotlinspectAlamofire`) on
every release; open issues and pull requests there. It is a separate package so that apps using
only Kotlinspect never download Alamofire.

## Install

Xcode > File > Add Package Dependencies, add both packages with the **same version**:

- `https://github.com/asterx5/kotlinspect`, product **Kotlinspect**
- `https://github.com/asterx5/kotlinspect-alamofire`, product **KotlinspectAlamofire**

Each version of this package requires exactly the same version of Kotlinspect.

## Use

```swift
import KotlinspectAlamofire

let session = KotlinspectAlamofire.session()              // your configuration, interceptor and monitors can be passed in
let provider = MoyaProvider<API>(session: KotlinspectAlamofire.session())
```

Each call is recorded once, with Alamofire's own view of it, and each retry is its own record.
Adding only `KotlinspectAlamofire.eventMonitor` to an existing session also works. Everything
else (setup, sessions, the inspector, HAR export) is in Kotlinspect's
[README](https://github.com/asterx5/kotlinspect#ios-swift).

Requires Alamofire 5.4 or later and iOS 14.
