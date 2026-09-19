# Retrofit / Gson DTOs keep field names for JSON (de)serialization.
# Todo data.remote, no solo dto: NominatimResultDto vive junto a su servicio, y con la regla
# anterior R8 le renombraba los campos -> Gson devolvía nulos y el buscador de direcciones
# fallaba solo en release ("no se ha podido buscar").
-keep class com.dskmusic.lokate.data.remote.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, Exceptions
