// Código Java puro usado pelo build.gradle.kts da raiz (a versão do app e o mapeamento dela para o jpackage). Fica aqui, e não dentro do script,
// para ser testado: o build.gradle.kts da raiz acrescenta esta pasta às fontes dos testes (VersaoDaCompilacaoTest, VersaoDoPacoteTest).
plugins {
    `java-library`
}

tasks.withType<JavaCompile> {
    options.release = 21
    options.encoding = "UTF-8"
}
