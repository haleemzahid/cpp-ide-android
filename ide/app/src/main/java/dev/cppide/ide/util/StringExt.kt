package dev.cppide.ide.util

/** Convert a kebab-case slug like "comparing-numbers" to "Comparing numbers". */
fun String.slugToTitle(): String =
    replace("-", " ").replaceFirstChar { it.uppercase() }
