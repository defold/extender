#ifdef __OBJC__
#import <UIKit/UIKit.h>
#else
#ifndef FOUNDATION_EXPORT
#if defined(__cplusplus)
#define FOUNDATION_EXPORT extern "C"
#else
#define FOUNDATION_EXPORT extern
#endif
#endif
#endif

{{#HEADERS}}
#import "{{{.}}}"
{{/HEADERS}}

FOUNDATION_EXPORT double {{MODULE_ID}}VersionNumber;
FOUNDATION_EXPORT const unsigned char {{MODULE_ID}}VersionString[];

