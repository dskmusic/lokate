"""i18n ligero para el panel de administración: sin dependencias nuevas (nada de Flask-Babel,
que además es para Flask, no FastAPI). Locale por request vía contextvar, fijado por un
middleware ASGI a partir de: cookie -> Accept-Language -> español por defecto."""

import contextvars

LOCALES = ("es", "en")
DEFAULT_LOCALE = "es"
LOCALE_COOKIE = "lokate_admin_lang"

_current_locale: contextvars.ContextVar[str] = contextvars.ContextVar("locale", default=DEFAULT_LOCALE)

TRANSLATIONS: dict[str, dict[str, str]] = {
    "es": {
        "dashboard": "Dashboard",
        "groups": "Grupos",
        "users": "Usuarios",
        "admins_count": "{n} admin",
        "admins_count_plural": "{n} admins",
        "online_now": "En línea",
        "pings_24h": "Ubicaciones (24h)",
        "active_24h": "Activos (24h)",
        "recent_activity": "Actividad reciente",
        "with_push": "Con notificaciones",
        "recent_users": "Últimos usuarios registrados",
        "activity_7d": "Actividad de los últimos 7 días",
        "username": "Usuario",
        "name": "Nombre",
        "group": "Grupo",
        "admin": "Admin",
        "created": "Creado",
        "yes": "Sí",
        "no": "No",
        "none": "—",
        "no_users_yet": "Sin usuarios todavía",
        "no_activity": "Sin actividad todavía",
        "history_title": "Historial de ubicaciones",
        "select_user": "Usuario",
        "select_date": "Fecha",
        "view": "Ver",
        "no_points": "No hay ubicaciones registradas ese día",
        "point_list": "Puntos registrados",
        "time": "Hora",
        "coordinates": "Coordenadas",
        "avatar_photo_of": "Foto de",
        "choose_image_file": "Elige un archivo de imagen",
        "unsupported_format": "Formato no soportado (usa JPEG, PNG o WEBP)",
        "image_too_large": "La imagen no puede superar 5 MB",
        "upload": "Subir",
        "language": "Idioma",
        "made_with_love": "Hecho con ❤️ por",
        "storage": "Almacenamiento",
        "storage_database": "Base de datos",
        "storage_avatars": "Avatares",
        "storage_attachments": "Adjuntos",
        "storage_apk": "APK",
        "storage_web_static": "Web estática",
        "storage_backups": "Copias de seguridad",
        "storage_total": "Total",
        "locations_purge": "Vaciar ubicaciones",
        "locations_purge_hint": "Borra todas las posiciones guardadas. Usuarios, grupos y zonas se conservan.",
        "locations_purge_confirm": "¿Borrar TODAS las ubicaciones guardadas de todos los usuarios? Los usuarios, grupos y zonas NO se tocan. No se puede deshacer.",
        "backups_title": "Copias de seguridad",
        "backups_create": "Nueva copia",
        "backups_create_button": "Crear copia ahora",
        "backups_description_placeholder": "Descripción (opcional) — ej. \"Antes de migrar al VPS\"",
        "backups_date": "Fecha",
        "backups_description": "Descripción",
        "backups_size": "Tamaño",
        "backups_no_description": "—",
        "backups_download": "Descargar",
        "backups_restore": "Restaurar",
        "backups_restore_confirm": "¿Restaurar esta copia? Se SOBRESCRIBIRÁN los datos actuales (usuarios, ubicaciones, avatares, adjuntos) con los de esta copia. Esta acción no se puede deshacer.",
        "backups_delete": "Eliminar",
        "backups_delete_confirm": "¿Eliminar esta copia de seguridad? No se puede deshacer.",
        "backups_none": "Todavía no hay copias de seguridad",
        "files_preview": "Vista previa",
        "files_name": "Nombre",
        "files_size": "Tamaño",
        "files_modified": "Modificado",
        "files_in_use": "en uso",
        "files_delete": "Eliminar",
        "files_delete_confirm": "¿Eliminar este archivo del servidor? No se puede deshacer.",
        "files_delete_all": "Borrar todos",
        "files_delete_all_confirm": "¿Borrar TODOS los archivos de esta carpeta? No se puede deshacer.",
        "files_none": "No hay archivos en esta carpeta",
    },
    "en": {
        "dashboard": "Dashboard",
        "groups": "Groups",
        "users": "Users",
        "admins_count": "{n} admin",
        "admins_count_plural": "{n} admins",
        "online_now": "Online",
        "pings_24h": "Locations (24h)",
        "active_24h": "Active (24h)",
        "recent_activity": "Recent activity",
        "with_push": "With notifications",
        "recent_users": "Recently registered users",
        "activity_7d": "Activity over the last 7 days",
        "username": "Username",
        "name": "Name",
        "group": "Group",
        "admin": "Admin",
        "created": "Created",
        "yes": "Yes",
        "no": "No",
        "none": "—",
        "no_users_yet": "No users yet",
        "no_activity": "No activity yet",
        "history_title": "Location history",
        "select_user": "User",
        "select_date": "Date",
        "view": "View",
        "no_points": "No locations recorded that day",
        "point_list": "Recorded points",
        "time": "Time",
        "coordinates": "Coordinates",
        "avatar_photo_of": "Photo of",
        "choose_image_file": "Choose an image file",
        "unsupported_format": "Unsupported format (use JPEG, PNG or WEBP)",
        "image_too_large": "The image can't be larger than 5 MB",
        "upload": "Upload",
        "language": "Language",
        "made_with_love": "Made with ❤️ by",
        "storage": "Storage",
        "storage_database": "Database",
        "storage_avatars": "Avatars",
        "storage_attachments": "Attachments",
        "storage_apk": "APK",
        "storage_web_static": "Static web",
        "storage_backups": "Backups",
        "storage_total": "Total",
        "locations_purge": "Clear locations",
        "locations_purge_hint": "Deletes every stored position. Users, groups and zones are kept.",
        "locations_purge_confirm": "Delete ALL stored locations for every user? Users, groups and zones are NOT touched. This cannot be undone.",
        "backups_title": "Backups",
        "backups_create": "New backup",
        "backups_create_button": "Create backup now",
        "backups_description_placeholder": "Description (optional) — e.g. \"Before migrating to VPS\"",
        "backups_date": "Date",
        "backups_description": "Description",
        "backups_size": "Size",
        "backups_no_description": "—",
        "backups_download": "Download",
        "backups_restore": "Restore",
        "backups_restore_confirm": "Restore this backup? Current data (users, locations, avatars, attachments) will be OVERWRITTEN with this backup's data. This can't be undone.",
        "backups_delete": "Delete",
        "backups_delete_confirm": "Delete this backup? This can't be undone.",
        "backups_none": "No backups yet",
        "files_preview": "Preview",
        "files_name": "Name",
        "files_size": "Size",
        "files_modified": "Modified",
        "files_in_use": "in use",
        "files_delete": "Delete",
        "files_delete_confirm": "Delete this file from the server? This cannot be undone.",
        "files_delete_all": "Delete all",
        "files_delete_all_confirm": "Delete ALL files in this folder? This cannot be undone.",
        "files_none": "No files in this folder",
    },
}

