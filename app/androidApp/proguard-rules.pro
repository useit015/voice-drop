# gomobile bindings are called from Go through JNI by name.
-keep class go.** { *; }
-keep class app.voicedrop.go.** { *; }
-keep class * implements app.voicedrop.go.engine.Listener { *; }
