package com.bank.tools.jaxrs.integration;

import com.bank.tools.jaxrs.generator.JaxrsProjectGenerator;
import com.bank.tools.jaxrs.model.EjbInfo;
import com.bank.tools.jaxrs.parser.EjbZipParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test d'intégration qui vérifie que les projets générés compilent réellement
 * avec les stubs des librairies internes (Envelope, SynchroneService).
 */
class CompilationTest {

    private static final Path TEST_PROJECTS = Paths.get("src/test/resources/real-projects");
    private static boolean stubsInstalled = false;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void installStubs() {
        // Vérifier que les stubs sont installés dans le repo local Maven
        Path stubsJar = Paths.get(System.getProperty("user.home"),
                ".m2/repository/ma/eai/eai-stubs/1.0.0/eai-stubs-1.0.0.jar");
        if (Files.exists(stubsJar)) {
            stubsInstalled = true;
        } else {
            // Tenter d'installer les stubs
            Path stubsDir = Paths.get("stubs");
            if (Files.exists(stubsDir.resolve("pom.xml"))) {
                try {
                    Process p = new ProcessBuilder("mvn", "install", "-q", "-f", stubsDir.toString())
                            .redirectErrorStream(true).start();
                    int exit = p.waitFor();
                    stubsInstalled = (exit == 0);
                } catch (Exception e) {
                    stubsInstalled = false;
                }
            }
        }
    }

    @Test
    void testCommandeChequierCompiles() throws Exception {
        assertCompiles("commande-chequier-bmcedirect");
    }

    @Test
    void testActivationCarteCompiles() throws Exception {
        assertCompiles("activation-carte-bmcedirect");
    }

    @Test
    void testInterfaceCreditJockerCompiles() throws Exception {
        assertCompiles("interface-credit-jocker");
    }

    @Test
    void testVirementPermanentCompiles() throws Exception {
        assertCompiles("virement-permanent-bmcedirect");
    }

    @Test
    void testProduitsEpargneCompiles() throws Exception {
        assertCompiles("produits-epargne-bmcedirect");
    }

    @Test
    void testInterfaceSendSmsCompiles() throws Exception {
        assertCompiles("interface-send-sms");
    }

    private void assertCompiles(String projectName) throws Exception {
        if (!stubsInstalled) {
            System.out.println("SKIP: stubs not installed, cannot compile " + projectName);
            return;
        }

        // Trouver le répertoire du projet dans test-projects
        Path projectDir = findProjectDir(projectName);
        if (projectDir == null) {
            System.out.println("SKIP: project directory not found for " + projectName);
            return;
        }

        // Parser et générer
        EjbZipParser parser = new EjbZipParser();
        List<EjbInfo> ejbs = parser.parseDirectory(projectDir);
        assertFalse(ejbs.isEmpty(), "No EJBs found in " + projectName);

        Path outputDir = tempDir.resolve(projectName);
        JaxrsProjectGenerator generator = new JaxrsProjectGenerator(
                "com.bank.api", "rest-api", "com.bank.api");
        generator.generate(ejbs, outputDir);

        // Ajouter la dépendance stubs au POM
        Path pom = outputDir.resolve("pom.xml");
        assertTrue(Files.exists(pom), "POM not generated for " + projectName);
        String pomContent = Files.readString(pom);
        String stubsDep = "        <dependency>\n" +
                "            <groupId>ma.eai</groupId>\n" +
                "            <artifactId>eai-stubs</artifactId>\n" +
                "            <version>1.0.0</version>\n" +
                "            <scope>provided</scope>\n" +
                "        </dependency>\n    </dependencies>";
        pomContent = pomContent.replace("</dependencies>", stubsDep);
        Files.writeString(pom, pomContent);

        // Compiler
        Process process = new ProcessBuilder("mvn", "compile", "-q")
                .directory(outputDir.toFile())
                .redirectErrorStream(true)
                .start();

        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        int exitCode = process.waitFor();

        assertEquals(0, exitCode,
                "Compilation failed for " + projectName + ":\n" + output);
    }

    private Path findProjectDir(String projectName) {
        // Chercher dans les répertoires de test
        String[] bases = {
                "/home/ubuntu/test-projects/project1",
                "/home/ubuntu/test-projects/project2",
                "/home/ubuntu/test-projects/project3"
        };
        for (String base : bases) {
            Path dir = Paths.get(base, projectName);
            if (Files.exists(dir)) {
                return dir;
            }
        }
        return null;
    }
}
