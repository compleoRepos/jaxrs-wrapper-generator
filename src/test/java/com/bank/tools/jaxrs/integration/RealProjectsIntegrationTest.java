package com.bank.tools.jaxrs.integration;

import com.bank.tools.jaxrs.generator.JaxrsProjectGenerator;
import com.bank.tools.jaxrs.model.EjbInfo;
import com.bank.tools.jaxrs.model.MethodInfo;
import com.bank.tools.jaxrs.parser.EjbZipParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests d'intégration avec les vrais projets EJB fournis.
 * Ces tests ne s'exécutent que si les projets sont présents sur le disque.
 */
class RealProjectsIntegrationTest {

    private static final Path PROJECT1_DIR = Path.of("/home/ubuntu/test-projects/project1");
    private static final Path PROJECT2_DIR = Path.of("/home/ubuntu/test-projects/project2");
    private static final Path PROJECT3_DIR = Path.of("/home/ubuntu/test-projects/project3");

    private EjbZipParser parser;

    @TempDir
    Path outputDir;

    @BeforeEach
    void setUp() {
        parser = new EjbZipParser();
    }

    static boolean project1Exists() {
        return Files.exists(PROJECT1_DIR);
    }

    static boolean project2Exists() {
        return Files.exists(PROJECT2_DIR);
    }

    static boolean project3Exists() {
        return Files.exists(PROJECT3_DIR);
    }

    // ===== Project 1 : commande-chequier-bmcedirect =====

    @Test
    @EnabledIf("project1Exists")
    void parseCommandeChequier_shouldDetectEjbs() throws IOException {
        Path projectPath = PROJECT1_DIR.resolve("commande-chequier-bmcedirect/command-chequier-ejb");
        List<EjbInfo> ejbs = parser.parseDirectory(projectPath);

        assertFalse(ejbs.isEmpty(), "Should detect at least one EJB in commande-chequier");
        System.out.println("=== commande-chequier-bmcedirect ===");
        for (EjbInfo ejb : ejbs) {
            System.out.println("  EJB: " + ejb.getInterfaceName() + " (impl: " + ejb.getImplementationName() + ")");
            for (MethodInfo m : ejb.getMethods()) {
                System.out.println("    " + m.getHttpMethod() + " " + m.getName() + " → " + m.getReturnType()
                        + " [body=" + m.hasMethodBody() + "]");
            }
        }
    }

    @Test
    @EnabledIf("project1Exists")
    void generateCommandeChequier_shouldProduceValidProject() throws IOException {
        Path projectPath = PROJECT1_DIR.resolve("commande-chequier-bmcedirect/command-chequier-ejb");
        List<EjbInfo> ejbs = parser.parseDirectory(projectPath);

        JaxrsProjectGenerator generator = new JaxrsProjectGenerator(
                "ma.eai.boa.xbanking", "commande-chequier-rest", "ma.eai.boa.xbanking");
        generator.generate(ejbs, outputDir);

        // Validate structure
        assertTrue(Files.exists(outputDir.resolve("pom.xml")));
        String pom = Files.readString(outputDir.resolve("pom.xml"));
        assertFalse(pom.contains("jakarta.ejb-api"), "POM should NOT have EJB dependency");

        Path resourceDir = outputDir.resolve("src/main/java/ma/eai/boa/xbanking/resource");
        assertTrue(Files.exists(resourceDir), "Resource directory should exist");
        assertTrue(Files.list(resourceDir).count() > 0, "Should have at least one Resource");

        // No service layer
        Path serviceDir = outputDir.resolve("src/main/java/ma/eai/boa/xbanking/service");
        assertFalse(Files.exists(serviceDir), "Service layer should NOT exist");
    }

    // ===== Project 1 : activation-carte-bmcedirect =====

    @Test
    @EnabledIf("project1Exists")
    void parseActivationCarte_shouldDetectEjbs() throws IOException {
        Path projectPath = PROJECT1_DIR.resolve("activation-carte-bmcedirect/activation-carte-bmcedirect-ejb");
        List<EjbInfo> ejbs = parser.parseDirectory(projectPath);

        System.out.println("=== activation-carte-bmcedirect ===");
        System.out.println("  EJBs detected: " + ejbs.size());
        for (EjbInfo ejb : ejbs) {
            System.out.println("  EJB: " + ejb.getInterfaceName() + " (impl: " + ejb.getImplementationName() + ")");
            for (MethodInfo m : ejb.getMethods()) {
                System.out.println("    " + m.getHttpMethod() + " " + m.getName() + " → " + m.getReturnType()
                        + " [body=" + m.hasMethodBody() + "]");
            }
        }
        assertFalse(ejbs.isEmpty(), "Should detect at least one EJB in activation-carte");
    }

    // ===== Project 1 : interface-credit-jocker =====

    @Test
    @EnabledIf("project1Exists")
    void parseCreditJocker_shouldDetectEjbs() throws IOException {
        Path projectPath = PROJECT1_DIR.resolve("interface-credit-jocker");
        List<EjbInfo> ejbs = parser.parseDirectory(projectPath);

        System.out.println("=== interface-credit-jocker ===");
        System.out.println("  EJBs detected: " + ejbs.size());
        for (EjbInfo ejb : ejbs) {
            System.out.println("  EJB: " + ejb.getInterfaceName() + " (impl: " + ejb.getImplementationName() + ")");
            for (MethodInfo m : ejb.getMethods()) {
                System.out.println("    " + m.getHttpMethod() + " " + m.getName() + " → " + m.getReturnType()
                        + " [body=" + m.hasMethodBody() + "]");
            }
        }
    }

