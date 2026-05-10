Shizuku Modern fork release (Hotfix).

Changes:
• Fixed an issue in release builds where `window.Shizuku` methods (`exec`, `execWithOptions`, `download`, `getModuleInfo`) were throwing `is not a function` errors. This was caused by R8/ProGuard stripping `@JavascriptInterface` methods during minification. Added proper keep rules to preserve the JavaScript bridge.

Validation:
• Verified ProGuard rules and rebuilt release APK.

APK SHA-256:
67accff96ceb8dfe70ec7ed78af10a37c7f9f91d26050f0f104b324b4982a254