plugins {
    // Todos os plugins no mesmo classloader da raiz: o Kotlin embutido do AGP 9
    // precisa enxergar as classes do AGP e vice-versa.
    id("com.android.application") apply false
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
}
