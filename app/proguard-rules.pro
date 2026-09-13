# Keep Enterprise DPC Receivers, Services & Model Classes
-keep class com.rrv.mdm.dpc.receiver.** { *; }
-keep class com.rrv.mdm.dpc.data.model.** { *; }
-keep class com.rrv.mdm.dpc.policy.** { *; }
-keep class com.rrv.mdm.dpc.worker.** { *; }
-keep class com.rrv.mdm.dpc.ui.** { *; }
-keep class com.rrv.mdm.dpc.RrvMdmApplication { *; }

# Paho MQTT Client Rules
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**

# Gson & Serialization
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class kotlinx.serialization.** { *; }

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
