import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
    // PTY local do terminal de "Este computador": o mesmo que o JediTerm usa nas IDEs da JetBrains (EPL-1.0). Traz o forkpty do
    // Mac e do Linux e o ConPTY do Windows num só jar, então o código do terminal local é o mesmo nos três sistemas. No Maven Central.
    implementation("org.jetbrains.pty4j:pty4j:0.13.13")
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

// A licença do app e a tabela de licenças de terceiros vão para dentro do jar: a tela Ajustes > Sobre as lê dali (nada de rede). Uma só fonte, na raiz.
tasks.processResources {
    from(layout.projectDirectory.file("LICENSE")) { into("br/com/labdesktop/sobre") }
    from(layout.projectDirectory.file("THIRD-PARTY-NOTICES.md")) { into("br/com/labdesktop/sobre") }
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
    // as provas que só fazem sentido num Windows de verdade ficam de fora; rodam com `./gradlew provaWindows` (job do Windows do CI, sem bloquear)
    useJUnitPlatform { excludeTags("prova-windows") }
    // O Argon2id de produção usa 256 MiB; o heap padrão do worker de teste (512 MiB) fica apertado.
    maxHeapSize = "1g"
    // Nada em /tmp: os testes escrevem sob build/ (apagado pelo `clean`).
    val tmpDosTestes = layout.buildDirectory.dir("tmp/testes").get().asFile
    systemProperty("java.io.tmpdir", tmpDosTestes.absolutePath)
    systemProperty("file.encoding", "UTF-8")
    // O teste do laboratório local com o Docker de VERDADE (containers e imagens `labteste-*`, removidos no fim) só roda com
    // `./gradlew test -Plabdesktop.dockerReal=true --tests '*DockerDeVerdade*'`: o build normal nunca toca no Docker de quem o roda.
    if (project.hasProperty("labdesktop.dockerReal")) {
        systemProperty("labdesktop.dockerReal", project.property("labdesktop.dockerReal").toString())
    }
    // `./gradlew test -Plabdesktop.laf=generico` roda a suíte inteira com o LaF do Windows e do Linux (FlatLightLaf/FlatDarkLaf) num Mac (ou `mac`, o contrário): os PNGs saem em build/telas-generico/.
    if (project.hasProperty("labdesktop.laf")) {
        systemProperty("labdesktop.laf", project.property("labdesktop.laf").toString())
    }
    // Os testes de tela pintam componentes soltos numa imagem: sem janela, sem ícone saltando no Dock do Mac.
    systemProperty("java.awt.headless", "true")
    doFirst { tmpDosTestes.mkdirs() }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

tasks.register<Test>("provaWindows") {
    group = "verification"
    description = "Provas que só rodam num Windows de verdade (ACL do NTFS, junção, registro do tema...). Em outro sistema não roda nada."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("prova-windows") }
    val tmpDosTestes = layout.buildDirectory.dir("tmp/testes").get().asFile
    systemProperty("java.io.tmpdir", tmpDosTestes.absolutePath)
    systemProperty("java.awt.headless", "true")
    doFirst { tmpDosTestes.mkdirs() }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = TestExceptionFormat.FULL
    }
    failOnNoDiscoveredTests = false
}

