# Incluir en el makefile del producto AOSP de pruebas (Android 14+).
PRODUCT_PACKAGES += libepicenter_effect

# Fusiona audio_effects_config.fragment.xml con el audio_effects.xml/config del
# producto y asegúrate de que el servicio Effects AIDL realmente lea ese archivo.
# No copies el fragmento como reemplazo: conserva los efectos del fabricante.

# Selecciona exactamente un motor sin editar los algoritmos:
PRODUCT_VENDOR_PROPERTIES += ro.vendor.epicenter.engine=classic
# Alternativa para una segunda corrida/imagen:
# PRODUCT_VENDOR_PROPERTIES += ro.vendor.epicenter.engine=headphones
