package com.bank.tools.jaxrs.integration;

import com.bank.tools.jaxrs.generator.JaxrsProjectGenerator;
import com.bank.tools.jaxrs.model.EjbInfo;
import com.bank.tools.jaxrs.model.MethodInfo;
import com.bank.tools.jaxrs.parser.EjbZipParser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Audit complet : parse et génère pour TOUS les projets EJB fournis.
 * Vérifie la détection, l'extraction des corps, et la qualité de la génération.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FullAuditTest {

    private static final Path PROJECT1_DIR = Path.of("/home/ubuntu/test-projects/project1");
    private static final Path PROJECT2_DIR = Path.of("/home/ubuntu/test-projects/project2");
    private static final Path PROJECT3_DIR = Path.of("/home/ubuntu/test-projects/project3");

    private static final EjbZipParser parser = new EjbZipParser();
    private static final Map<String, List<EjbInfo>> auditResults = new LinkedHashMap<>();

    @TempDir
    Path outputDir;

    // ===== HELPER =====

    private Path findEjbModule(Path projectDir) throws IOException {
        if (!Files.exists(projectDir)) return null;
        // Chercher un sous-répertoire contenant "ejb" dans le nom
        try (var stream = Files.list(projectDir)) {
            Optional<Path> ejbDir = stream
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().toLowerCase().contains("ejb"))
                    .findFirst();
            if (ejbDir.isPresent()) return ejbDir.get();
        }
        return projectDir;
    }

    private List<EjbInfo> parseProject(String name, Path baseDir) throws IOException {
        if (!Files.exists(baseDir)) {
            System.out.println("[SKIP] " + name + " — répertoire inexistant: " + baseDir);
            return Collections.emptyList();
        }
        Path ejbModule = findEjbModule(baseDir);
        List<EjbInfo> ejbs = parser.parseDirectory(ejbModule);
        auditResults.put(name, ejbs);
        return ejbs;
    }

    private void printEjbs(String name, List<EjbInfo> ejbs) {
        System.out.println("\n=== " + name + " ===");
        System.out.println("  EJBs détectés: " + ejbs.size());
        for (EjbInfo ejb : ejbs) {
            System.out.println("  EJB: " + ejb.getInterfaceName()
                    + " (impl: " + ejb.getImplementationName() + ", type: " + ejb.getEjbType() + ")");
            for (MethodInfo m : ejb.getMethods()) {
                System.out.println("    " + m.getHttpMethod() + " " + m.getName()
                        + "(" + m.getParameters().size() + " params) → " + m.getReturnType()
                        + " [body=" + m.hasMethodBody() + "]");
            }
        }
    }

    private void validateGeneration(String name, List<EjbInfo> ejbs) throws IOException {
        if (ejbs.isEmpty()) {
            System.out.println("  [WARN] Aucun EJB détecté — pas de génération possible");
            return;
        }

        Path genDir = outputDir.resolve(name);
        JaxrsProjectGenerator generator = new JaxrsProjectGenerator(
                "ma.eai.boa.xbanking", name + "-rest", "ma.eai.boa.xbanking");
        generator.generate(ejbs, genDir, parser.getParsedClassMap());

        // Vérifications structurelles
        String webModule = name + "-rest-web";
        assertTrue(Files.exists(genDir.resolve("pom.xml")), name + ": POM parent manquant");

        String pom = Files.readString(genDir.resolve("pom.xml"));
        assertTrue(pom.contains("javaee-api"), name + ": POM doit contenir javaee-api (Java EE 7)");
        assertFalse(pom.contains("jakarta"), name + ": POM ne doit PAS contenir jakarta (Java EE 7 = javax)");
        assertTrue(pom.contains("<source>1.8</source>"), name + ": POM doit cibler Java 1.8");
        assertTrue(pom.contains("<packaging>pom</packaging>"), name + ": Parent POM doit être pom");

        // Vérifier modules
        assertTrue(Files.exists(genDir.resolve(name + "-rest-ejb/pom.xml")), name + ": EJB POM manquant");
        assertTrue(Files.exists(genDir.resolve(name + "-rest-ear/pom.xml")), name + ": EAR POM manquant");
        assertTrue(Files.exists(genDir.resolve(webModule + "/pom.xml")), name + ": Web POM manquant");

        Path resourceDir = genDir.resolve(webModule + "/src/main/java/ma/eai/boa/xbanking/resource");
        assertTrue(Files.exists(resourceDir), name + ": répertoire resource/ manquant");
        long resourceCount = Files.list(resourceDir).count();
        assertEquals(ejbs.size(), resourceCount, name + ": nombre de Resources != nombre d'EJBs");

        // Pas de service layer dans le web module
        Path serviceDir = genDir.resolve(webModule + "/src/main/java/ma/eai/boa/xbanking/service");
        assertFalse(Files.exists(serviceDir), name + ": service/ ne doit PAS exister dans web");

        // Vérifier contenu des Resources (pattern POJO + lazy JNDI)
        for (Path resFile : Files.list(resourceDir).toList()) {
            String content = Files.readString(resFile);
            assertTrue(content.contains("InitialContext"), name + "/" + resFile.getFileName() + ": InitialContext manquant (lazy JNDI)");
            assertFalse(content.contains("@EJB"), name + "/" + resFile.getFileName() + ": @EJB interdit (POJO mode)");
            assertFalse(content.contains("@ApplicationScoped"), name + "/" + resFile.getFileName() + ": @ApplicationScoped interdit (POJO mode)");
            assertTrue(content.contains("SynchroneService"), name + "/" + resFile.getFileName() + ": SynchroneService manquant");
            assertTrue(content.contains("@Path("), name + "/" + resFile.getFileName() + ": @Path manquant");
            assertTrue(content.contains("@Produces(MediaType.APPLICATION_JSON)"), name + "/" + resFile.getFileName() + ": @Produces manquant");
            assertTrue(content.contains("CodeMapper.isSuccess"), name + "/" + resFile.getFileName() + ": CodeMapper manquant");
            assertTrue(content.contains("converter.toEnvelope"), name + "/" + resFile.getFileName() + ": converter.toEnvelope manquant");
            assertFalse(content.contains("jakarta"), name + "/" + resFile.getFileName() + ": jakarta interdit (Java EE 7)");
        }

        // Vérifier DTOs
        Path dtoDir = genDir.resolve(webModule + "/src/main/java/ma/eai/boa/xbanking/dto");
        assertTrue(Files.exists(dtoDir), name + ": répertoire dto/ manquant");
        assertTrue(Files.exists(dtoDir.resolve("ErrorResponse.java")), name + ": ErrorResponse.java manquant");

        System.out.println("  [OK] Génération validée: " + resourceCount + " Resource(s), "
                + Files.list(dtoDir).count() + " DTO(s)");
    }

    // ===== PROJECT 1 =====

    @Test @Order(1)
    void audit_project1_commandeChequier() throws IOException {
        List<EjbInfo> ejbs = parseProject("commande-chequier-bmcedirect",
                PROJECT1_DIR.resolve("commande-chequier-bmcedirect"));
        printEjbs("commande-chequier-bmcedirect", ejbs);
        validateGeneration("commande-chequier-bmcedirect", ejbs);
    }

    @Test @Order(2)
    void audit_project1_activationCarte() throws IOException {
        List<EjbInfo> ejbs = parseProject("activation-carte-bmcedirect",
                PROJECT1_DIR.resolve("activation-carte-bmcedirect"));
        printEjbs("activation-carte-bmcedirect", ejbs);
        validateGeneration("activation-carte-bmcedirect", ejbs);
    }

    @Test @Order(3)
    void audit_project1_interfaceCreditJocker() throws IOException {
        List<EjbInfo> ejbs = parseProject("interface-credit-jocker",
                PROJECT1_DIR.resolve("interface-credit-jocker"));
        printEjbs("interface-credit-jocker", ejbs);
        validateGeneration("interface-credit-jocker", ejbs);
    }

    @Test @Order(4)
    void audit_project1_avisOpere() throws IOException {
        List<EjbInfo> ejbs = parseProject("avis-opere",
                PROJECT1_DIR.resolve("avis-opere"));
        printEjbs("avis-opere", ejbs);
        validateGeneration("avis-opere", ejbs);
    }

    @Test @Order(5)
    void audit_project1_coordonnees3dsecure() throws IOException {
        List<EjbInfo> ejbs = parseProject("coordonnees-3dsecure-bmcedirect",
                PROJECT1_DIR.resolve("coordonnees-3dsecure-bmcedirect"));
        printEjbs("coordonnees-3dsecure-bmcedirect", ejbs);
        validateGeneration("coordonnees-3dsecure-bmcedirect", ejbs);
    }

    @Test @Order(6)
    void audit_project1_demandeDotation() throws IOException {
        List<EjbInfo> ejbs = parseProject("demande-dotation",
                PROJECT1_DIR.resolve("demande-dotation"));
        printEjbs("demande-dotation", ejbs);
        validateGeneration("demande-dotation", ejbs);
    }

    // ===== PROJECT 2 =====

    @Test @Order(7)
    void audit_project2_interfaceSendSms() throws IOException {
        List<EjbInfo> ejbs = parseProject("interface-send-sms",
                PROJECT2_DIR.resolve("interface-send-sms"));
        printEjbs("interface-send-sms", ejbs);
        validateGeneration("interface-send-sms", ejbs);
    }

    @Test @Order(8)
    void audit_project2_miseDisposition() throws IOException {
        List<EjbInfo> ejbs = parseProject("mise-disposition-bmcedirect",
                PROJECT2_DIR.resolve("mise-disposition-bmcedirect"));
        printEjbs("mise-disposition-bmcedirect", ejbs);
        validateGeneration("mise-disposition-bmcedirect", ejbs);
    }

    @Test @Order(9)
    void audit_project2_operationAvenirServices() throws IOException {
        List<EjbInfo> ejbs = parseProject("operation-avenir-services",
                PROJECT2_DIR.resolve("operation-avenir-services"));
        printEjbs("operation-avenir-services", ejbs);
        validateGeneration("operation-avenir-services", ejbs);
    }

    @Test @Order(10)
    void audit_project2_oppositionCarte() throws IOException {
        List<EjbInfo> ejbs = parseProject("opposition-carte-bmcedirect",
                PROJECT2_DIR.resolve("opposition-carte-bmcedirect"));
        printEjbs("opposition-carte-bmcedirect", ejbs);
        validateGeneration("opposition-carte-bmcedirect", ejbs);
    }

    @Test @Order(11)
    void audit_project2_produitsEpargne() throws IOException {
        List<EjbInfo> ejbs = parseProject("produits-epargne-bmcedirect",
                PROJECT2_DIR.resolve("produits-epargne-bmcedirect"));
        printEjbs("produits-epargne-bmcedirect", ejbs);
        validateGeneration("produits-epargne-bmcedirect", ejbs);
    }

    @Test @Order(12)
    void audit_project2_pushNotification() throws IOException {
        List<EjbInfo> ejbs = parseProject("push-notification",
                PROJECT2_DIR.resolve("push-notification"));
        printEjbs("push-notification", ejbs);
        validateGeneration("push-notification", ejbs);
    }

    @Test @Order(13)
    void audit_project2_releveCarte() throws IOException {
        List<EjbInfo> ejbs = parseProject("releve-carte-bmcedirect",
                PROJECT2_DIR.resolve("releve-carte-bmcedirect"));
        printEjbs("releve-carte-bmcedirect", ejbs);
        validateGeneration("releve-carte-bmcedirect", ejbs);
    }

    @Test @Order(14)
    void audit_project2_souscriptionAssistance() throws IOException {
        List<EjbInfo> ejbs = parseProject("souscription-assistance-bmcedirect",
                PROJECT2_DIR.resolve("souscription-assistance-bmcedirect"));
        printEjbs("souscription-assistance-bmcedirect", ejbs);
        validateGeneration("souscription-assistance-bmcedirect", ejbs);
    }

    // ===== PROJECT 3 =====

    @Test @Order(15)
    void audit_project3_souscriptionOpv() throws IOException {
        List<EjbInfo> ejbs = parseProject("souscription-opv-bmcedirect",
                PROJECT3_DIR.resolve("souscription-opv-bmcedirect"));
        printEjbs("souscription-opv-bmcedirect", ejbs);
        validateGeneration("souscription-opv-bmcedirect", ejbs);
    }

    @Test @Order(16)
    void audit_project3_tockenisationCarte() throws IOException {
        List<EjbInfo> ejbs = parseProject("tockenisation-carte-bmcedirect",
                PROJECT3_DIR.resolve("tockenisation-carte-bmcedirect"));
        printEjbs("tockenisation-carte-bmcedirect", ejbs);
        validateGeneration("tockenisation-carte-bmcedirect", ejbs);
    }

    @Test @Order(17)
    void audit_project3_transfertEuro() throws IOException {
        List<EjbInfo> ejbs = parseProject("transfert-euro-bmce-direct",
                PROJECT3_DIR.resolve("transfert-euro-bmce-direct"));
        printEjbs("transfert-euro-bmce-direct", ejbs);
        validateGeneration("transfert-euro-bmce-direct", ejbs);
    }

    @Test @Order(18)
    void audit_project3_venteDistanceCarte() throws IOException {
        List<EjbInfo> ejbs = parseProject("vente-distance-carte-monetique",
                PROJECT3_DIR.resolve("vente-distance-carte-monetique"));
        printEjbs("vente-distance-carte-monetique", ejbs);
        validateGeneration("vente-distance-carte-monetique", ejbs);
    }

    @Test @Order(19)
    void audit_project3_virementPermanent() throws IOException {
        List<EjbInfo> ejbs = parseProject("virement-permanent-bmcedirect",
                PROJECT3_DIR.resolve("virement-permanent-bmcedirect"));
        printEjbs("virement-permanent-bmcedirect", ejbs);
        validateGeneration("virement-permanent-bmcedirect", ejbs);
    }

    // ===== SUMMARY =====

    @Test @Order(100)
    void audit_summary() {
        System.out.println("\n\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                    AUDIT SUMMARY                             ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        int totalEjbs = 0;
        int totalMethods = 0;
        int totalWithBody = 0;
        int projectsWithZeroEjbs = 0;

        for (var entry : auditResults.entrySet()) {
            List<EjbInfo> ejbs = entry.getValue();
            int methods = ejbs.stream().mapToInt(e -> e.getMethods().size()).sum();
            int withBody = ejbs.stream()
                    .flatMap(e -> e.getMethods().stream())
                    .mapToInt(m -> m.hasMethodBody() ? 1 : 0)
                    .sum();
            totalEjbs += ejbs.size();
            totalMethods += methods;
            totalWithBody += withBody;
            if (ejbs.isEmpty()) projectsWithZeroEjbs++;

            System.out.printf("║ %-40s | %d EJB | %2d méth | %2d body ║%n",
                    entry.getKey(), ejbs.size(), methods, withBody);
        }
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║ TOTAL: %d projets | %d EJBs | %d méthodes | %d avec corps    ║%n",
                auditResults.size(), totalEjbs, totalMethods, totalWithBody);
        System.out.printf("║ Projets à 0 EJB: %d                                         ║%n", projectsWithZeroEjbs);
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
    }
}
