import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    application
}

group = "br.com.labdesktop"
version = "0.1.0"

repositories {
    mavenCentral()
    // JediTerm NÃO está no Maven Central: só neste repositório e só para este grupo.
    // exclusiveContent impede que o Gradle procure qualquer outro artefato lá (e o jediterm em outro lugar).
    exclusiveContent {
        forRepository {
            maven { url = uri("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") }
        }
        filter { includeGroup("org.jetbrains.jediterm") }
    }
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

tasks.withType<JavaCompile> {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-serial"))
}

dependencies {
    // SSH, túnel e SshServer embutido para os testes (NÃO usar sshd-mina nem sshd-netty).
    implementation("org.apache.sshd:sshd-core:2.20.0")
    // SFTP do gerenciador de arquivos: cliente (SftpClient) e, nos testes, o subsistema do servidor embutido. Mesma versão do sshd-core.
    implementation("org.apache.sshd:sshd-sftp:2.20.0")
    // Editor de texto com números de linha e realce de sintaxe do gerenciador de arquivos (BSD-3, sem dependências).
    implementation("com.fifesoft:rsyntaxtextarea:4.0.1")
    // Ed25519 do MINA e Argon2id do cofre. Declarados porque no sshd-common são opcionais.
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
    // Terminal nativo.
    implementation("org.jetbrains.jediterm:jediterm-core:3.76")
    implementation("org.jetbrains.jediterm:jediterm-ui:3.76")
    // Tema, ícones SVG e fontes.
    implementation("com.formdev:flatlaf:3.7.2")
    implementation("com.formdev:flatlaf-extras:3.7.2")
    implementation("com.formdev:flatlaf-fonts-inter:4.1")
    implementation("com.formdev:flatlaf-fonts-jetbrains-mono:2.304")
    // QR do "Configurar 2FA".
    implementation("com.google.zxing:core:3.5.4")
    // MatrixToImageWriter e BufferedImageLuminanceSource. Sem o CLI do ZXing nem os leitores extras do ImageIO.
    implementation("com.google.zxing:javase:3.5.4") {
        exclude(group = "org.jcommander")
        exclude(group = "com.github.jai-imageio")
    }
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.22.3"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    // O MINA declara slf4j-api 1.7.36 e o JediTerm 2.0.9; a 2.0.17 resolve o conflito.
    implementation("org.slf4j:slf4j-api:2.0.17")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher") // obrigatório no Gradle 9
}

application {
    mainClass = "br.com.labdesktop.Principal"
    // Estas flags valem para `./gradlew run` e para o script de instalação; o jpackage repete a lista.
    // -Xmx768m dá folga ao Argon2id de 256 MiB. As três últimas impedem que um dump de memória leve a chave embora.
    applicationDefaultJvmArgs = listOf(
        "-Dapple.laf.useScreenMenuBar=true",
        "-Dapple.awt.application.name=Lab Desktop",
        "-Dfile.encoding=UTF-8",
        "-Xmx768m",
        "-XX:+DisableAttachMechanism",
        "-XX:-HeapDumpOnOutOfMemoryError",
        "-XX:-CreateCoredumpOnCrash"
    )
}

tasks.named<JavaExec>("run") {
    // Só no `./gradlew run`: liga o vigia de latência da EDT (ARQUITETURA 6.6), que registra no log a pilha da EDT se ela
    // passar de 500 ms sem responder. O app empacotado não leva esta propriedade.
    systemProperty("labdesktop.dev", "true")
    // `./gradlew run --args="--pasta cofre-dev"`: o caminho relativo vale a partir daqui, a raiz do projeto.
    workingDir = projectDir
    // Chaves de desenvolvimento repassadas à JVM do app quando passadas ao Gradle com -P:
    //   -Plabdesktop.debug=true  liga o log de requisições (cada chamada ao agente vira uma linha no log)
    //   -Plabdesktop.acoes=false desliga as ações de escrita da lista de projetos (iniciar, parar, Abrir Claude...), que
    //                            vêm ligadas por padrão; com false a lista fica somente leitura
    for (chave in listOf("labdesktop.debug", "labdesktop.acoes")) {
        if (project.hasProperty(chave)) {
            systemProperty(chave, project.property(chave).toString())
        }
    }
}

tasks.jar {
    // lab-desktop.jar sem versão no nome: é o que o jpackage aponta em --main-jar (ARQUITETURA 11.3).
    archiveVersion = ""
    manifest {
        attributes(
            "Implementation-Title" to "Lab Desktop",
            "Implementation-Version" to project.version.toString()
        )
    }
}

tasks.test {
    useJUnitPlatform()
    // O Argon2id de produção usa 256 MiB; o heap padrão do worker de teste (512 MiB) fica apertado.
    maxHeapSize = "1g"
    // Nada em /tmp: os testes escrevem sob build/ (apagado pelo `clean`).
    val tmpDosTestes = layout.buildDirectory.dir("tmp/testes").get().asFile
    systemProperty("java.io.tmpdir", tmpDosTestes.absolutePath)
    systemProperty("file.encoding", "UTF-8")
    // Os testes de tela pintam componentes soltos numa imagem: sem janela, sem ícone saltando no Dock do Mac.
    systemProperty("java.awt.headless", "true")
    doFirst { tmpDosTestes.mkdirs() }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

// ---------------------------------------------------------------------------------------------
// Empacotamento (ESQUELETO da fase 4). Desligado por padrão: as duas tarefas só rodam com `-Pempacotar`
// (sem a propriedade o Gradle as pula, e `build` nunca depende delas).
// Segue ARQUITETURA 11.3: jlink com os módulos de `jdeps` mais jdk.localedata, depois jpackage app-image.
// Falta, na fase 4: Info.plist pós-jpackage (NSLocalNetworkUsageDescription, NSAppSleepDisabled),
// codesign ad-hoc, ícone .icns, `empacotar` dependendo de `test` e `instalarApp`.
// ---------------------------------------------------------------------------------------------
val modulosDoRuntime = "java.base,java.desktop,java.logging,java.naming,java.net.http," +
    "jdk.crypto.ec,jdk.unsupported,jdk.localedata,jdk.accessibility"

val jlinkRuntime = tasks.register<Exec>("jlinkRuntime") {
    group = "empacotamento"
    description = "Gera o runtime enxuto em build/runtime (esqueleto da fase 4; só com -Pempacotar)."
    onlyIf { project.hasProperty("empacotar") }
    dependsOn("installDist")
    val saida = layout.buildDirectory.dir("runtime")
    doFirst {
        val jdk = javaToolchains.launcherFor(java.toolchain).get().metadata.installationPath.asFile
        delete(saida)
        commandLine(
            "${jdk}/bin/jlink",
            "--add-modules", modulosDoRuntime,
            "--strip-debug", "--no-header-files", "--no-man-pages",
            "--output", saida.get().asFile.absolutePath
        )
    }
}

tasks.register<Exec>("jpackageApp") {
    group = "empacotamento"
    description = "Gera build/dist/Lab Desktop.app com jpackage (esqueleto da fase 4; só com -Pempacotar)."
    onlyIf { project.hasProperty("empacotar") }
    dependsOn(jlinkRuntime)
    val lib = layout.buildDirectory.dir("install/lab-desktop/lib")
    val destino = layout.buildDirectory.dir("dist")
    doFirst {
        val jdk = javaToolchains.launcherFor(java.toolchain).get().metadata.installationPath.asFile
        delete(destino)
        val opcoes = application.applicationDefaultJvmArgs.flatMap { listOf("--java-options", it) }
        commandLine(
            listOf(
                "${jdk}/bin/jpackage",
                "--type", "app-image",
                "--name", "Lab Desktop",
                "--mac-package-identifier", "br.com.labdesktop",
                "--runtime-image", layout.buildDirectory.dir("runtime").get().asFile.absolutePath,
                "--input", lib.get().asFile.absolutePath,
                "--main-jar", "lab-desktop.jar",
                "--main-class", "br.com.labdesktop.Principal",
                "--dest", destino.get().asFile.absolutePath,
                "--java-options", "-Dapple.awt.application.appearance=system"
            ) + opcoes
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Pacote do agente do perfil monitor (o que o instalador envia ao servidor). Montagem MANUAL, só quando se muda o commit fixado:
//   ./gradlew empacotarAgente -PpainelDir=<pasta do repositório do agente> -PagenteCommit=<hash de 40 dígitos>
//   (usa agenteVersaoBase de gradle.properties). O repositório do agente é opcional e fica fora deste repositório: sem ele o app compila e roda sem o pacote.
// Lê cada arquivo com `git show <commit>:<arquivo>` (nunca a pasta de trabalho), grava src/main/resources/agente/ e o build normal só
// CONFERE o que está lá (PacoteDoAgenteTest). É Java, não bash: a CI do Windows monta igual. Veja ARQUITETURA, "Instalador do agente".
// ---------------------------------------------------------------------------------------------
tasks.register<JavaExec>("empacotarAgente") {
    group = "empacotamento"
    description = "Monta src/main/resources/agente/agente-<versao>.tar.gz a partir do repositório do agente no commit indicado em -PagenteCommit."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "br.com.labdesktop.instalador.MontadorDoPacote"
    doFirst {
        val painel = providers.gradleProperty("painelDir").orNull ?: throw GradleException("Falta a pasta do repositório do agente: passe -PpainelDir=<pasta>")
        val commit = providers.gradleProperty("agenteCommit").orNull ?: throw GradleException("Falta o commit do agente: passe -PagenteCommit=<hash de 40 dígitos do repositório do agente>")
        val base = providers.gradleProperty("agenteVersaoBase").orNull ?: throw GradleException("Falta agenteVersaoBase em gradle.properties")
        args(painel, commit, base, layout.projectDirectory.dir("src/main/resources/agente").asFile.absolutePath)
    }
}
