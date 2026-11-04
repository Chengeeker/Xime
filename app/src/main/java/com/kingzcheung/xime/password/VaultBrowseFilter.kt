package com.kingzcheung.xime.password

internal data class VaultBrowseFilter(
    val title: String,
    val kind: Kind = Kind.All,
    val type: Int = 0,
    val folderId: String = "",
) {
    enum class Kind { All, Type, Folder, Favorites, Totp, Archived, Trash }
    fun accepts(entry: BitwardenVaultEntry): Boolean {
        if (kind == Kind.Trash) return entry.deleted
        if (entry.deleted) return false
        if (kind == Kind.Archived) return entry.archived
        if (entry.archived) return false
        return when (kind) {
            Kind.Type -> entry.type == type
            Kind.Folder -> entry.folderId == folderId
            Kind.Favorites -> entry.favorite
            Kind.Totp -> entry.values["totp"].orEmpty().isNotBlank()
            else -> true
        }
    }
}