# Textos propios (español) <-> su equivalente inglés, para el traductor JS de la interfaz por
# defecto de sqladmin (nav de modelos, botones Save/Cancel/Delete...) — ver layout.html.
UI_DICT_ES: dict[str, str] = {
    "Logout": "Cerrar sesión",
    "Cancel": "Cancelar",
    "Save": "Guardar",
    "Save and continue editing": "Guardar y seguir editando",
    "Save and add another": "Guardar y añadir otro",
    "Save as new": "Guardar como nuevo",
    "Delete": "Eliminar",
    "Details": "Detalle",
    "Edit": "Editar",
    "Actions": "Acciones",
    "Search": "Buscar",
    "Export": "Exportar",
    "Yes": "Sí",
    "No": "No",
    "New": "Nuevo",
    "Family groups": "Grupos familiares",
    "Users": "Usuarios",
    "Zones": "Zonas",
    "Location history": "Historial de ubicaciones",
    "Files": "Archivos",
    "History": "Historial",
    # Columnas de las listas (Usuarios/Grupos/Zonas), recortadas y renombradas para que quepan
    # en móvil sin scroll horizontal — ver column_labels en admin.py.
    "Photo": "Foto",
    "Name": "Nombre",
    "Username": "Usuario",
    "Code": "Código",
    "Created": "Creado",
    "Group": "Grupo",
    "Radius (m)": "Radio (m)",
    # Paginación y contador de las listas (sqladmin no trae i18n propio, texto fijo en inglés).
    "prev": "ant.",
    "next": "sig.",
    "Showing": "Mostrando",
    "to": "a",
    "of": "de",
    "items": "elementos",
    "/ Page": "/ Página",
    "New User": "Nuevo usuario",
    "New Group": "Nuevo grupo",
    "New Zone": "Nueva zona",
}


def detect_locale(accept_language: str | None) -> str:
    if not accept_language:
        return DEFAULT_LOCALE
    for part in accept_language.split(","):
        lang = part.split(";")[0].strip().split("-")[0].lower()
        if lang in LOCALES:
            return lang
    return DEFAULT_LOCALE


def get_locale() -> str:
    return _current_locale.get()


def set_current_locale(locale: str) -> None:
    _current_locale.set(locale if locale in LOCALES else DEFAULT_LOCALE)


def t(key: str, **kwargs) -> str:
    locale = get_locale()
    text = TRANSLATIONS.get(locale, {}).get(key) or TRANSLATIONS[DEFAULT_LOCALE].get(key) or key
    return text.format(**kwargs) if kwargs else text
