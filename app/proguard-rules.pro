# Dihcord R8 rules. Libraries (OkHttp, Coil, Compose, Wear) ship their own consumer rules;
# add app-specific keeps here if a release build misbehaves.

# Optional TLS providers referenced by OkHttp but not shipped on Wear OS.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