// ---------------------------------------------------------------------------------------------
// Empacotamento. O app leva o Java junto: `jlink` monta um runtime enxuto e o `jpackage` junta runtime, jars e ícone.
// As tarefas são NEUTRAS de sistema (o mesmo arquivo roda no Mac, no Windows e no Linux) e só geram o instalador DO sistema onde rodam (o jlink e o
// jpackage não fazem cruzado), por isso o CI tem um job por sistema. Nada disto entra no `build`: só `./gradlew empacotar` (ou as tarefas soltas).
//
//   ./gradlew empacotar        -> build/instaladores/: Windows .msi + .zip portátil; Mac .dmg; Linux .tar.gz portátil (e SHA256SUMS.txt)
//   ./gradlew jpackageImagem   -> só a imagem (o .app, ou a pasta com o .exe), sem WiX nem dmg
//   ./gradlew verificarModulos -> compara os módulos do jlink com o que `jdeps` acha nos jars (falha se faltar algum)
//   ./gradlew conferirImagem   -> roda o app da imagem com --autoteste (sem janela) usando o runtime enxuto
//
// Windows: o `.msi` precisa do WiX Toolset 3.x no PATH (candle.exe e light.exe); o jpackage do JDK 21 não usa o WiX 4 nem o 5. O workflow instala com o Chocolatey.
// Mac: Info.plist ajustado depois do jpackage (NSLocalNetworkUsageDescription, NSAppSleepDisabled) e assinatura AD-HOC (sem Developer ID: ver docs/INSTALAR.md).
// ---------------------------------------------------------------------------------------------
val nomeDoSistema = System.getProperty("os.name").lowercase()
val ehMac = nomeDoSistema.contains("mac")
val ehWindows = nomeDoSistema.contains("win")
val sistemaNoNome = if (ehMac) "mac" else if (ehWindows) "windows" else "linux"
val arquiteturaNoNome = System.getProperty("os.arch").lowercase().let { if (it == "aarch64" || it == "arm64") "arm64" else "x64" }
val nomeDoApp = "Lab Desktop"
val versaoDoApp = project.version.toString()
// O macOS não aceita um pacote cuja primeira parte da versão é 0 (o jpackage recusa: "The first number in an app-version cannot be zero"). No Mac a versão do BUNDLE é a do app com o
// primeiro número SOMADO DE 1, SEMPRE (0.1.0 -> 1.1.0; a futura 1.0.0 -> 2.0.0): assim a versão do bundle nunca recua (se só o 0.x fosse mapeado, a 1.0.0 de verdade ficaria MENOR que a
// 1.1.0 do bundle de 0.1.0 e o macOS trataria a atualização como retrocesso). No Windows (.msi) a versão do app vale como está. O nome dos arquivos e `--versao` do app dizem a versão de verdade.
fun versaoDoBundleDoMac(versao: String): String {
    val partes = versao.split(".")
    val primeiro = partes.first().toIntOrNull() ?: throw GradleException("A versão do app precisa começar com um número: $versao")
    return (listOf((primeiro + 1).toString()) + partes.drop(1)).joinToString(".")
}
val versaoDoPacote: String = if (ehMac) versaoDoBundleDoMac(versaoDoApp) else versaoDoApp

// Os módulos do runtime. Os de `jdeps` mais o que o `jdeps` não vê (carregado por nome ou serviço): java.logging (JUL das bibliotecas), java.management e jdk.management (memória total em "Este
// computador"), jdk.crypto.ec (ECDH/ECDSA do SSH, por ServiceLoader), jdk.unsupported (sun.misc.Unsafe de bibliotecas), jdk.localedata (pt-BR) e jdk.accessibility (leitor de tela).
val modulosDoRuntime = "java.base,java.desktop,java.logging,java.naming,java.net.http,java.management,jdk.management," +
    "java.prefs,java.rmi,java.security.jgss,java.sql,jdk.crypto.ec,jdk.unsupported,jdk.localedata,jdk.accessibility"

fun ferramentaDoJdk(nome: String): String {
    val jdk = javaToolchains.launcherFor(java.toolchain).get().metadata.installationPath.asFile
    return jdk.resolve("bin").resolve(if (ehWindows) "$nome.exe" else nome).absolutePath
}

val pastaDeEmpacotamento = layout.buildDirectory.dir("empacotamento")

val jlinkRuntime = tasks.register<Exec>("jlinkRuntime") {
    group = "empacotamento"
    description = "Gera o runtime enxuto (jlink) em build/empacotamento/runtime."
    dependsOn("installDist")
    val saida = pastaDeEmpacotamento.map { it.dir("runtime") }
    inputs.property("modulos", modulosDoRuntime)
    outputs.dir(saida)
    doFirst {
        delete(saida)
        commandLine(
            ferramentaDoJdk("jlink"),
            "--add-modules", modulosDoRuntime,
            "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=zip-6",
            "--output", saida.get().asFile.absolutePath
        )
    }
}

