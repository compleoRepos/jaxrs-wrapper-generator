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

    private static final String WEB_MODULE = "commande-chequier-rest-web";
    private static final String EJB_MODULE = "commande-chequier-rest-ejb";
    private static final String EAR_MODULE = "commande-chequier-rest-ear";

    @Test
    @DisplayName("G\u00e9n\u00e8re la structure multi-modules (parent + ejb + ear + web)")
    void testGeneratesProjectStructure() throws IOException {
        generator.generate(List.of(createTestEjb()), outputDir);

        String pkg = "ma/eai/boa/xbanking";
        // Parent POM
        assertTrue(Files.exists(outputDir.resolve("pom.xml")));
        // EJB module
        assertTrue(Files.exists(outputDir.resolve(EJB_MODULE + "/pom.xml")));
        assertTrue(Files.exists(outputDir.resolve(EJB_MODULE + "/src/main/java/" + pkg + "/service")));
        // EAR module
        assertTrue(Files.exists(outputDir.resolve(EAR_MODULE + "/pom.xml")));
        // Web module
        assertTrue(Files.exists(outputDir.resolve(WEB_MODULE + "/pom.xml")));
        assertTrue(Files.exists(outputDir.resolve(WEB_MODULE + "/src/main/java/" + pkg + "/resource")));
        assertTrue(Files.exists(outputDir.resolve(WEB_MODULE + "/src/main/java/" + pkg + "/dto")));
        assertTrue(Files.exists(outputDir.resolve(WEB_MODULE + "/src/main/java/" + pkg + "/converter")));
        assertTrue(Files.exists(outputDir.resolve(WEB_MODULE + "/src/main/java/" + pkg + "/config")));
        assertTrue(Files.exists(outputDir.resolve(WEB_MODULE + "/src/main/resources/META-INF/beans.xml")));
        // Deployment files
        assertTrue(Files.exists(outputDir.resolve(WEB_MODULE + "/Dockerfile")));
        assertTrue(Files.exists(outputDir.resolve(WEB_MODULE + "/install_app")));
        assertTrue(Files.exists(outputDir.resolve(WEB_MODULE + "/run-local")));
    }

    @Test
    @DisplayName("Parent POM est de type pom avec modules, Web POM est WAR")
    void testPomTargetsJava8() throws IOException {
        generator.generate(List.of(createTestEjb()), outputDir);

        // Parent POM
        String parentPom = Files.readString(outputDir.resolve("pom.xml"));
        assertTrue(parentPom.contains("<source>1.8</source>"), "Parent POM doit cibler Java 1.8");
        assertTrue(parentPom.contains("<packaging>pom</packaging>"), "Parent POM doit \u00eatre pom");
        assertTrue(parentPom.contains("<module>" + EJB_MODULE + "</module>"), "Parent doit lister module EJB");
        assertTrue(parentPom.contains("<module>" + EAR_MODULE + "</module>"), "Parent doit lister module EAR");
        assertTrue(parentPom.contains("<module>" + WEB_MODULE + "</module>"), "Parent doit lister module Web");
        assertTrue(parentPom.contains("javaee-api"), "Parent POM doit contenir javaee-api");
        assertFalse(parentPom.contains("jakarta"), "Parent POM ne doit PAS contenir jakarta");

        // Web POM
        String webPom = Files.readString(outputDir.resolve(WEB_MODULE + "/pom.xml"));
        assertTrue(webPom.contains("<packaging>war</packaging>"), "Web POM doit \u00eatre WAR");
        assertTrue(webPom.contains("eai-commons-services"), "Web POM doit contenir eai-commons-services");
        assertTrue(webPom.contains("eai-midw-connectors"), "Web POM doit contenir eai-midw-connectors");

        // EJB POM
        String ejbPom = Files.readString(outputDir.resolve(EJB_MODULE + "/pom.xml"));
        assertTrue(ejbPom.contains("<packaging>ejb</packaging>"), "EJB POM doit \u00eatre ejb");
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
        Path requestDto = outputDir.resolve(WEB_MODULE + "/src/main/java/" + pkg + "/dto/EnrgCommandeRequest.java");
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
        Path responseDto = outputDir.resolve(WEB_MODULE + "/src/main/java/" + pkg + "/dto/EnrgCommandeResponse.java");
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
        Path errorDto = outputDir.resolve(WEB_MODULE + "/src/main/java/" + pkg + "/dto/ErrorResponse.java");
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
        Path converterFile = outputDir.resolve(WEB_MODULE + "/src/main/java/" + pkg + "/converter/CommandchequierConverter.java");
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
        Path converterFile = outputDir.resolve(WEB_MODULE + "/src/main/java/" + pkg + "/converter/CommandchequierConverter.java");
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
        Path codeMapper = outputDir.resolve(WEB_MODULE + "/src/main/java/" + pkg + "/config/CodeMapper.java");
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

        String webMod = "chequier-rest-api-web";
        assertTrue(Files.exists(outputDir.resolve("pom.xml")));
        assertTrue(Files.exists(outputDir.resolve(webMod + "/src/main/java/com/bank/api/config/JaxRsApplication.java")));
        assertTrue(Files.exists(outputDir.resolve(webMod + "/src/main/java/com/bank/api/config/CodeMapper.java")));
        assertTrue(Files.exists(outputDir.resolve(webMod + "/src/main/resources/META-INF/beans.xml")));

        Path resourceDir = outputDir.resolve(webMod + "/src/main/java/com/bank/api/resource");
        Path converterDir = outputDir.resolve(webMod + "/src/main/java/com/bank/api/converter");
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

    // ===== Tests V2 (Function-Code-Based Generation) =====

    private com.bank.tools.jaxrs.model.SourceProjectMetadata createTestSourceMetadata() {
        com.bank.tools.jaxrs.model.SourceProjectMetadata meta = new com.bank.tools.jaxrs.model.SourceProjectMetadata();
        meta.setEjbGroupId("ma.eai.boa.xbanking");
        meta.setEjbArtifactId("commande-chequier-ejb");
        meta.setEjbVersion("1.0.0");
        com.bank.tools.jaxrs.model.SourceProjectMetadata.JndiBinding binding =
                new com.bank.tools.jaxrs.model.SourceProjectMetadata.JndiBinding();
        binding.setEjbName("CommandChequierService");
        binding.setBindingName("ejb/CommandChequierService");
        meta.setJndiBindings(java.util.Collections.singletonList(binding));
        return meta;
    }

    private EjbInfo createTestEjbWithFunctionCodes() {
        EjbInfo ejb = createTestEjb();

        // Add function codes (V2 path)
        com.bank.tools.jaxrs.model.FunctionCodeInfo fci1 = new com.bank.tools.jaxrs.model.FunctionCodeInfo("enrgCommande", "ENRG_COMMANDE");
        fci1.setDispatchPath("flux/action");
        fci1.setInputFields(Arrays.asList("flux/numAccount", "flux/typeCommand", "flux/nbVignettes", "flux/quantite"));
        fci1.setOutputFields(Arrays.asList("flux/numCommande"));
        fci1.setHttpMethod(MethodInfo.HttpMethod.POST);

        com.bank.tools.jaxrs.model.FunctionCodeInfo fci2 = new com.bank.tools.jaxrs.model.FunctionCodeInfo("suiviCommande", "SUIVI_COMMANDE");
        fci2.setDispatchPath("flux/action");
        fci2.setInputFields(Arrays.asList("flux/numAccount"));
        fci2.setOutputFields(java.util.Collections.emptyList());
        fci2.setHttpMethod(MethodInfo.HttpMethod.GET);

        com.bank.tools.jaxrs.model.FunctionCodeInfo fci3 = new com.bank.tools.jaxrs.model.FunctionCodeInfo("suiviCommande_TST", "SUIVI_COMMANDE_TST");
        fci3.setDispatchPath("flux/action");
        fci3.setInputFields(Arrays.asList("flux/numAccount"));
        fci3.setOutputFields(java.util.Collections.emptyList());
        fci3.setHttpMethod(MethodInfo.HttpMethod.GET);
        fci3.setTestFunction(true);

        ejb.setFunctionCodes(Arrays.asList(fci1, fci2, fci3));
        return ejb;
    }

    @Test
    @DisplayName("V2: Resource utilise JNDI lookup au lieu de @EJB")
    void testV2ResourceUsesJndiLookup() throws IOException {
        EjbInfo ejb = createTestEjbWithFunctionCodes();
        var meta = createTestSourceMetadata();

        generator.generate(List.of(ejb), outputDir, java.util.Collections.emptyMap(), meta);

        String content = readFirstResource();
        assertTrue(content.contains("InitialContext"), "V2 Resource doit utiliser InitialContext");
        assertTrue(content.contains("@PostConstruct"), "V2 Resource doit avoir @PostConstruct");
        assertTrue(content.contains("ejb/CommandChequierService"), "V2 Resource doit utiliser le JNDI name réel");
        assertFalse(content.contains("@EJB"), "V2 Resource ne doit PAS utiliser @EJB");
    }

    @Test
    @DisplayName("V2: Resource génère un endpoint par code fonction")
    void testV2ResourceGeneratesEndpointsPerFunctionCode() throws IOException {
        EjbInfo ejb = createTestEjbWithFunctionCodes();
        var meta = createTestSourceMetadata();

        generator.generate(List.of(ejb), outputDir, java.util.Collections.emptyMap(), meta);

        String content = readFirstResource();
        assertTrue(content.contains("/enrg-commande"), "V2 doit générer endpoint enrg-commande");
        assertTrue(content.contains("/suivi-commande"), "V2 doit générer endpoint suivi-commande");
        assertTrue(content.contains("@POST"), "enrgCommande doit être @POST");
        assertTrue(content.contains("@GET"), "suiviCommande doit être @GET");
    }

    @Test
    @DisplayName("V2: GET avec <= 3 champs utilise @QueryParam")
    void testV2GetUsesQueryParam() throws IOException {
        EjbInfo ejb = createTestEjbWithFunctionCodes();
        var meta = createTestSourceMetadata();

        generator.generate(List.of(ejb), outputDir, java.util.Collections.emptyMap(), meta);

        String content = readFirstResource();
        assertTrue(content.contains("@QueryParam(\"numAccount\")"), "V2 GET doit utiliser @QueryParam");
    }

    @Test
    @DisplayName("V2: Fonctions _TST génèrent des endpoints sous /test/")
    void testV2TestFunctionsUnderTestPath() throws IOException {
        EjbInfo ejb = createTestEjbWithFunctionCodes();
        var meta = createTestSourceMetadata();

        generator.generate(List.of(ejb), outputDir, java.util.Collections.emptyMap(), meta);

        String content = readFirstResource();
        assertTrue(content.contains("/test/suivi-commande"), "V2 _TST doit générer sous /test/");
    }

    @Test
    @DisplayName("V2: Converter utilise le dispatch path réel")
    void testV2ConverterUsesRealDispatchPath() throws IOException {
        EjbInfo ejb = createTestEjbWithFunctionCodes();
        var meta = createTestSourceMetadata();

        generator.generate(List.of(ejb), outputDir, java.util.Collections.emptyMap(), meta);

        String pkg = "ma/eai/boa/xbanking";
        Path converterDir = outputDir.resolve(WEB_MODULE + "/src/main/java/" + pkg + "/converter");
        String content = Files.readString(Files.list(converterDir).findFirst()
                .orElseThrow(() -> new AssertionError("No converter file found")));

        assertTrue(content.contains("\"flux/action\", \"enrgCommande\""), "V2 Converter doit utiliser le dispatch path avec le code fonction");
        assertTrue(content.contains("\"flux/action\", \"suiviCommande\""), "V2 Converter doit utiliser le dispatch path pour suiviCommande");
        assertTrue(content.contains("addNode("), "V2 Converter doit utiliser addNode");
    }

    @Test
    @DisplayName("V2: Pas de module EJB généré quand sourceMetadata est fourni")
    void testV2NoEjbModuleGenerated() throws IOException {
        EjbInfo ejb = createTestEjbWithFunctionCodes();
        var meta = createTestSourceMetadata();

        generator.generate(List.of(ejb), outputDir, java.util.Collections.emptyMap(), meta);

        assertFalse(Files.exists(outputDir.resolve(EJB_MODULE)), "V2 ne doit PAS générer de module EJB");
        assertTrue(Files.exists(outputDir.resolve(WEB_MODULE + "/pom.xml")), "V2 doit générer le module Web");
        assertTrue(Files.exists(outputDir.resolve(EAR_MODULE + "/pom.xml")), "V2 doit générer le module EAR");
    }

    @Test
    @DisplayName("V2: DTOs générés pour chaque code fonction")
    void testV2DtosGeneratedPerFunctionCode() throws IOException {
        EjbInfo ejb = createTestEjbWithFunctionCodes();
        var meta = createTestSourceMetadata();

        generator.generate(List.of(ejb), outputDir, java.util.Collections.emptyMap(), meta);

        String pkg = "ma/eai/boa/xbanking";
        Path dtoDir = outputDir.resolve(WEB_MODULE + "/src/main/java/" + pkg + "/dto");
        assertTrue(Files.exists(dtoDir.resolve("EnrgcommandeRequest.java")), "V2 doit générer EnrgcommandeRequest");
        assertTrue(Files.exists(dtoDir.resolve("EnrgcommandeResponse.java")), "V2 doit générer EnrgcommandeResponse");
        assertTrue(Files.exists(dtoDir.resolve("SuivicommandeRequest.java")), "V2 doit générer SuivicommandeRequest");
        assertTrue(Files.exists(dtoDir.resolve("SuivicommandeResponse.java")), "V2 doit générer SuivicommandeResponse");
    }

    @Test
    @DisplayName("V2: GET avec >3 champs bascule en @POST")
    void testV2GetWithManyFieldsSwitchesToPost() throws IOException {
        EjbInfo ejb = createTestEjb();

        // Create a function code with GET + 5 input fields → should switch to POST
        com.bank.tools.jaxrs.model.FunctionCodeInfo fci = new com.bank.tools.jaxrs.model.FunctionCodeInfo("rechercheAvancee", "RECHERCHE_AVANCEE");
        fci.setDispatchPath("flux/action");
        fci.setInputFields(Arrays.asList("flux/nom", "flux/prenom", "flux/dateNaissance", "flux/ville", "flux/codePostal"));
        fci.setOutputFields(java.util.Collections.emptyList());
        fci.setHttpMethod(MethodInfo.HttpMethod.GET);

        ejb.setFunctionCodes(Arrays.asList(fci));
        var meta = createTestSourceMetadata();

        generator.generate(List.of(ejb), outputDir, java.util.Collections.emptyMap(), meta);

        String content = readFirstResource();
        // Should have switched to @POST because >3 input fields
        assertTrue(content.contains("@POST"), "V2 GET avec >3 champs doit basculer en @POST");
        assertFalse(content.contains("@GET"), "V2 GET avec >3 champs ne doit PAS rester @GET");
        assertTrue(content.contains("RechercheavanceeRequest request"), "V2 doit utiliser un DTO body pour >3 champs");
        assertFalse(content.contains("@QueryParam"), "V2 ne doit PAS utiliser @QueryParam pour >3 champs");
    }

    @Test
    @DisplayName("V2: Pas de signature @author dans Resource, Converter et DTOs générés")
    void testV2NoAuthorSignature() throws IOException {
        EjbInfo ejb = createTestEjbWithFunctionCodes();
        var meta = createTestSourceMetadata();

        generator.generate(List.of(ejb), outputDir, java.util.Collections.emptyMap(), meta);

        String pkg = "ma/eai/boa/xbanking";
        // Check V2-specific files (Resource, Converter, DTOs) for absence of @author
        Path[] v2Dirs = {
            outputDir.resolve(WEB_MODULE + "/src/main/java/" + pkg + "/resource"),
            outputDir.resolve(WEB_MODULE + "/src/main/java/" + pkg + "/converter"),
            outputDir.resolve(WEB_MODULE + "/src/main/java/" + pkg + "/dto")
        };
        for (Path dir : v2Dirs) {
            if (!Files.exists(dir)) continue;
            Files.walk(dir)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(file -> {
                        try {
                            String content = Files.readString(file);
                            assertFalse(content.contains("@author Générateur"),
                                    "V2 ne doit pas contenir @author Générateur dans " + file.getFileName());
                            assertFalse(content.contains("Generated by jaxrs-wrapper"),
                                    "V2 ne doit pas contenir de signature outil dans " + file.getFileName());
                        } catch (IOException e) {
                            fail("Failed to read: " + e.getMessage());
                        }
                    });
        }
    }

    // ===== Utilitaires =====

    private String readFirstResource() throws IOException {
        String pkg = "ma/eai/boa/xbanking";
        Path resourceDir = outputDir.resolve(WEB_MODULE + "/src/main/java/" + pkg + "/resource");
        return Files.readString(Files.list(resourceDir).findFirst()
                .orElseThrow(() -> new AssertionError("No resource file found")));
    }
}
