# Retrofit / Gson DTOs keep field names for JSON (de)serialization.
# Todo data.remote, no solo dto: NominatimResultDto vive junto a su servicio, y con la regla
# anterior R8 le renombraba los campos -> Gson devolvía nulos y el buscador de direcciones
# fallaba solo en release ("no se ha podido buscar").
-keep class com.dskmusic.lokate.data.remote.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, Exceptions

# Mapas sin conexión: mapsforge instancia por nombre las clases del tema de render (XML) y
# arrastra clases de AWT/desktop que en Android no existen. Sin esto, el mapa offline
# revienta solo en release, que es justo la compilación que se instala.
-keep class org.mapsforge.** { *; }
-keep class org.osmdroid.mapsforge.** { *; }
-dontwarn org.mapsforge.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