tasks.register("verificarModulos") {
    group = "empacotamento"
    description = "Falha se o jdeps achar nos jars um módulo do Java que o jlink não leva."
    dependsOn("installDist")
    doLast {
        val lib = layout.buildDirectory.dir("install/lab-desktop/lib").get().asFile
        val jars = lib.listFiles { f -> f.name.endsWith(".jar") }!!.map { it.absolutePath }
        // as mensagens de "split package" (jars que repartem um pacote) saem junto: o que interessa é a ÚLTIMA linha, a lista de módulos
        val processo = ProcessBuilder(listOf(ferramentaDoJdk("jdeps"), "--multi-release", "21", "--ignore-missing-deps", "--print-module-deps", "-cp", lib.absolutePath + File.separator + "*") + jars)
            .redirectErrorStream(true).start()
        val todaASaida = processo.inputStream.bufferedReader().readText()
        if (processo.waitFor() != 0) throw GradleException("jdeps falhou:\n$todaASaida")
        val saida = todaASaida.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("Warning") } ?: throw GradleException("jdeps não devolveu nada")
        val achados = saida.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val levados = modulosDoRuntime.split(",").toSet()
        val faltam = achados.filter { it !in levados }
        if (faltam.isNotEmpty()) throw GradleException("O jdeps achou módulos que o jlink não leva: $faltam. Acrescente em modulosDoRuntime.")
        println("Módulos de jdeps: $achados (todos no runtime). Extras levados de propósito: ${levados - achados.toSet()}")
    }
}

// As opções da JVM do app empacotado. Comuns aos três sistemas; as do Mac só no Mac (a barra de menus do sistema e o nome no Dock não existem fora dele).
fun opcoesDaJvm(): List<String> {
    val comuns = listOf("-Dfile.encoding=UTF-8", "-Xmx768m", "-XX:+DisableAttachMechanism", "-XX:-HeapDumpOnOutOfMemoryError", "-XX:-CreateCoredumpOnCrash")
    // (o jpackage corta a opção no espaço: "-Dapple.awt.application.name=Lab Desktop" viraria duas; o nome do app no Dock vem do CFBundleName do .app e, rodando solto, do Principal)
    val doMac = if (ehMac) listOf("-Dapple.laf.useScreenMenuBar=true", "-Dapple.awt.application.appearance=system") else emptyList()
    return comuns + doMac
}

val iconeDoSistema = layout.projectDirectory.file(if (ehMac) "empacotamento/icones/lab-desktop.icns" else if (ehWindows) "empacotamento/icones/lab-desktop.ico" else "empacotamento/icones/lab-desktop.png")

