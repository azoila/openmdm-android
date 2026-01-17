# OpenMDM Library ProGuard Rules

# Keep API models for Gson serialization
-keep class com.openmdm.library.api.** { *; }

# Keep DeviceManager public API
-keep class com.openmdm.library.device.DeviceManager { *; }

# Keep MDMClient public API
-keep class com.openmdm.library.MDMClient { *; }
-keep class com.openmdm.library.MDMClient$Builder { *; }
