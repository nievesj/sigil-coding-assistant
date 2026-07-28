package com.opencode.acp.intelligence.model

/**
 * A member (field or method) of a class, returned by `psi_file_structure`.
 *
 * @param name The member's simple name.
 * @param signature Full signature without body (e.g., "public suspend fun foo(bar: String): Int").
 * @param modifiers Modifier list (e.g., ["public", "static", "suspend"]).
 */
data class MemberInfo(
    val name: String,
    val signature: String,
    val modifiers: List<String>,
)

/**
 * A class/interface/enum/object structure within a file.
 *
 * @param name The class's simple name.
 * @param kind The symbol kind (CLASS, INTERFACE, ENUM, OBJECT).
 * @param fields Field members.
 * @param methods Method members.
 * @param nestedClasses Nested classes (recursively structured).
 */
data class ClassStructure(
    val name: String,
    val kind: SymbolKind,
    val fields: List<MemberInfo>,
    val methods: List<MemberInfo>,
    val nestedClasses: List<ClassStructure>,
)

/**
 * The structure of a file, returned by `psi_file_structure`.
 *
 * @param file Project-relative file path.
 * @param language The source language (e.g., "Java", "Kotlin").
 * @param classes Top-level classes in the file.
 */
data class FileStructure(
    val file: String,
    val language: String,
    val classes: List<ClassStructure>,
)