val jpackageImagem = tasks.register<Exec>("jpackageImagem") {
    group = "empacotamento"
    description = "Gera a imagem do app com o Java junto (o .app no Mac; a pasta com o .exe no Windows) em build/empacotamento/imagem."
    dependsOn(jlinkRuntime)
    val lib = layout.buildDirectory.dir("install/lab-desktop/lib")
    val destino = pastaDeEmpacotamento.map { it.dir("imagem") }
    // Sem declarar o que entra, o Gradle achava a imagem "em dia" depois de o código mudar e o instalador levava o app VELHO.
    inputs.file(iconeDoSistema)
    inputs.dir(lib)
    inputs.dir(pastaDeEmpacotamento.map { it.dir("runtime") })
    inputs.property("versaoDoPacote", versaoDoPacote)
    inputs.property("opcoesDaJvm", opcoesDaJvm().joinToString(" "))
    outputs.dir(destino)
    doFirst {
        delete(destino)
        val comandos = mutableListOf(
            ferramentaDoJdk("jpackage"),
            "--type", "app-image",
            "--name", nomeDoApp,
            "--app-version", versaoDoPacote,
            "--vendor", "Thiago Bezerra",
            "--description", "Cliente do Painel do Lab: servidores, projetos, arquivos e terminal",
            "--copyright", "Thiago Bezerra",
            "--icon", iconeDoSistema.asFile.absolutePath,
            "--runtime-image", pastaDeEmpacotamento.get().dir("runtime").asFile.absolutePath,
            "--input", lib.get().asFile.absolutePath,
            "--main-jar", "lab-desktop.jar",
            "--main-class", "br.com.labdesktop.Principal",
            "--dest", destino.get().asFile.absolutePath
        )
        for (o in opcoesDaJvm()) { comandos += listOf("--java-options", o) }
        if (ehMac) {
            comandos += listOf("--mac-package-identifier", "br.com.labdesktop", "--mac-package-name", nomeDoApp)
        }
        commandLine(comandos)
    }
    doLast {
        if (ehMac) {
            // O que o jpackage não deixa pôr: a permissão de Rede Local (o app fala com servidores da rede local) e o app que não dorme com a janela escondida.
            val plist = destino.get().asFile.resolve("$nomeDoApp.app/Contents/Info.plist").absolutePath
            fun plutil(vararg args: String) {
                val p = ProcessBuilder(listOf("plutil") + args).redirectErrorStream(true).start()
                val saida = p.inputStream.bufferedReader().readText()
                if (p.waitFor() != 0) throw GradleException("plutil falhou: $saida")
            }
            plutil("-replace", "NSLocalNetworkUsageDescription", "-string", "O Lab Desktop fala com os servidores da sua rede (por exemplo o do laboratório) por SSH.", plist)
            plutil("-replace", "NSAppSleepDisabled", "-bool", "true", plist)
            plutil("-replace", "LSApplicationCategoryType", "-string", "public.app-category.developer-tools", plist)
            // o app não usa o microfone: o texto de permissão que o jpackage deixa no plist faria o macOS poder perguntar por ele; o Java 21 pede o macOS 11 ou mais novo
            ProcessBuilder("plutil", "-remove", "NSMicrophoneUsageDescription", plist).redirectErrorStream(true).start().also { it.inputStream.readBytes(); it.waitFor() } // sem a chave: sem problema
            plutil("-replace", "LSMinimumSystemVersion", "-string", "11.0", plist)
            // assinatura ad-hoc depois de mexer no Info.plist (sem Developer ID; o Gatekeeper avisa na primeira abertura: docs/INSTALAR.md)
            val app = destino.get().asFile.resolve("$nomeDoApp.app").absolutePath
            val p = ProcessBuilder("codesign", "--force", "--deep", "--sign", "-", app).redirectErrorStream(true).start()
            val saida = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0) throw GradleException("codesign ad-hoc falhou: $saida")
        }
    }
}

val pastaDosInstaladores = layout.buildDirectory.dir("instaladores")
val nomeBase = "LabDesktop-$versaoDoApp-$sistemaNoNome-$arquiteturaNoNome"

val jpackageInstalador = tasks.register<Exec>("jpackageInstalador") {
    group = "empacotamento"
    description = "Gera o instalador do sistema: .msi no Windows (precisa do WiX 3), .dmg no Mac. No Linux não faz nada (só o .tar.gz portátil)."
    dependsOn(jpackageImagem)
    onlyIf { ehMac || ehWindows }
    val imagem = pastaDeEmpacotamento.map { it.dir("imagem") }
    val destino = pastaDeEmpacotamento.map { it.dir("instalador") }
    inputs.dir(imagem)
    inputs.property("versaoDoPacote", versaoDoPacote)
    outputs.dir(destino)
    doFirst {
        delete(destino)
        val comandos = mutableListOf(
            ferramentaDoJdk("jpackage"),
            "--type", if (ehWindows) "msi" else "dmg",
            "--name", nomeDoApp,
            "--app-version", versaoDoPacote,
            "--vendor", "Thiago Bezerra",
            "--dest", destino.get().asFile.absolutePath
        )
        if (ehWindows) {
            comandos += listOf(
                "--app-image", imagem.get().asFile.resolve(nomeDoApp).absolutePath,
                "--win-per-user-install", "--win-menu", "--win-menu-group", nomeDoApp, "--win-shortcut",
                // O UUID de atualização é FIXO: o Windows reconhece uma versão nova como atualização da mesma instalação (nunca mude).
                "--win-upgrade-uuid", "d96b92d7-db83-49cd-8d70-5a9f62d546c0",
                "--license-file", layout.projectDirectory.file("empacotamento/windows/licenca.txt").asFile.absolutePath
            )
        } else {
            comandos += listOf("--app-image", imagem.get().asFile.resolve("$nomeDoApp.app").absolutePath, "--mac-package-identifier", "br.com.labdesktop")
        }
        commandLine(comandos)
    }
}

