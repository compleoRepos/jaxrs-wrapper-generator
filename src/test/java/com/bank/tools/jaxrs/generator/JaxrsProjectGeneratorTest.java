package com.bank.tools.jaxrs.generator;

import com.bank.tools.jaxrs.model.EjbInfo;
import com.bank.tools.jaxrs.model.MethodInfo;
import com.bank.tools.jaxrs.model.ParameterInfo;
import com.bank.tools.jaxrs.parser.EjbZipParser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires pour le JaxrsProjectGenerator (mode WAR adaptateur Java EE 7 / WebSphere).
 */
class JaxrsProjectGeneratorTest {

    @TempDir
    Path outputDir;

    private JaxrsProjectGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new JaxrsProjectGenerator(
                "ma.eai.boa.xbanking", "commande-chequier-rest", "ma.eai.boa.xbanking");
    }

    private EjbInfo createTestEjb() {
        EjbInfo ejb = new EjbInfo();
        ejb.setInterfaceName("CommandChequier");
        ejb.setImplementationName("CommandChequierService");
        ejb.setJndiName("CommandChequierService");
        ejb.setPackageName("ma.eai.boa.xbanking.services");
        ejb.setEjbType(EjbInfo.EjbType.STATELESS);

        // Méthode enrgCommande avec corps contenant des getNodeAsString
        MethodInfo enrg = new MethodInfo();
        enrg.setName("enrgCommande");
        enrg.setReturnType("Envelope");
        enrg.setMethodBody(
                "{\n" +
                "    String numAccount = pEnvelopeIn.getNodeAsString(\"flux/numAccount\");\n" +
                "    String typeCommand = pEnvelopeIn.getNodeAsString(\"flux/typeCommand\");\n" +
                "    String nbVignettes = pEnvelopeIn.getNodeAsString(\"flux/nbVignettes\");\n" +
                "    int quantite = pEnvelopeIn.getNodeAsInt(\"flux/quantite\");\n" +
                "    Envelope envelopeOut = new Envelope();\n" +
                "    WriteFlux.ecrireString(envelopeOut, Constants.FLUX, \"code\", \"000\", \"\");\n" +
                "    WriteFlux.ecrireString(envelopeOut, Constants.FLUX, \"message\", \"OK\", \"\");\n" +
                "    WriteFlux.ecrireString(envelopeOut, Constants.FLUX, \"numCommande\", numCmd, \"\");\n" +
                "    return envelopeOut;\n" +
                "}\n");
        ParameterInfo p1 = new ParameterInfo();
        p1.setName("pEnvelopeIn");
        p1.setType("Envelope");
        enrg.setParameters(Arrays.asList(p1));

        // Méthode suiviCommande (GET avec un seul param)
        MethodInfo suivi = new MethodInfo();
        suivi.setName("suiviCommande");
        suivi.setReturnType("Envelope");
        suivi.setMethodBody(
                "{\n" +
                "    String numAccount = pEnvelopeIn.getNodeAsString(\"flux/numAccount\");\n" +
                "    Envelope envelopeOut = new Envelope();\n" +
                "    WriteFlux.ecrireString(envelopeOut, Constants.FLUX, \"code\", \"000\", \"\");\n" +
                "    WriteFlux.ecrireString(envelopeOut, Constants.FLUX, \"message\", \"OK\", \"\");\n" +
                "    return envelopeOut;\n" +
                "}\n");
        ParameterInfo p2 = new ParameterInfo();
        p2.setName("numAccount");
        p2.setType("String");
        suivi.setParameters(Arrays.asList(p2));

        ejb.setMethods(Arrays.asList(enrg, suivi));
        return ejb;
    }

    // ===== Tests de structure =====

    @Test
    @DisplayName("Génère la structure complète du projet WAR adaptateur")
    void testGeneratesProjectStructure() throws IOException {
        generator.generate(List.of(createTestEjb()), outputDir);

        String pkg = "ma/eai/boa/xbanking";
        assertTrue(Files.exists(outputDir.resolve("src/main/java/" + pkg + "/resource")));
        assertTrue(Files.exists(outputDir.resolve("src/main/java/" + pkg + "/dto")));
        assertTrue(Files.exists(outputDir.resolve("src/main/java/" + pkg + "/converter")));
        assertTrue(Files.exists(outputDir.resolve("src/main/java/" + pkg + "/config")));
        assertTrue(Files.exists(outputDir.resolve("src/main/resources/META-INF/beans.xml")));
        assertTrue(Files.exists(outputDir.resolve("pom.xml")));
    }

    @Test
    @DisplayName("POM cible Java 1.8 et utilise javax (Java EE 7)")
    void testPomTargetsJava8() throws IOException {
        generator.generate(List.of(createTestEjb()), outputDir);

        String pom = Files.readString(outputDir.resolve("pom.xml"));
        assertTrue(pom.contains("<source>1.8</source>"), "POM doit cibler Java 1.8");
        assertTrue(pom.contains("<target>1.8</target>"), "POM doit cibler Java 1.8");
        assertTrue(pom.contains("<packaging>war</packaging>"), "POM doit être WAR");
        assertTrue(pom.contains("javaee-api"), "POM doit contenir javaee-api (pas jakarta)");
        assertTrue(pom.contains("eai-commons-services"), "POM doit contenir eai-commons-services");
        assertTrue(pom.contains("eai-midw-connectors"), "POM doit contenir eai-midw-connectors");
        assertFalse(pom.contains("jakarta"), "POM ne doit PAS contenir jakarta");
    }

    // ===== Tests Resource =====

    @Test
    @DisplayName("Resource contient @EJB injection vers SynchroneService")
    void testResourceContainsEjbInjection() throws IOException {
        generator.generate(List.of(createTestEjb()), outputDir);

        String content = readFirstResource();
        assertTrue(content.contains("@EJB"), "Resource doit contenir @EJB");
        assertTrue(content.contains("SynchroneService ejbService"), "Resource doit injecter SynchroneService");
        assertTrue(content.contains("ejbService.process("), "Resource doit appeler ejbService.process()");
    }

    @Test
    @DisplayName("Resource utilise javax.* (pas jakarta.*)")
    void testResourceUsesJavax() throws IOException {
        generator.generate(List.of(createTestEjb()), outputDir);

        String content = readFirstResource();
        assertTrue(content.contains("import javax.ejb.EJB"), "Resource doit importer javax.ejb");
        assertTrue(content.contains("import javax.ws.rs"), "Resource doit importer javax.ws.rs");
        assertFalse(content.contains("jakarta"), "Resource ne doit PAS contenir jakarta");
    }

    @Test
    @DisplayName("Resource contient le mapping codes retour → HTTP status")
    void testResourceContainsCodeMapper() throws IOException {
        generator.generate(List.of(createTestEjb()), outputDir);

        String content = readFirstResource();
        assertTrue(content.contains("CodeMapper.isSuccess(code)"), "Resource doit utiliser CodeMapper");
        assertTrue(content.contains("CodeMapper.toHttpStatus(code)"), "Resource doit mapper le code vers HTTP status");
    }

    @Test
    @DisplayName("Resource est un adaptateur pur — pas de logique métier directe")
    void testResourceIsAdapterOnly() throws IOException {
        generator.generate(List.of(createTestEjb()), outputDir);

        String content = readFirstResource();
        assertFalse(content.contains("DataSource"), "Resource ne doit pas contenir DataSource");
        assertFalse(content.contains("PreparedStatement"), "Resource ne doit pas contenir PreparedStatement");
        assertTrue(content.contains("converter.toEnvelope"), "Resource doit utiliser le converter");
        assertTrue(content.contains("converter.from"), "Resource doit utiliser le converter pour la réponse");
    }

    @Test
    @DisplayName("Méthode GET avec un seul paramètre utilise @QueryParam")
    void testGetMethodUsesQueryParam() throws IOException {
        generator.generate(List.of(createTestEjb()), outputDir);

        String content = readFirstResource();
        assertTrue(content.contains("@QueryParam(\"numAccount\")"),
                "GET avec 1 param doit utiliser @QueryParam");
    }

    // ===== Tests DTOs =====

    @Test
    @DisplayName("DTOs Request ont les noms des champs Envelope")
    void testRequestDtoFieldNames() throws IOException {
        generator.generate(List.of(createTestEjb()), outputDir);

        String pkg = "ma/eai/boa/xbanking";
        Path requestDto = outputDir.resolve("src/main/java/" + pkg + "/dto/EnrgCommandeRequest.java");
        assertTrue(Files.exists(requestDto), "Request DTO must exist");

        String content = Files.readString(requestDto);
        assertTrue(content.contains("numAccount"), "DTO doit contenir numAccount");
        assertTrue(content.contains("typeCommand"), "DTO doit contenir typeCommand");
        assertTrue(content.contains("nbVignettes"), "DTO doit contenir nbVignettes");
        assertTrue(content.contains("int quantite"), "DTO doit contenir quantite comme int");
    }

    @Test
    @DisplayName("DTOs Response ont code, message et champs de sortie")
    void testResponseDtoFieldNames() throws IOException {
        generator.generate(List.of(createTestEjb()), outputDir);

        String pkg = "ma/eai/boa/xbanking";
        Path responseDto = outputDir.resolve("src/main/java/" + pkg + "/dto/EnrgCommandeResponse.java");
        assertTrue(Files.exists(responseDto), "Response DTO must exist");

        String content = Files.readString(responseDto);
        assertTrue(content.contains("code"), "Response DTO doit contenir code");
        assertTrue(content.contains("message"), "Response DTO doit contenir message");
        assertTrue(content.contains("numCommande"), "Response DTO doit contenir numCommande");
    }

    @Test
    @DisplayName("ErrorResponse contient code, message et timestamp")
    void testErrorResponseDto() throws IOException {
        generator.generate(List.of(createTestEjb()), outputDir);

        String pkg = "ma/eai/boa/xbanking";
        Path errorDto = outputDir.resolve("src/main/java/" + pkg + "/dto/ErrorResponse.java");
        assertTrue(Files.exists(errorDto));

        String content = Files.readString(errorDto);
        assertTrue(content.contains("private String code"));
        assertTrue(content.contains("private String message"));
        assertTrue(content.contains("private long timestamp"));
    }

    // ===== Tests Converter =====

    @Test
    @DisplayName("Converter génère toEnvelope et fromEnvelope pour chaque méthode")
    void testConverterGeneration() throws IOException {
        generator.generate(List.of(createTestEjb()), outputDir);

        String pkg = "ma/eai/boa/xbanking";
        Path converterFile = outputDir.resolve("src/main/java/" + pkg + "/converter/CommandchequierConverter.java");
        assertTrue(Files.exists(converterFile), "Converter file must exist");

        String content = Files.readString(converterFile);
        assertTrue(content.contains("toEnvelopeEnrgCommande"), "Converter doit avoir toEnvelopeEnrgCommande");
        assertTrue(content.contains("toEnvelopeSuiviCommande"), "Converter doit avoir toEnvelopeSuiviCommande");
        assertTrue(content.contains("fromEnrgCommandeEnvelope"), "Converter doit avoir fromEnrgCommandeEnvelope");
        assertTrue(content.contains("fromSuiviCommandeEnvelope"), "Converter doit avoir fromSuiviCommandeEnvelope");
    }

    @Test
    @DisplayName("Converter mappe les champs DTO vers les paths Envelope")
    void testConverterMapsFieldsToEnvelopePaths() throws IOException {
        generator.generate(List.of(createTestEjb()), outputDir);

        String pkg = "ma/eai/boa/xbanking";
        Path converterFile = outputDir.resolve("src/main/java/" + pkg + "/converter/CommandchequierConverter.java");
        String content = Files.readString(converterFile);

        assertTrue(content.contains("\"flux/action\""), "Converter doit ajouter flux/action");
        assertTrue(content.contains("\"flux/numAccount\""), "Converter doit mapper flux/numAccount");
        assertTrue(content.contains("\"flux/typeCommand\""), "Converter doit mapper flux/typeCommand");
        assertTrue(content.contains("addNode("), "Converter doit utiliser addNode");
        assertTrue(content.contains("getNodeAsString("), "Converter doit utiliser getNodeAsString");
    }

    // ===== Tests CodeMapper =====

    @Test
    @DisplayName("CodeMapper est généré avec les codes standards")
    void testCodeMapperGeneration() throws IOException {
        generator.generate(List.of(createTestEjb()), outputDir);

        String pkg = "ma/eai/boa/xbanking";
        Path codeMapper = outputDir.resolve("src/main/java/" + pkg + "/config/CodeMapper.java");
        assertTrue(Files.exists(codeMapper));

        String content = Files.readString(codeMapper);
        assertTrue(content.contains("\"000\""), "CodeMapper doit contenir le code 000");
        assertTrue(content.contains("\"003\""), "CodeMapper doit contenir le code 003");
        assertTrue(content.contains("Response.Status.OK"), "CodeMapper doit mapper 000 → OK");
        assertTrue(content.contains("Response.Status.INTERNAL_SERVER_ERROR"), "CodeMapper doit mapper 003 → 500");
        assertTrue(content.contains("Response.Status.CONFLICT"), "CodeMapper doit mapper erreurs métier → 409");
    }

    // ===== Test avec sample-ejb (parsing réel) =====

    @Test
    @DisplayName("Génère un projet valide à partir du sample-ejb de test")
    void testGenerateFromSampleEjb() throws IOException {
        JaxrsProjectGenerator gen = new JaxrsProjectGenerator("com.bank.api", "chequier-rest-api", "com.bank.api");
        EjbZipParser parser = new EjbZipParser();
        List<EjbInfo> ejbs = parser.parseDirectory(Path.of("src/test/resources/sample-ejb"));

        gen.generate(ejbs, outputDir);

        assertTrue(Files.exists(outputDir.resolve("pom.xml")));
        assertTrue(Files.exists(outputDir.resolve("src/main/java/com/bank/api/config/JaxRsApplication.java")));
        assertTrue(Files.exists(outputDir.resolve("src/main/java/com/bank/api/config/CodeMapper.java")));
        assertTrue(Files.exists(outputDir.resolve("src/main/resources/META-INF/beans.xml")));

        Path resourceDir = outputDir.resolve("src/main/java/com/bank/api/resource");
        Path converterDir = outputDir.resolve("src/main/java/com/bank/api/converter");
        assertTrue(Files.exists(resourceDir));
        assertTrue(Files.exists(converterDir));
        assertEquals(ejbs.size(), Files.list(resourceDir).count());
        assertEquals(ejbs.size(), Files.list(converterDir).count());
    }

    @Test
    @DisplayName("Code généré ne contient aucune syntaxe Java 9+")
    void testNoJava9PlusInGeneratedCode() throws IOException {
        generator.generate(List.of(createTestEjb()), outputDir);

        // Vérifier tous les fichiers Java générés
        Files.walk(outputDir)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(file -> {
                    try {
                        String content = Files.readString(file);
                        assertFalse(content.contains("var "), "Pas de 'var' dans " + file.getFileName());
                        assertFalse(content.contains("List.of("), "Pas de List.of() dans " + file.getFileName());
                        assertFalse(content.contains("Map.of("), "Pas de Map.of() dans " + file.getFileName());
                        assertFalse(content.contains("Set.of("), "Pas de Set.of() dans " + file.getFileName());
                        assertFalse(content.contains("jakarta."), "Pas de jakarta dans " + file.getFileName());
                    } catch (IOException e) {
                        fail("Failed to read: " + e.getMessage());
                    }
                });
    }

    // ===== Utilitaires =====

    private String readFirstResource() throws IOException {
        String pkg = "ma/eai/boa/xbanking";
        Path resourceDir = outputDir.resolve("src/main/java/" + pkg + "/resource");
        return Files.readString(Files.list(resourceDir).findFirst()
                .orElseThrow(() -> new AssertionError("No resource file found")));
    }
}