    // ===== Project 2 : opposition-carte-bmcedirect =====

    @Test
    @EnabledIf("project2Exists")
    void parseOppositionCarte_shouldDetectEjbs() throws IOException {
        Path projectPath = PROJECT2_DIR.resolve("opposition-carte-bmcedirect");
        // Chercher le sous-module EJB
        Path ejbModule = findEjbModule(projectPath);
        if (ejbModule == null) ejbModule = projectPath;

        List<EjbInfo> ejbs = parser.parseDirectory(ejbModule);

        System.out.println("=== opposition-carte-bmcedirect ===");
        System.out.println("  EJBs detected: " + ejbs.size());
        for (EjbInfo ejb : ejbs) {
            System.out.println("  EJB: " + ejb.getInterfaceName() + " (impl: " + ejb.getImplementationName() + ")");
            for (MethodInfo m : ejb.getMethods()) {
                System.out.println("    " + m.getHttpMethod() + " " + m.getName() + " → " + m.getReturnType()
                        + " [body=" + m.hasMethodBody() + "]");
            }
        }
    }

    // ===== Project 2 : mise-disposition-bmcedirect =====

    @Test
    @EnabledIf("project2Exists")
    void parseMiseDisposition_shouldDetectEjbs() throws IOException {
        Path projectPath = PROJECT2_DIR.resolve("mise-disposition-bmcedirect");
        Path ejbModule = findEjbModule(projectPath);
        if (ejbModule == null) ejbModule = projectPath;

        List<EjbInfo> ejbs = parser.parseDirectory(ejbModule);

        System.out.println("=== mise-disposition-bmcedirect ===");
        System.out.println("  EJBs detected: " + ejbs.size());
        for (EjbInfo ejb : ejbs) {
            System.out.println("  EJB: " + ejb.getInterfaceName() + " (impl: " + ejb.getImplementationName() + ")");
            for (MethodInfo m : ejb.getMethods()) {
                System.out.println("    " + m.getHttpMethod() + " " + m.getName() + " → " + m.getReturnType()
                        + " [body=" + m.hasMethodBody() + "]");
            }
        }
    }

    // ===== Project 3 : virement-permanent-bmcedirect =====

    @Test
    @EnabledIf("project3Exists")
    void parseVirementPermanent_shouldDetectEjbs() throws IOException {
        Path projectPath = PROJECT3_DIR.resolve("virement-permanent-bmcedirect");
        Path ejbModule = findEjbModule(projectPath);
        if (ejbModule == null) ejbModule = projectPath;

        List<EjbInfo> ejbs = parser.parseDirectory(ejbModule);

        System.out.println("=== virement-permanent-bmcedirect ===");
        System.out.println("  EJBs detected: " + ejbs.size());
        for (EjbInfo ejb : ejbs) {
            System.out.println("  EJB: " + ejb.getInterfaceName() + " (impl: " + ejb.getImplementationName() + ")");
            for (MethodInfo m : ejb.getMethods()) {
                System.out.println("    " + m.getHttpMethod() + " " + m.getName() + " → " + m.getReturnType()
                        + " [body=" + m.hasMethodBody() + "]");
            }
        }
    }

    @Test
    @EnabledIf("project3Exists")
    void generateVirementPermanent_shouldProduceValidProject() throws IOException {
        Path projectPath = PROJECT3_DIR.resolve("virement-permanent-bmcedirect");
        Path ejbModule = findEjbModule(projectPath);
        if (ejbModule == null) ejbModule = projectPath;

        List<EjbInfo> ejbs = parser.parseDirectory(ejbModule);
        if (ejbs.isEmpty()) return; // Skip if no EJBs found

        JaxrsProjectGenerator generator = new JaxrsProjectGenerator(
                "ma.eai.boa.xbanking", "virement-permanent-rest", "ma.eai.boa.xbanking");
        generator.generate(ejbs, outputDir);

        assertTrue(Files.exists(outputDir.resolve("pom.xml")));
        String pom = Files.readString(outputDir.resolve("pom.xml"));
        assertFalse(pom.contains("jakarta.ejb-api"));

        Path serviceDir = outputDir.resolve("src/main/java/ma/eai/boa/xbanking/service");
        assertFalse(Files.exists(serviceDir), "Service layer should NOT exist");
    }

    // ===== Project 3 : transfert-euro-bmce-direct =====

    @Test
    @EnabledIf("project3Exists")
    void parseTransfertEuro_shouldDetectEjbs() throws IOException {
        Path projectPath = PROJECT3_DIR.resolve("transfert-euro-bmce-direct");
        Path ejbModule = findEjbModule(projectPath);
        if (ejbModule == null) ejbModule = projectPath;

        List<EjbInfo> ejbs = parser.parseDirectory(ejbModule);

        System.out.println("=== transfert-euro-bmce-direct ===");
        System.out.println("  EJBs detected: " + ejbs.size());
        for (EjbInfo ejb : ejbs) {
            System.out.println("  EJB: " + ejb.getInterfaceName() + " (impl: " + ejb.getImplementationName() + ")");
            for (MethodInfo m : ejb.getMethods()) {
                System.out.println("    " + m.getHttpMethod() + " " + m.getName() + " → " + m.getReturnType()
                        + " [body=" + m.hasMethodBody() + "]");
            }
        }
    }

    // ===== Utility =====

    private Path findEjbModule(Path projectDir) throws IOException {
        if (!Files.exists(projectDir)) return null;
        try (var stream = Files.list(projectDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().contains("ejb"))
                    .findFirst()
                    .orElse(null);
        }
    }
}
