#import "KotlinspectLoader.h"
#import <objc/runtime.h>
#import <UIKit/UIKit.h>

static BOOL kotlinspectInstalled = NO;

/// Inserts Kotlinspect's protocol first in a configuration's protocol classes, once.
static void KotlinspectAddProtocol(NSURLSessionConfiguration *configuration) {
    Class protocolClass = NSClassFromString(@"KotlinspectURLProtocol");
    if (protocolClass == nil || configuration == nil) return;
    NSMutableArray<Class> *classes = [NSMutableArray arrayWithArray:configuration.protocolClasses ?: @[]];
    if ([classes containsObject:protocolClass]) return;
    [classes insertObject:protocolClass atIndex:0];
    configuration.protocolClasses = classes;
}

/// Replaces a class getter (defaultSessionConfiguration, ephemeralSessionConfiguration) with one
/// that calls the original and adds Kotlinspect's protocol to the result.
static void KotlinspectSwizzleConfigurationGetter(SEL selector) {
    Class metaClass = object_getClass([NSURLSessionConfiguration class]);
    Method method = class_getClassMethod([NSURLSessionConfiguration class], selector);
    if (method == NULL) return;
    IMP original = method_getImplementation(method);
    IMP replacement = imp_implementationWithBlock(^NSURLSessionConfiguration *(id receiver) {
        NSURLSessionConfiguration *configuration =
            ((NSURLSessionConfiguration * (*)(id, SEL))original)(receiver, selector);
        KotlinspectAddProtocol(configuration);
        return configuration;
    });
    // class_replaceMethod on the metaclass keeps the original reachable through `original`.
    class_replaceMethod(metaClass, selector, replacement, method_getTypeEncoding(method));
}

@implementation KotlinspectLoader

+ (BOOL)compiledForDebug {
#if DEBUG
    return YES;
#else
    return NO;
#endif
}

+ (BOOL)installed {
    return kotlinspectInstalled;
}

+ (void)load {
#if DEBUG
    Class protocolClass = NSClassFromString(@"KotlinspectURLProtocol");
    if (protocolClass == nil) return;
    [NSURLProtocol registerClass:protocolClass];
    KotlinspectSwizzleConfigurationGetter(@selector(defaultSessionConfiguration));
    KotlinspectSwizzleConfigurationGetter(@selector(ephemeralSessionConfiguration));
    kotlinspectInstalled = YES;

    // Kotlin must not start inside +load; wait for the app to finish launching.
    __block id observer = [[NSNotificationCenter defaultCenter]
        addObserverForName:UIApplicationDidFinishLaunchingNotification
                    object:nil
                     queue:[NSOperationQueue mainQueue]
                usingBlock:^(NSNotification *note) {
                    [[NSNotificationCenter defaultCenter] removeObserver:observer];
                    Class autoStart = NSClassFromString(@"KotlinspectAutoStart");
                    SEL start = NSSelectorFromString(@"start");
                    if (autoStart != nil && [autoStart respondsToSelector:start]) {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Warc-performSelector-leaks"
                        [autoStart performSelector:start];
#pragma clang diagnostic pop
                    }
                }];
#endif
}

@end
