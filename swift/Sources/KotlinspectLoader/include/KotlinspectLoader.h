#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Zero-setup capture for debug builds. At load time (never in release builds) it registers
/// Kotlinspect's URL protocol for URLSession.shared and adds it to every default and ephemeral
/// session configuration, then starts Kotlinspect once the app has finished launching.
/// It never calls into Kotlin at load time.
@interface KotlinspectLoader : NSObject

/// YES when this target was compiled in a Debug configuration.
@property (class, nonatomic, readonly) BOOL compiledForDebug;

/// YES once the load-time installation ran.
@property (class, nonatomic, readonly) BOOL installed;

@end

NS_ASSUME_NONNULL_END