val empacotar = tasks.register("empacotar") {
    group = "empacotamento"
    description = "Gera tudo do sistema onde roda em build/instaladores/ (instalador, versão portátil e SHA256SUMS.txt)."
    dependsOn(jpackageInstalador)
    doLast {
        val saida = pastaDosInstaladores.get().asFile
        delete(saida)
        saida.mkdirs()
        val imagem = pastaDeEmpacotamento.get().dir("imagem").asFile
        val instalador = pastaDeEmpacotamento.get().dir("instalador").asFile
        val gerados = mutableListOf<File>()
        if (ehWindows || ehMac) {
            val feito = instalador.listFiles()!!.firstOrNull { it.name.endsWith(if (ehWindows) ".msi" else ".dmg") }
                ?: throw GradleException("O jpackage não gerou o instalador em $instalador")
            val alvo = saida.resolve("$nomeBase.${if (ehWindows) "msi" else "dmg"}")
            feito.copyTo(alvo)
            gerados += alvo
        }
        if (ehWindows || project.hasProperty("zipPortatil")) { // -PzipPortatil faz o .zip também fora do Windows (só para provar o código num Mac; no Mac o .app zipado perde links e permissões)
            // versão portátil: a pasta inteira num .zip (não precisa do WiX nem instalar; roda de qualquer pasta). Feito em Java (ZipOutputStream) e não com o `tar` do PATH:
            // no Git Bash do runner o `tar` é o GNU, que não grava .zip.
            val zip = saida.resolve("$nomeBase-portatil.zip")
            ZipOutputStream(zip.outputStream().buffered()).use { z ->
                val pasta = imagem.resolve(if (ehMac) "$nomeDoApp.app" else nomeDoApp)
                pasta.walkTopDown().filter { it.isFile }.forEach { f ->
                    z.putNextEntry(ZipEntry(nomeDoApp + "/" + f.relativeTo(pasta).path.replace(File.separatorChar, '/')))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
            gerados += zip
        }
        if (!ehWindows && !ehMac) {
            val tar = saida.resolve("$nomeBase-portatil.tar.gz")
            val p = ProcessBuilder("tar", "-czf", tar.absolutePath, "-C", imagem.absolutePath, nomeDoApp).redirectErrorStream(true).start()
            val texto = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0) throw GradleException("Não consegui montar o .tar.gz portátil: $texto")
            gerados += tar
        }
        val soma = gerados.joinToString("\n") { f ->
            val h = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            "$h  ${f.name}"
        }
        saida.resolve("SHA256SUMS-$sistemaNoNome-$arquiteturaNoNome.txt").writeText(soma + "\n")
        println("Instaladores em $saida:\n" + gerados.joinToString("\n") { "  ${it.name} (${it.length() / 1024 / 1024} MB)" })
    }
}

tasks.register<Exec>("conferirImagem") {
    group = "empacotamento"
    description = "Roda o app da imagem gerada com --autoteste (sem janela, com o runtime enxuto): prova que os módulos do jlink bastam."
    dependsOn(jpackageImagem)
    doFirst {
        // scripts/ConferirEmpacotamento.java é um arquivo único (source launcher do JDK): acha o java e os jars DENTRO da imagem e roda `Principal --autoteste`
        commandLine(ferramentaDoJdk("java"), layout.projectDirectory.file("scripts/ConferirEmpacotamento.java").asFile.absolutePath,
            pastaDeEmpacotamento.get().dir("imagem").asFile.absolutePath)
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
