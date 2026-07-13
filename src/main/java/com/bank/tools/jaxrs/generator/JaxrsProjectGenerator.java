package com.bank.tools.jaxrs.generator;

import com.bank.tools.jaxrs.model.EjbInfo;
import com.bank.tools.jaxrs.model.FunctionCodeInfo;
import com.bank.tools.jaxrs.model.MethodInfo;
import com.bank.tools.jaxrs.model.ParameterInfo;
import com.bank.tools.jaxrs.model.SourceProjectMetadata;
import com.bank.tools.jaxrs.parser.DtoClassParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Génère un module WAR adaptateur JAX-RS à déployer dans le même EAR que l'EJB existant.
 * <p>
 * Le code généré cible <strong>Java 7/8</strong> et <strong>WebSphere</strong> (Java EE 7) :
 * <ul>
 *   <li>Packages javax.* (pas jakarta.*)</li>
 *   <li>JAX-RS 2.0 / EJB 3.2 / JSON-P 1.0</li>
 *   <li>Pas de var, text blocks, List.of(), records</li>
 *   <li>@EJB lookup compatible WebSphere</li>
 * </ul>
 * <p>
 * Architecture :
 * <ul>
 *   <li>Resource JAX-RS : endpoint REST, reçoit le JSON, délègue au service EJB</li>
 *   <li>DTOs : nommés d'après les champs Envelope (getNodeAsString/addNode)</li>
 *   <li>Converter : conversion DTO JSON → Envelope et Envelope → DTO JSON</li>
 *   <li>CodeMapper : mapping des codes retour Envelope → statuts HTTP</li>
 *   <li>@EJB injection : appel du SynchroneService existant</li>
 * </ul>
 */
public class JaxrsProjectGenerator {

    private static final Logger log = LoggerFactory.getLogger(JaxrsProjectGenerator.class);

    private static final Pattern GET_NODE_PATTERN = Pattern.compile(
            "getNodeAs(?:String|Int|Boolean)\\(\"([^\"]+)\"\\)");
    private static final Pattern WRITE_FLUX_FIELD_PATTERN = Pattern.compile(
            "ecrireString\\([^,]+,\\s*[^,]+,\\s*\"([^\"]+)\"");

    private final String basePackage;
    private final String artifactId;
    private final String groupId;

    public JaxrsProjectGenerator(String groupId, String artifactId, String basePackage) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.basePackage = basePackage;
    }

    /**
     * Génère le projet WAR adaptateur complet dans le répertoire de sortie.
     */
    public void generate(List<EjbInfo> ejbs, Path outputDir) throws IOException {
        generate(ejbs, outputDir, Collections.emptyMap());
    }

    /**
     * Génère le projet multi-modules adaptateur complet avec résolution des types DTO.
     * <p>
     * Structure générée :
     * <pre>
     * {outputDir}/
     * ├── pom.xml                         (parent POM, packaging=pom)
     * ├── {artifactId}-ejb/               (module EJB, packaging=ejb)
     * │   ├── pom.xml
     * │   └── src/main/java/...           (interfaces EJB + beans)
     * │   └── src/main/resources/META-INF/ (ejb-jar.xml, ibm-ejb-jar-bnd.xml)
     * ├── {artifactId}-ear/               (module EAR, packaging=ear)
     * │   └── pom.xml                     (dépend de -ejb et -web)
     * └── {artifactId}-web/               (module WAR, packaging=war)
     *     ├── pom.xml
     *     ├── Dockerfile
     *     ├── install_app
     *     ├── run-local
     *     └── src/main/java/...           (Resources REST, DTOs, Converters)
     * </pre>
     *
     * @param ejbs     liste des EJB détectés
     * @param outputDir répertoire de sortie
     * @param classMap  map des classes parsées (nom simple → ParsedClass) pour résoudre les types de retour/paramètres
     */
    public void generate(List<EjbInfo> ejbs, Path outputDir, Map<String, DtoClassParser.ParsedClass> classMap) throws IOException {
        generate(ejbs, outputDir, classMap, null);
    }

    /**
     * Génère le projet adaptateur complet avec métadonnées du projet source.
     * <p>
     * Quand sourceMetadata est fourni :
     * - Le module EJB n'est PAS généré (on référence l'EJB source comme dépendance)
     * - Le parent POM hérite du parent du projet source
     * - Les coordonnées Maven sont celles du projet source
     * - Le lookup JNDI utilise les vrais bindings
     * - Les endpoints sont dérivés des codes fonction (pas des méthodes Java)
     */
    public void generate(List<EjbInfo> ejbs, Path outputDir, Map<String, DtoClassParser.ParsedClass> classMap,
                         SourceProjectMetadata sourceMetadata) throws IOException {
        log.info("Generating JAX-RS adapter project in: {}", outputDir);
        log.info("  groupId={}, artifactId={}, basePackage={}", groupId, artifactId, basePackage);
        log.info("  EJBs to transform: {}", ejbs.size());
        if (sourceMetadata != null) {
            log.info("  Source metadata: {}", sourceMetadata);
        }

        Files.createDirectories(outputDir);

        // === Determine structure based on sourceMetadata ===
        boolean useSourceEjb = sourceMetadata != null && sourceMetadata.hasEjbCoordinates();

        String earModuleName = artifactId + "-ear";
        String webModuleName = artifactId + "-web";

        Path earModuleDir = outputDir.resolve(earModuleName);
        Path webModuleDir = outputDir.resolve(webModuleName);

        // === Web module (WAR) — contains the REST adapter code ===
        Path srcMain = webModuleDir.resolve("src/main/java/" + basePackage.replace('.', '/'));
        Path srcResources = webModuleDir.resolve("src/main/resources");
        Path resourceDir = srcMain.resolve("resource");
        Path dtoDir = srcMain.resolve("dto");
        Path converterDir = srcMain.resolve("converter");
        Path configDir = srcMain.resolve("config");

        Files.createDirectories(resourceDir);
        Files.createDirectories(dtoDir);
        Files.createDirectories(converterDir);
        Files.createDirectories(configDir);
        Files.createDirectories(srcResources);

        // === Generate POMs ===
        if (useSourceEjb) {
            // No generated EJB module — reference the original EJB as dependency
            generateParentPomV2(outputDir, earModuleName, webModuleName, sourceMetadata);
            generateEarModulePomV2(earModuleDir, webModuleName, sourceMetadata);
            generateWebModulePomV2(webModuleDir, sourceMetadata);
        } else {
            // Fallback: generate EJB module stubs (when no source metadata available)
            String ejbModuleName = artifactId + "-ejb";
            Path ejbModuleDir = outputDir.resolve(ejbModuleName);
            generateParentPom(outputDir, ejbModuleName, earModuleName, webModuleName);
            generateEjbModulePom(ejbModuleDir);
            generateEarModulePom(earModuleDir, ejbModuleName, webModuleName);
            generateWebModulePom(webModuleDir, ejbModuleName);
            generateEjbModule(ejbModuleDir, ejbs);
        }

        // === Web module — REST adapter code ===
        generateJaxRsApplication(configDir);
        generateCodeMapper(configDir);

        boolean multiEjb = ejbs.size() > 1;

        for (EjbInfo ejb : ejbs) {
            // Dédupliquer les méthodes surchargées (même nom, params différents)
            deduplicateOverloadedMethods(ejb);

            Map<String, List<EnvelopeField>> inputFieldsByMethod = new LinkedHashMap<>();
            Map<String, List<EnvelopeField>> outputFieldsByMethod = new LinkedHashMap<>();
            extractEnvelopeFields(ejb, inputFieldsByMethod, outputFieldsByMethod);

            // Enrichir les champs avec les vrais noms de propriétés des classes parsées
            if (!classMap.isEmpty()) {
                enrichFieldsFromClassMap(ejb, inputFieldsByMethod, outputFieldsByMethod, classMap);
            }

            // Si multi-EJB, créer un sous-package DTO par EJB pour éviter les collisions
            String ejbDtoSubPackage = multiEjb ? ejb.deriveResourceName().replace("-", "") : null;
            Path ejbDtoDir = dtoDir;
            if (ejbDtoSubPackage != null) {
                ejbDtoDir = dtoDir.resolve(ejbDtoSubPackage);
                Files.createDirectories(ejbDtoDir);
            }

            // V2 path: function-code-based generation when source metadata is available
            boolean useV2 = useSourceEjb && ejb.getFunctionCodes() != null && !ejb.getFunctionCodes().isEmpty();

            if (useV2) {
                // Build field maps keyed by endpoint name (from FunctionCodeInfo)
                Map<String, List<EnvelopeField>> inputFieldsByFc = new LinkedHashMap<>();
                Map<String, List<EnvelopeField>> outputFieldsByFc = new LinkedHashMap<>();
                extractEnvelopeFieldsV2(ejb, inputFieldsByFc, outputFieldsByFc);

                generateResourceV2(resourceDir, ejb, sourceMetadata, inputFieldsByFc, outputFieldsByFc, ejbDtoSubPackage);
                generateDtosV2(ejbDtoDir, ejb, inputFieldsByFc, outputFieldsByFc, ejbDtoSubPackage);
                generateConverterV2(converterDir, ejb, inputFieldsByFc, outputFieldsByFc, ejbDtoSubPackage);
            } else {
                // V1 fallback: method-based generation
                generateResource(resourceDir, ejb, ejbDtoSubPackage);
                generateDtos(ejbDtoDir, ejb, inputFieldsByMethod, outputFieldsByMethod, ejbDtoSubPackage);
                generateConverter(converterDir, ejb, inputFieldsByMethod, outputFieldsByMethod, ejbDtoSubPackage);
            }
        }

        generateErrorResponseDto(dtoDir);
        generateSecurityHeadersFilter(configDir);
        generateRequestLoggingFilter(configDir);
        generateInputSanitizer(configDir);
        generateBeansXml(srcResources);

        // === Deployment files in web module ===
        generateDockerfile(webModuleDir);
        generateInstallApp(webModuleDir);
        generateRunLocal(webModuleDir);

        // === README at project root ===
        generateReadme(outputDir, ejbs);

        log.info("Project generation complete: {}", outputDir);
    }

    // ===== Déduplication des méthodes surchargées =====

    /**
     * Si une interface EJB contient des méthodes surchargées (même nom, params différents),
     * on renomme les doublons avec un suffixe numérique pour éviter les collisions
     * dans les DTOs, Converters et Resources générés.
     */
    private void deduplicateOverloadedMethods(EjbInfo ejb) {
        Map<String, Integer> nameCount = new LinkedHashMap<>();
        for (MethodInfo method : ejb.getMethods()) {
            nameCount.merge(method.getName(), 1, Integer::sum);
        }

        // Renommer les doublons
        Map<String, Integer> currentIndex = new HashMap<>();
        for (MethodInfo method : ejb.getMethods()) {
            String baseName = method.getName();
            if (nameCount.getOrDefault(baseName, 1) > 1) {
                int idx = currentIndex.merge(baseName, 1, Integer::sum);
                if (idx > 1) {
                    method.setName(baseName + idx);
                }
            }
        }
    }

    // ===== Extraction des champs Envelope =====

    public static class EnvelopeField {
        private final String path;
        private final String fieldName;
        private final String type;

        public EnvelopeField(String path, String fieldName, String type) {
            this.path = path;
            this.fieldName = fieldName;
            this.type = type;
        }

        public String getPath() { return path; }
        public String getFieldName() { return fieldName; }
        public String getType() { return type; }
    }

    private void extractEnvelopeFields(EjbInfo ejb,
                                       Map<String, List<EnvelopeField>> inputFields,
                                       Map<String, List<EnvelopeField>> outputFields) {
        for (MethodInfo method : ejb.getMethods()) {
            String body = method.getMethodBody();
            if (body == null || body.isBlank()) {
                inputFields.put(method.getName(), parametersToFields(method));
                List<EnvelopeField> defaultOut = new ArrayList<>();
                defaultOut.add(new EnvelopeField("flux/code", "code", "String"));
                defaultOut.add(new EnvelopeField("flux/message", "message", "String"));
                outputFields.put(method.getName(), defaultOut);
                continue;
            }

            // Déterminer si la méthode reçoit un Envelope en entrée
            // Si oui, les getNodeAsString sont des lectures de l'Envelope d'entrée (= champs input)
            // Si non (params primitifs), les getNodeAsString sont sur la réponse EJB (= champs output)
            boolean hasEnvelopeParam = method.getParameters().stream()
                    .anyMatch(p -> p.getTypeSimple().equals("Envelope"));

            if (hasEnvelopeParam) {
                // Pattern EJB classique : process(Envelope envelopeIn)
                // getNodeAsString = lecture de l'Envelope d'entrée = champs INPUT
                Set<String> seenInput = new LinkedHashSet<>();
                List<EnvelopeField> inFields = new ArrayList<>();
                Matcher m = GET_NODE_PATTERN.matcher(body);
                while (m.find()) {
                    String path = m.group(1);
                    if (path.contains(",") || path.equals("flux/action")) continue;
                    String fieldName = extractFieldName(path);
                    if (seenInput.add(fieldName)) {
                        String type = body.contains("getNodeAsInt(\"" + path + "\")") ? "int" :
                                      body.contains("getNodeAsBoolean(\"" + path + "\")") ? "boolean" : "String";
                        inFields.add(new EnvelopeField(path, fieldName, type));
                    }
                }
                if (inFields.isEmpty()) {
                    inFields = parametersToFields(method);
                }
                inputFields.put(method.getName(), inFields);
            } else {
                // Pattern WebService : getLigneDeclicGAB(String compte, String canal, String reference)
                // Les paramètres de la méthode = champs INPUT
                inputFields.put(method.getName(), parametersToFields(method));
            }

            // Champs de sortie : getNodeAsString sur la réponse + ecrireString
            Set<String> seenOutput = new LinkedHashSet<>();
            List<EnvelopeField> outFields = new ArrayList<>();
            outFields.add(new EnvelopeField("flux/code", "code", "String"));
            outFields.add(new EnvelopeField("flux/message", "message", "String"));
            seenOutput.add("code");
            seenOutput.add("message");

            // Pour les WebServices, les getNodeAsString sont des champs de sortie
            if (!hasEnvelopeParam) {
                Matcher m = GET_NODE_PATTERN.matcher(body);
                while (m.find()) {
                    String path = m.group(1);
                    if (path.contains(",") || path.equals("flux/action")) continue;
                    String fieldName = extractFieldName(path);
                    if (seenOutput.add(fieldName)) {
                        String type = body.contains("getNodeAsInt(\"" + path + "\")") ? "int" :
                                      body.contains("getNodeAsBoolean(\"" + path + "\")") ? "boolean" : "String";
                        outFields.add(new EnvelopeField(path, fieldName, type));
                    }
                }
            }

            Matcher wfM = WRITE_FLUX_FIELD_PATTERN.matcher(body);
            while (wfM.find()) {
                String fieldName = wfM.group(1);
                if (seenOutput.add(fieldName)) {
                    outFields.add(new EnvelopeField("flux/" + fieldName, fieldName, "String"));
                }
            }
            outputFields.put(method.getName(), outFields);
        }
    }

    private List<EnvelopeField> parametersToFields(MethodInfo method) {
        return method.getParameters().stream()
                .map(p -> new EnvelopeField("flux/" + p.getName(), p.getName(), p.getTypeSimple()))
                .collect(Collectors.toList());
    }

    private String extractFieldName(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    // ===== Extraction des champs V2 (depuis FunctionCodeInfo) =====

    /**
     * Construit les maps input/output depuis les FunctionCodeInfo de l'EJB.
     * Les clés sont les endpoint names (deriveEndpointName() sans tirets).
     */
    private void extractEnvelopeFieldsV2(EjbInfo ejb,
                                          Map<String, List<EnvelopeField>> inputFields,
                                          Map<String, List<EnvelopeField>> outputFields) {
        for (FunctionCodeInfo fci : ejb.getFunctionCodes()) {
            String key = fci.deriveEndpointName();

            // Input fields
            List<EnvelopeField> inFields = new ArrayList<>();
            for (String path : fci.getInputFields()) {
                String fieldName = extractFieldName(path);
                // Default to String type; could be refined with type analysis
                inFields.add(new EnvelopeField(path, fieldName, "String"));
            }
            inputFields.put(key, inFields);

            // Output fields — always include code/message, then add specific output fields
            List<EnvelopeField> outFields = new ArrayList<>();
            outFields.add(new EnvelopeField("flux/code", "code", "String"));
            outFields.add(new EnvelopeField("flux/message", "message", "String"));
            Set<String> seen = new LinkedHashSet<>();
            seen.add("code");
            seen.add("message");
            for (String path : fci.getOutputFields()) {
                String fieldName = extractFieldName(path);
                if (seen.add(fieldName)) {
                    outFields.add(new EnvelopeField(path, fieldName, "String"));
                }
            }
            outputFields.put(key, outFields);
        }
    }

    // ===== Enrichissement des champs DTO depuis les classes parsées =====

    /**
     * Enrichit les champs input/output en résolvant les types de retour et de paramètres
     * vers les classes Java parsées dans le projet source.
     *
     * <p>Stratégie :</p>
     * <ul>
     *   <li>Si le type de retour d'une méthode correspond à une classe parsée avec des champs
     *       non-génériques, et que les outputFields actuels ne contiennent que code/message
     *       (fallback par défaut), on les enrichit avec les vrais champs de la classe.</li>
     *   <li>Si un paramètre complexe correspond à une classe parsée, on remplace les inputFields
     *       génériques par les vrais champs de cette classe.</li>
     * </ul>
     */
    private void enrichFieldsFromClassMap(EjbInfo ejb,
                                          Map<String, List<EnvelopeField>> inputFields,
                                          Map<String, List<EnvelopeField>> outputFields,
                                          Map<String, DtoClassParser.ParsedClass> classMap) {
        DtoClassParser dtoParser = new DtoClassParser();

        for (MethodInfo method : ejb.getMethods()) {
            // --- Enrichir les champs de SORTIE depuis le type de retour ---
            String returnType = method.getReturnType();
            if (returnType != null && !"void".equals(returnType) && !"Envelope".equals(returnType)) {
                Optional<DtoClassParser.ParsedClass> resolved = dtoParser.resolveType(returnType, classMap);
                if (resolved.isPresent()) {
                    List<DtoClassParser.ClassField> classFields = resolved.get().getFields();
                    if (!classFields.isEmpty()) {
                        List<EnvelopeField> currentOut = outputFields.get(method.getName());
                        // Ne remplacer que si les champs actuels sont le fallback par défaut (code+message uniquement)
                        if (currentOut != null && isDefaultOutputOnly(currentOut)) {
                            List<EnvelopeField> enriched = new ArrayList<>();
                            enriched.add(new EnvelopeField("flux/code", "code", "String"));
                            enriched.add(new EnvelopeField("flux/message", "message", "String"));
                            for (DtoClassParser.ClassField cf : classFields) {
                                String fieldName = cf.getName();
                                if (!"code".equals(fieldName) && !"message".equals(fieldName)) {
                                    String type = mapClassFieldType(cf);
                                    enriched.add(new EnvelopeField("flux/" + fieldName, fieldName, type));
                                }
                            }
                            outputFields.put(method.getName(), enriched);
                            log.info("  Enriched output fields for {} from class {} ({} fields)",
                                    method.getName(), resolved.get().getSimpleName(), classFields.size());
                        }
                    }
                }
            }

            // --- Enrichir les champs d'ENTRÉE depuis les paramètres complexes ---
            List<EnvelopeField> currentIn = inputFields.get(method.getName());
            if (currentIn != null && method.getParameters().size() == 1) {
                ParameterInfo param = method.getParameters().get(0);
                if (param.isComplexType() && !"Envelope".equals(param.getTypeSimple())) {
                    Optional<DtoClassParser.ParsedClass> resolved = dtoParser.resolveType(param.getType(), classMap);
                    if (resolved.isPresent()) {
                        List<DtoClassParser.ClassField> classFields = resolved.get().getFields();
                        if (!classFields.isEmpty()) {
                            List<EnvelopeField> enriched = new ArrayList<>();
                            for (DtoClassParser.ClassField cf : classFields) {
                                String type = mapClassFieldType(cf);
                                enriched.add(new EnvelopeField("flux/" + cf.getName(), cf.getName(), type));
                            }
                            inputFields.put(method.getName(), enriched);
                            log.info("  Enriched input fields for {} from class {} ({} fields)",
                                    method.getName(), resolved.get().getSimpleName(), classFields.size());
                        }
                    }
                }
            }
        }
    }

    /**
     * Vérifie si les champs de sortie ne contiennent que le fallback par défaut (code + message).
     */
    private boolean isDefaultOutputOnly(List<EnvelopeField> fields) {
        if (fields.size() != 2) return false;
        Set<String> names = fields.stream().map(EnvelopeField::getFieldName).collect(Collectors.toSet());
        return names.contains("code") && names.contains("message");
    }

    /**
     * Mappe un ClassField vers un type Java simple pour le générateur.
     */
    private String mapClassFieldType(DtoClassParser.ClassField cf) {
        String type = cf.getTypeSimple();
        switch (type) {
            case "int": case "Integer": return "int";
            case "long": case "Long": return "long";
            case "double": case "Double": return "double";
            case "float": case "Float": return "float";
            case "boolean": case "Boolean": return "boolean";
            case "Date": case "LocalDate": case "LocalDateTime": return "String";
            default: return "String";
        }
    }

    // ===== POMs (Multi-module: Parent + EJB + EAR + Web) =====

    /**
     * Génère le POM parent (packaging=pom) qui coordonne les 3 modules.
     */
    private void generateParentPom(Path outputDir, String ejbModule, String earModule, String webModule) throws IOException {
        StringBuilder pom = new StringBuilder();
        pom.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        pom.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        pom.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        pom.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        pom.append("    <modelVersion>4.0.0</modelVersion>\n\n");
        pom.append("    <groupId>").append(groupId).append("</groupId>\n");
        pom.append("    <artifactId>").append(artifactId).append("-pom</artifactId>\n");
        pom.append("    <version>1.0.0-SNAPSHOT</version>\n");
        pom.append("    <packaging>pom</packaging>\n\n");
        pom.append("    <modules>\n");
        pom.append("        <module>").append(ejbModule).append("</module>\n");
        pom.append("        <module>").append(webModule).append("</module>\n");
        pom.append("        <module>").append(earModule).append("</module>\n");
        pom.append("    </modules>\n\n");
        pom.append("    <properties>\n");
        pom.append("        <maven.compiler.source>1.8</maven.compiler.source>\n");
        pom.append("        <maven.compiler.target>1.8</maven.compiler.target>\n");
        pom.append("        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n");
        pom.append("        <project.version>1.0.0-SNAPSHOT</project.version>\n");
        pom.append("    </properties>\n\n");
        pom.append("    <dependencyManagement>\n");
        pom.append("        <dependencies>\n");
        pom.append("            <dependency>\n");
        pom.append("                <groupId>javax</groupId>\n");
        pom.append("                <artifactId>javaee-api</artifactId>\n");
        pom.append("                <version>7.0</version>\n");
        pom.append("                <scope>provided</scope>\n");
        pom.append("            </dependency>\n");
        pom.append("            <dependency>\n");
        pom.append("                <groupId>ma.eai.commons</groupId>\n");
        pom.append("                <artifactId>eai-commons-services</artifactId>\n");
        pom.append("                <version>1.0.0</version>\n");
        pom.append("                <scope>provided</scope>\n");
        pom.append("            </dependency>\n");
        pom.append("            <dependency>\n");
        pom.append("                <groupId>ma.eai.midw</groupId>\n");
        pom.append("                <artifactId>eai-midw-connectors</artifactId>\n");
        pom.append("                <version>1.0.0</version>\n");
        pom.append("                <scope>provided</scope>\n");
        pom.append("            </dependency>\n");
        pom.append("            <dependency>\n");
        pom.append("                <groupId>org.slf4j</groupId>\n");
        pom.append("                <artifactId>slf4j-api</artifactId>\n");
        pom.append("                <version>1.7.36</version>\n");
        pom.append("                <scope>provided</scope>\n");
        pom.append("            </dependency>\n");
        pom.append("        </dependencies>\n");
        pom.append("    </dependencyManagement>\n\n");
        pom.append("    <build>\n");
        pom.append("        <plugins>\n");
        pom.append("            <plugin>\n");
        pom.append("                <groupId>org.apache.maven.plugins</groupId>\n");
        pom.append("                <artifactId>maven-compiler-plugin</artifactId>\n");
        pom.append("                <version>3.12.1</version>\n");
        pom.append("                <configuration>\n");
        pom.append("                    <source>1.8</source>\n");
        pom.append("                    <target>1.8</target>\n");
        pom.append("                </configuration>\n");
        pom.append("            </plugin>\n");
        pom.append("        </plugins>\n");
        pom.append("    </build>\n");
        pom.append("</project>\n");
        Files.writeString(outputDir.resolve("pom.xml"), pom.toString());
    }

    /**
     * Génère le POM du module EJB (packaging=ejb).
     */
    private void generateEjbModulePom(Path ejbModuleDir) throws IOException {
        Files.createDirectories(ejbModuleDir);
        String ejbArtifactId = artifactId + "-ejb";
        StringBuilder pom = new StringBuilder();
        pom.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        pom.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        pom.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        pom.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        pom.append("    <modelVersion>4.0.0</modelVersion>\n\n");
        pom.append("    <parent>\n");
        pom.append("        <groupId>").append(groupId).append("</groupId>\n");
        pom.append("        <artifactId>").append(artifactId).append("-pom</artifactId>\n");
        pom.append("        <version>1.0.0-SNAPSHOT</version>\n");
        pom.append("    </parent>\n\n");
        pom.append("    <artifactId>").append(ejbArtifactId).append("</artifactId>\n");
        pom.append("    <packaging>ejb</packaging>\n\n");
        pom.append("    <build>\n");
        pom.append("        <finalName>${project.artifactId}</finalName>\n");
        pom.append("        <plugins>\n");
        pom.append("            <plugin>\n");
        pom.append("                <groupId>org.apache.maven.plugins</groupId>\n");
        pom.append("                <artifactId>maven-ejb-plugin</artifactId>\n");
        pom.append("                <version>3.2.1</version>\n");
        pom.append("                <configuration>\n");
        pom.append("                    <ejbVersion>3.2</ejbVersion>\n");
        pom.append("                </configuration>\n");
        pom.append("            </plugin>\n");
        pom.append("        </plugins>\n");
        pom.append("    </build>\n\n");
        pom.append("    <dependencies>\n");
        pom.append("        <dependency>\n");
        pom.append("            <groupId>javax</groupId>\n");
        pom.append("            <artifactId>javaee-api</artifactId>\n");
        pom.append("        </dependency>\n");
        pom.append("        <dependency>\n");
        pom.append("            <groupId>ma.eai.commons</groupId>\n");
        pom.append("            <artifactId>eai-commons-services</artifactId>\n");
        pom.append("        </dependency>\n");
        pom.append("        <dependency>\n");
        pom.append("            <groupId>ma.eai.midw</groupId>\n");
        pom.append("            <artifactId>eai-midw-connectors</artifactId>\n");
        pom.append("        </dependency>\n");
        pom.append("        <dependency>\n");
        pom.append("            <groupId>org.slf4j</groupId>\n");
        pom.append("            <artifactId>slf4j-api</artifactId>\n");
        pom.append("        </dependency>\n");
        pom.append("    </dependencies>\n");
        pom.append("</project>\n");
        Files.writeString(ejbModuleDir.resolve("pom.xml"), pom.toString());
    }

    /**
     * Génère le POM du module EAR (packaging=ear) qui dépend de -ejb et -web.
     */
    private void generateEarModulePom(Path earModuleDir, String ejbModule, String webModule) throws IOException {
        Files.createDirectories(earModuleDir);
        String earArtifactId = artifactId + "-ear";
        StringBuilder pom = new StringBuilder();
        pom.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        pom.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        pom.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        pom.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        pom.append("    <modelVersion>4.0.0</modelVersion>\n\n");
        pom.append("    <parent>\n");
        pom.append("        <groupId>").append(groupId).append("</groupId>\n");
        pom.append("        <artifactId>").append(artifactId).append("-pom</artifactId>\n");
        pom.append("        <version>1.0.0-SNAPSHOT</version>\n");
        pom.append("    </parent>\n\n");
        pom.append("    <artifactId>").append(earArtifactId).append("</artifactId>\n");
        pom.append("    <packaging>ear</packaging>\n\n");
        pom.append("    <build>\n");
        pom.append("        <finalName>${project.artifactId}</finalName>\n");
        pom.append("        <plugins>\n");
        pom.append("            <plugin>\n");
        pom.append("                <groupId>org.apache.maven.plugins</groupId>\n");
        pom.append("                <artifactId>maven-ear-plugin</artifactId>\n");
        pom.append("                <version>3.3.0</version>\n");
        pom.append("                <configuration>\n");
        pom.append("                    <version>7</version>\n");
        pom.append("                    <defaultLibBundleDir>lib</defaultLibBundleDir>\n");
        pom.append("                    <modules>\n");
        pom.append("                        <ejbModule>\n");
        pom.append("                            <groupId>").append(groupId).append("</groupId>\n");
        pom.append("                            <artifactId>").append(ejbModule).append("</artifactId>\n");
        pom.append("                        </ejbModule>\n");
        pom.append("                        <webModule>\n");
        pom.append("                            <groupId>").append(groupId).append("</groupId>\n");
        pom.append("                            <artifactId>").append(webModule).append("</artifactId>\n");
        pom.append("                            <contextRoot>/api</contextRoot>\n");
        pom.append("                        </webModule>\n");
        pom.append("                    </modules>\n");
        pom.append("                </configuration>\n");
        pom.append("            </plugin>\n");
        pom.append("        </plugins>\n");
        pom.append("    </build>\n\n");
        pom.append("    <dependencies>\n");
        pom.append("        <dependency>\n");
        pom.append("            <groupId>").append(groupId).append("</groupId>\n");
        pom.append("            <artifactId>").append(ejbModule).append("</artifactId>\n");
        pom.append("            <version>${project.version}</version>\n");
        pom.append("            <type>ejb</type>\n");
        pom.append("        </dependency>\n");
        pom.append("        <dependency>\n");
        pom.append("            <groupId>").append(groupId).append("</groupId>\n");
        pom.append("            <artifactId>").append(webModule).append("</artifactId>\n");
        pom.append("            <version>${project.version}</version>\n");
        pom.append("            <type>war</type>\n");
        pom.append("        </dependency>\n");
        pom.append("    </dependencies>\n");
        pom.append("</project>\n");
        Files.writeString(earModuleDir.resolve("pom.xml"), pom.toString());
    }

    /**
     * Génère le POM du module Web/WAR (packaging=war) qui dépend de -ejb.
     */
    private void generateWebModulePom(Path webModuleDir, String ejbModule) throws IOException {
        Files.createDirectories(webModuleDir);
        String webArtifactId = artifactId + "-web";
        StringBuilder pom = new StringBuilder();
        pom.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        pom.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        pom.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        pom.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        pom.append("    <modelVersion>4.0.0</modelVersion>\n\n");
        pom.append("    <parent>\n");
        pom.append("        <groupId>").append(groupId).append("</groupId>\n");
        pom.append("        <artifactId>").append(artifactId).append("-pom</artifactId>\n");
        pom.append("        <version>1.0.0-SNAPSHOT</version>\n");
        pom.append("    </parent>\n\n");
        pom.append("    <artifactId>").append(webArtifactId).append("</artifactId>\n");
        pom.append("    <packaging>war</packaging>\n\n");
        pom.append("    <dependencies>\n");
        pom.append("        <!-- Java EE 7 API (provided by WebSphere) -->\n");
        pom.append("        <dependency>\n");
        pom.append("            <groupId>javax</groupId>\n");
        pom.append("            <artifactId>javaee-api</artifactId>\n");
        pom.append("        </dependency>\n\n");
        pom.append("        <!-- EJB module (for @EJB injection) -->\n");
        pom.append("        <dependency>\n");
        pom.append("            <groupId>").append(groupId).append("</groupId>\n");
        pom.append("            <artifactId>").append(ejbModule).append("</artifactId>\n");
        pom.append("            <version>${project.version}</version>\n");
        pom.append("            <scope>provided</scope>\n");
        pom.append("        </dependency>\n\n");
        pom.append("        <!-- EAI Commons (Envelope, Parser) -->\n");
        pom.append("        <dependency>\n");
        pom.append("            <groupId>ma.eai.commons</groupId>\n");
        pom.append("            <artifactId>eai-commons-services</artifactId>\n");
        pom.append("        </dependency>\n\n");
        pom.append("        <!-- EAI Middleware Connectors (SynchroneService) -->\n");
        pom.append("        <dependency>\n");
        pom.append("            <groupId>ma.eai.midw</groupId>\n");
        pom.append("            <artifactId>eai-midw-connectors</artifactId>\n");
        pom.append("        </dependency>\n\n");
        pom.append("        <!-- SLF4J API -->\n");
        pom.append("        <dependency>\n");
        pom.append("            <groupId>org.slf4j</groupId>\n");
        pom.append("            <artifactId>slf4j-api</artifactId>\n");
        pom.append("        </dependency>\n");
        pom.append("    </dependencies>\n\n");
        pom.append("    <build>\n");
        pom.append("        <finalName>${project.artifactId}</finalName>\n");
        pom.append("        <plugins>\n");
        pom.append("            <plugin>\n");
        pom.append("                <groupId>org.apache.maven.plugins</groupId>\n");
        pom.append("                <artifactId>maven-war-plugin</artifactId>\n");
        pom.append("                <version>3.4.0</version>\n");
        pom.append("                <configuration>\n");
        pom.append("                    <failOnMissingWebXml>false</failOnMissingWebXml>\n");
        pom.append("                </configuration>\n");
        pom.append("            </plugin>\n");
        pom.append("        </plugins>\n");
        pom.append("    </build>\n");
        pom.append("</project>\n");
        Files.writeString(webModuleDir.resolve("pom.xml"), pom.toString());
    }

    // ===== V2 POM Generation (Source EJB reuse) =====

    /**
     * V2: Génère le POM parent avec héritage du parent source et seulement 2 modules (web + ear).
     * Le module EJB n'est PAS généré — on référence l'EJB source comme dépendance Maven.
     */
    private void generateParentPomV2(Path outputDir, String earModule, String webModule, SourceProjectMetadata meta) throws IOException {
        StringBuilder pom = new StringBuilder();
        pom.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        pom.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        pom.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        pom.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        pom.append("    <modelVersion>4.0.0</modelVersion>\n\n");
        // Inherit from source parent POM
        if (meta.hasParentPom()) {
            pom.append("    <parent>\n");
            pom.append("        <groupId>").append(meta.getParentGroupId()).append("</groupId>\n");
            pom.append("        <artifactId>").append(meta.getParentArtifactId()).append("</artifactId>\n");
            pom.append("        <version>").append(meta.getParentVersion()).append("</version>\n");
            pom.append("    </parent>\n\n");
        }
        pom.append("    <groupId>").append(groupId).append("</groupId>\n");
        pom.append("    <artifactId>").append(artifactId).append("-pom</artifactId>\n");
        pom.append("    <version>1.0.0-SNAPSHOT</version>\n");
        pom.append("    <packaging>pom</packaging>\n\n");
        pom.append("    <modules>\n");
        pom.append("        <module>").append(webModule).append("</module>\n");
        pom.append("        <module>").append(earModule).append("</module>\n");
        pom.append("    </modules>\n\n");
        pom.append("    <properties>\n");
        pom.append("        <maven.compiler.source>1.8</maven.compiler.source>\n");
        pom.append("        <maven.compiler.target>1.8</maven.compiler.target>\n");
        pom.append("        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n");
        pom.append("    </properties>\n\n");
        pom.append("    <dependencyManagement>\n");
        pom.append("        <dependencies>\n");
        // Source EJB as dependency
        pom.append("            <dependency>\n");
        pom.append("                <groupId>").append(meta.getEjbGroupId()).append("</groupId>\n");
        pom.append("                <artifactId>").append(meta.getEjbArtifactId()).append("</artifactId>\n");
        String ejbVer = meta.getEjbVersion() != null ? meta.getEjbVersion() : "${project.version}";
        pom.append("                <version>").append(ejbVer).append("</version>\n");
        pom.append("                <scope>provided</scope>\n");
        pom.append("            </dependency>\n");
        // Java EE API
        pom.append("            <dependency>\n");
        pom.append("                <groupId>javax</groupId>\n");
        pom.append("                <artifactId>javaee-api</artifactId>\n");
        pom.append("                <version>7.0</version>\n");
        pom.append("                <scope>provided</scope>\n");
        pom.append("            </dependency>\n");
        // EAI Commons
        pom.append("            <dependency>\n");
        pom.append("                <groupId>ma.eai.commons</groupId>\n");
        pom.append("                <artifactId>eai-commons-services</artifactId>\n");
        pom.append("                <version>1.0.0</version>\n");
        pom.append("                <scope>provided</scope>\n");
        pom.append("            </dependency>\n");
        // EAI Middleware Connectors
        pom.append("            <dependency>\n");
        pom.append("                <groupId>ma.eai.midw</groupId>\n");
        pom.append("                <artifactId>eai-midw-connectors</artifactId>\n");
        pom.append("                <version>1.0.0</version>\n");
        pom.append("                <scope>provided</scope>\n");
        pom.append("            </dependency>\n");
        // SLF4J
        pom.append("            <dependency>\n");
        pom.append("                <groupId>org.slf4j</groupId>\n");
        pom.append("                <artifactId>slf4j-api</artifactId>\n");
        pom.append("                <version>1.7.36</version>\n");
        pom.append("                <scope>provided</scope>\n");
        pom.append("            </dependency>\n");
        pom.append("        </dependencies>\n");
        pom.append("    </dependencyManagement>\n\n");
        pom.append("    <build>\n");
        pom.append("        <plugins>\n");
        pom.append("            <plugin>\n");
        pom.append("                <groupId>org.apache.maven.plugins</groupId>\n");
        pom.append("                <artifactId>maven-compiler-plugin</artifactId>\n");
        pom.append("                <version>3.12.1</version>\n");
        pom.append("                <configuration>\n");
        pom.append("                    <source>1.8</source>\n");
        pom.append("                    <target>1.8</target>\n");
        pom.append("                </configuration>\n");
        pom.append("            </plugin>\n");
        pom.append("        </plugins>\n");
        pom.append("    </build>\n");
        pom.append("</project>\n");
        Files.writeString(outputDir.resolve("pom.xml"), pom.toString());
    }

    /**
     * V2: Génère le POM du module EAR qui référence l'EJB source + le WAR généré.
     */
    private void generateEarModulePomV2(Path earModuleDir, String webModule, SourceProjectMetadata meta) throws IOException {
        Files.createDirectories(earModuleDir);
        String earArtifactId = artifactId + "-ear";
        StringBuilder pom = new StringBuilder();
        pom.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        pom.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        pom.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        pom.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        pom.append("    <modelVersion>4.0.0</modelVersion>\n\n");
        pom.append("    <parent>\n");
        pom.append("        <groupId>").append(groupId).append("</groupId>\n");
        pom.append("        <artifactId>").append(artifactId).append("-pom</artifactId>\n");
        pom.append("        <version>1.0.0-SNAPSHOT</version>\n");
        pom.append("    </parent>\n\n");
        pom.append("    <artifactId>").append(earArtifactId).append("</artifactId>\n");
        pom.append("    <packaging>ear</packaging>\n\n");
        pom.append("    <build>\n");
        pom.append("        <plugins>\n");
        pom.append("            <plugin>\n");
        pom.append("                <groupId>org.apache.maven.plugins</groupId>\n");
        pom.append("                <artifactId>maven-ear-plugin</artifactId>\n");
        pom.append("                <version>3.3.0</version>\n");
        pom.append("                <configuration>\n");
        pom.append("                    <version>7</version>\n");
        pom.append("                    <defaultLibBundleDir>lib</defaultLibBundleDir>\n");
        pom.append("                    <modules>\n");
        // EJB module from source project
        pom.append("                        <ejbModule>\n");
        pom.append("                            <groupId>").append(meta.getEjbGroupId()).append("</groupId>\n");
        pom.append("                            <artifactId>").append(meta.getEjbArtifactId()).append("</artifactId>\n");
        pom.append("                        </ejbModule>\n");
        // Web module (generated)
        pom.append("                        <webModule>\n");
        pom.append("                            <groupId>").append(groupId).append("</groupId>\n");
        pom.append("                            <artifactId>").append(webModule).append("</artifactId>\n");
        pom.append("                            <contextRoot>/").append(artifactId).append("</contextRoot>\n");
        pom.append("                        </webModule>\n");
        pom.append("                    </modules>\n");
        pom.append("                </configuration>\n");
        pom.append("            </plugin>\n");
        pom.append("        </plugins>\n");
        pom.append("    </build>\n\n");
        pom.append("    <dependencies>\n");
        // Source EJB dependency (type=ejb)
        pom.append("        <dependency>\n");
        pom.append("            <groupId>").append(meta.getEjbGroupId()).append("</groupId>\n");
        pom.append("            <artifactId>").append(meta.getEjbArtifactId()).append("</artifactId>\n");
        String ejbVer = meta.getEjbVersion() != null ? meta.getEjbVersion() : "${project.version}";
        pom.append("            <version>").append(ejbVer).append("</version>\n");
        pom.append("            <type>ejb</type>\n");
        pom.append("        </dependency>\n");
        // Generated WAR dependency
        pom.append("        <dependency>\n");
        pom.append("            <groupId>").append(groupId).append("</groupId>\n");
        pom.append("            <artifactId>").append(webModule).append("</artifactId>\n");
        pom.append("            <version>${project.version}</version>\n");
        pom.append("            <type>war</type>\n");
        pom.append("        </dependency>\n");
        pom.append("    </dependencies>\n");
        pom.append("</project>\n");
        Files.writeString(earModuleDir.resolve("pom.xml"), pom.toString());
    }

    /**
     * V2: Génère le POM du module Web/WAR qui dépend de l'EJB source (scope=provided).
     */
    private void generateWebModulePomV2(Path webModuleDir, SourceProjectMetadata meta) throws IOException {
        Files.createDirectories(webModuleDir);
        String webArtifactId = artifactId + "-web";
        StringBuilder pom = new StringBuilder();
        pom.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        pom.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        pom.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        pom.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        pom.append("    <modelVersion>4.0.0</modelVersion>\n\n");
        pom.append("    <parent>\n");
        pom.append("        <groupId>").append(groupId).append("</groupId>\n");
        pom.append("        <artifactId>").append(artifactId).append("-pom</artifactId>\n");
        pom.append("        <version>1.0.0-SNAPSHOT</version>\n");
        pom.append("    </parent>\n\n");
        pom.append("    <artifactId>").append(webArtifactId).append("</artifactId>\n");
        pom.append("    <packaging>war</packaging>\n\n");
        pom.append("    <dependencies>\n");
        // Java EE API (provided by WebSphere)
        pom.append("        <dependency>\n");
        pom.append("            <groupId>javax</groupId>\n");
        pom.append("            <artifactId>javaee-api</artifactId>\n");
        pom.append("        </dependency>\n\n");
        // Source EJB module (provided — deployed in same EAR)
        pom.append("        <dependency>\n");
        pom.append("            <groupId>").append(meta.getEjbGroupId()).append("</groupId>\n");
        pom.append("            <artifactId>").append(meta.getEjbArtifactId()).append("</artifactId>\n");
        pom.append("            <scope>provided</scope>\n");
        pom.append("        </dependency>\n\n");
        // EAI Commons (Envelope, Parser)
        pom.append("        <dependency>\n");
        pom.append("            <groupId>ma.eai.commons</groupId>\n");
        pom.append("            <artifactId>eai-commons-services</artifactId>\n");
        pom.append("        </dependency>\n\n");
        // EAI Middleware Connectors (SynchroneService)
        pom.append("        <dependency>\n");
        pom.append("            <groupId>ma.eai.midw</groupId>\n");
        pom.append("            <artifactId>eai-midw-connectors</artifactId>\n");
        pom.append("        </dependency>\n\n");
        // SLF4J
        pom.append("        <dependency>\n");
        pom.append("            <groupId>org.slf4j</groupId>\n");
        pom.append("            <artifactId>slf4j-api</artifactId>\n");
        pom.append("        </dependency>\n");
        pom.append("    </dependencies>\n\n");
        pom.append("    <build>\n");
        pom.append("        <finalName>${project.artifactId}</finalName>\n");
        pom.append("        <plugins>\n");
        pom.append("            <plugin>\n");
        pom.append("                <groupId>org.apache.maven.plugins</groupId>\n");
        pom.append("                <artifactId>maven-war-plugin</artifactId>\n");
        pom.append("                <version>3.4.0</version>\n");
        pom.append("                <configuration>\n");
        pom.append("                    <failOnMissingWebXml>false</failOnMissingWebXml>\n");
        pom.append("                </configuration>\n");
        pom.append("            </plugin>\n");
        pom.append("        </plugins>\n");
        pom.append("    </build>\n");
        pom.append("</project>\n");
        Files.writeString(webModuleDir.resolve("pom.xml"), pom.toString());
    }

    /**
     * Génère le module EJB avec les interfaces et beans extraits.
     */
    private void generateEjbModule(Path ejbModuleDir, List<EjbInfo> ejbs) throws IOException {
        Path ejbSrc = ejbModuleDir.resolve("src/main/java/" + basePackage.replace('.', '/'));
        Path ejbResources = ejbModuleDir.resolve("src/main/resources/META-INF");
        Files.createDirectories(ejbSrc);
        Files.createDirectories(ejbResources);

        // Generate EJB interface stubs for each detected EJB
        for (EjbInfo ejb : ejbs) {
            Path serviceDir = ejbSrc.resolve("service");
            Files.createDirectories(serviceDir);

            // Generate interface
            StringBuilder iface = new StringBuilder();
            iface.append("package ").append(basePackage).append(".service;\n\n");
            iface.append("import ma.eai.commons.services.parsing.Envelope;\n\n");
            iface.append("/**\n");
            iface.append(" * Interface EJB locale pour ").append(ejb.getInterfaceName()).append(".\n");
            iface.append(" * G\u00e9n\u00e9r\u00e9 automatiquement \u2014 adapter selon le code source original.\n");
            iface.append(" */\n");
            iface.append("public interface ").append(ejb.getInterfaceName()).append(" {\n\n");
            for (MethodInfo method : ejb.getMethods()) {
                iface.append("    Envelope ").append(method.getName()).append("(Envelope envelope);\n\n");
            }
            iface.append("}\n");
            Files.writeString(serviceDir.resolve(ejb.getInterfaceName() + ".java"), iface.toString());

            // Generate implementation stub
            String implName = ejb.getImplementationName() != null ? ejb.getImplementationName() : ejb.getInterfaceName() + "Impl";
            // Avoid cyclic inheritance: if impl name equals interface name, suffix with "Bean"
            if (implName.equals(ejb.getInterfaceName())) {
                implName = implName + "Bean";
            }
            StringBuilder impl = new StringBuilder();
            impl.append("package ").append(basePackage).append(".service;\n\n");
            impl.append("import ma.eai.commons.services.parsing.Envelope;\n");
            impl.append("import javax.ejb.Stateless;\n\n");
            impl.append("/**\n");
            impl.append(" * Impl\u00e9mentation EJB Stateless pour ").append(ejb.getInterfaceName()).append(".\n");
            impl.append(" * G\u00e9n\u00e9r\u00e9 automatiquement \u2014 adapter selon le code source original.\n");
            impl.append(" */\n");
            impl.append("@Stateless\n");
            impl.append("public class ").append(implName).append(" implements ").append(ejb.getInterfaceName()).append(" {\n\n");
            for (MethodInfo method : ejb.getMethods()) {
                impl.append("    @Override\n");
                impl.append("    public Envelope ").append(method.getName()).append("(Envelope envelope) {\n");
                impl.append("        // TODO: Impl\u00e9menter la logique m\u00e9tier\n");
                impl.append("        return envelope;\n");
                impl.append("    }\n\n");
            }
            impl.append("}\n");
            Files.writeString(serviceDir.resolve(implName + ".java"), impl.toString());
        }

        // Generate ejb-jar.xml
        StringBuilder ejbJar = new StringBuilder();
        ejbJar.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        ejbJar.append("<ejb-jar xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\"\n");
        ejbJar.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        ejbJar.append("         xsi:schemaLocation=\"http://xmlns.jcp.org/xml/ns/javaee http://xmlns.jcp.org/xml/ns/javaee/ejb-jar_3_2.xsd\"\n");
        ejbJar.append("         version=\"3.2\">\n");
        ejbJar.append("</ejb-jar>\n");
        Files.writeString(ejbResources.resolve("ejb-jar.xml"), ejbJar.toString());

        // Generate ibm-ejb-jar-bnd.xml (WebSphere binding)
        StringBuilder ibmBnd = new StringBuilder();
        ibmBnd.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        ibmBnd.append("<ejb-jar-bnd xmlns=\"http://websphere.ibm.com/xml/ns/javaee\"\n");
        ibmBnd.append("             xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        ibmBnd.append("             xsi:schemaLocation=\"http://websphere.ibm.com/xml/ns/javaee http://websphere.ibm.com/xml/ns/javaee/ibm-ejb-jar-bnd_1_2.xsd\"\n");
        ibmBnd.append("             version=\"1.2\">\n");
        for (EjbInfo ejb : ejbs) {
            String implName = ejb.getImplementationName() != null ? ejb.getImplementationName() : ejb.getInterfaceName() + "Impl";
            ibmBnd.append("    <session name=\"").append(implName).append("\">\n");
            ibmBnd.append("        <interface class=\"").append(basePackage).append(".service.").append(ejb.getInterfaceName()).append("\"\n");
            ibmBnd.append("                  binding-name=\"ejblocal:").append(basePackage).append(".service.").append(ejb.getInterfaceName()).append("\"/>\n");
            ibmBnd.append("    </session>\n");
        }
        ibmBnd.append("</ejb-jar-bnd>\n");
        Files.writeString(ejbResources.resolve("ibm-ejb-jar-bnd.xml"), ibmBnd.toString());
    }

    // ===== JAX-RS Application =====

    private void generateJaxRsApplication(Path configDir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(".config;\n\n");
        sb.append("import javax.ws.rs.ApplicationPath;\n");
        sb.append("import javax.ws.rs.core.Application;\n\n");
        sb.append("/**\n");
        sb.append(" * Configuration JAX-RS — point d'entrée de l'API REST.\n");
        sb.append(" * Ce module WAR est déployé dans le même EAR que l'EJB sur WebSphere.\n");
        sb.append(" */\n");
        sb.append("@ApplicationPath(\"/api\")\n");
        sb.append("public class JaxRsApplication extends Application {\n");
        sb.append("}\n");

        Files.writeString(configDir.resolve("JaxRsApplication.java"), sb.toString());
    }

    // ===== CodeMapper =====

    private void generateCodeMapper(Path configDir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(".config;\n\n");
        sb.append("import javax.ws.rs.core.Response;\n");
        sb.append("import java.util.HashMap;\n");
        sb.append("import java.util.Map;\n\n");
        sb.append("/**\n");
        sb.append(" * Mapping des codes retour Envelope vers les statuts HTTP.\n");
        sb.append(" * <p>\n");
        sb.append(" * Convention :\n");
        sb.append(" * <ul>\n");
        sb.append(" *   <li>\"000\" = 200 OK (succès)</li>\n");
        sb.append(" *   <li>\"001\", \"002\", \"005\" = 409 Conflict (erreur métier)</li>\n");
        sb.append(" *   <li>\"333\", \"444\" = 400 Bad Request (validation)</li>\n");
        sb.append(" *   <li>\"003\", \"004\", \"009\" = 500 Internal Server Error (technique)</li>\n");
        sb.append(" * </ul>\n");
        sb.append(" */\n");
        sb.append("public final class CodeMapper {\n\n");
        sb.append("    private static final Map<String, Response.Status> CODE_MAP = new HashMap<String, Response.Status>();\n\n");
        sb.append("    static {\n");
        sb.append("        CODE_MAP.put(\"000\", Response.Status.OK);\n");
        sb.append("        CODE_MAP.put(\"001\", Response.Status.CONFLICT);\n");
        sb.append("        CODE_MAP.put(\"002\", Response.Status.CONFLICT);\n");
        sb.append("        CODE_MAP.put(\"003\", Response.Status.INTERNAL_SERVER_ERROR);\n");
        sb.append("        CODE_MAP.put(\"004\", Response.Status.INTERNAL_SERVER_ERROR);\n");
        sb.append("        CODE_MAP.put(\"005\", Response.Status.FORBIDDEN);\n");
        sb.append("        CODE_MAP.put(\"009\", Response.Status.INTERNAL_SERVER_ERROR);\n");
        sb.append("        CODE_MAP.put(\"333\", Response.Status.BAD_REQUEST);\n");
        sb.append("        CODE_MAP.put(\"444\", Response.Status.BAD_REQUEST);\n");
        sb.append("    }\n\n");
        sb.append("    private CodeMapper() {}\n\n");
        sb.append("    /**\n");
        sb.append("     * Mappe un code retour Envelope vers un statut HTTP.\n");
        sb.append("     */\n");
        sb.append("    public static Response.Status toHttpStatus(String code) {\n");
        sb.append("        if (code == null || code.trim().isEmpty()) {\n");
        sb.append("            return Response.Status.INTERNAL_SERVER_ERROR;\n");
        sb.append("        }\n");
        sb.append("        Response.Status status = CODE_MAP.get(code);\n");
        sb.append("        return status != null ? status : Response.Status.INTERNAL_SERVER_ERROR;\n");
        sb.append("    }\n\n");
        sb.append("    /**\n");
        sb.append("     * Vérifie si le code indique un succès.\n");
        sb.append("     */\n");
        sb.append("    public static boolean isSuccess(String code) {\n");
        sb.append("        return \"000\".equals(code);\n");
        sb.append("    }\n");
        sb.append("}\n");

        Files.writeString(configDir.resolve("CodeMapper.java"), sb.toString());
    }

    // ===== Resource (Endpoint JAX-RS — adaptateur pur) =====

    private void generateResource(Path resourceDir, EjbInfo ejb, String ejbDtoSubPackage) throws IOException {
        String resourceName = capitalize(ejb.deriveResourceName().replace("-", "")) + "Resource";
        String converterName = capitalize(ejb.deriveResourceName().replace("-", "")) + "Converter";
        String path = "/" + ejb.deriveResourceName();

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(".resource;\n\n");

        // Imports (javax.* pour Java EE 7 / WebSphere)
        sb.append("import javax.ejb.EJB;\n");
        sb.append("import javax.enterprise.context.ApplicationScoped;\n");
        sb.append("import javax.validation.Valid;\n");
        sb.append("import javax.validation.constraints.NotNull;\n");
        sb.append("import javax.ws.rs.*;\n");
        sb.append("import javax.ws.rs.core.MediaType;\n");
        sb.append("import javax.ws.rs.core.Response;\n");
        sb.append("import org.slf4j.Logger;\n");
        sb.append("import org.slf4j.LoggerFactory;\n");
        sb.append("import ma.eai.commons.services.parsing.Envelope;\n");
        sb.append("import ma.eai.midw.connectors.SynchroneService;\n");
        sb.append("import ").append(basePackage).append(".config.CodeMapper;\n");
        sb.append("import ").append(basePackage).append(".converter.").append(converterName).append(";\n");
        if (ejbDtoSubPackage != null) {
            sb.append("import ").append(basePackage).append(".dto.").append(ejbDtoSubPackage).append(".*;\n");
        } else {
            sb.append("import ").append(basePackage).append(".dto.*;\n");
        }
        sb.append("import ").append(basePackage).append(".dto.ErrorResponse;\n");
        sb.append("\n");

        sb.append("/**\n");
        sb.append(" * Resource JAX-RS — adaptateur REST pour {@link ").append(ejb.getInterfaceName()).append("}.\n");
        sb.append(" *\n");
        sb.append(" * <p>Ce composant est un <b>adaptateur pur</b> (pattern Adapter GoF) qui expose les opérations\n");
        sb.append(" * de l'EJB legacy en tant qu'endpoints REST JSON. Il est déployé dans le même EAR que l'EJB\n");
        sb.append(" * sur WebSphere Application Server.</p>\n");
        sb.append(" *\n");
        sb.append(" * <h3>Flux de traitement :</h3>\n");
        sb.append(" * <ol>\n");
        sb.append(" *   <li>Réception de la requête HTTP (JSON)</li>\n");
        sb.append(" *   <li>Conversion DTO → Envelope via {@link ").append(converterName).append("}</li>\n");
        sb.append(" *   <li>Appel du SynchroneService (EJB) via le bus EAI</li>\n");
        sb.append(" *   <li>Extraction du code retour et mapping HTTP via {@link CodeMapper}</li>\n");
        sb.append(" *   <li>Conversion Envelope → DTO Response</li>\n");
        sb.append(" * </ol>\n");
        sb.append(" *\n");
        sb.append(" * <h3>Sécurité :</h3>\n");
        sb.append(" * <p>L'authentification est gérée par le conteneur WebSphere (LTPA tokens).\n");
        sb.append(" * Les contraintes de sécurité sont définies dans le web.xml du WAR.</p>\n");
        sb.append(" *\n");
        sb.append(" * @author Générateur EJB-to-REST\n");
        sb.append(" * @version 1.0.0\n");
        sb.append(" * @see ").append(converterName).append("\n");
        sb.append(" * @see CodeMapper\n");
        sb.append(" */\n");
        sb.append("@Path(\"").append(path).append("\")\n");
        sb.append("@Produces(MediaType.APPLICATION_JSON)\n");
        sb.append("@Consumes(MediaType.APPLICATION_JSON)\n");
        sb.append("@ApplicationScoped\n");
        sb.append("public class ").append(resourceName).append(" {\n\n");

        // SLF4J Logger
        sb.append("    private static final Logger log = LoggerFactory.getLogger(").append(resourceName).append(".class);\n\n");

        // @EJB injection (WebSphere compatible)
        String ejbName = ejb.getJndiName() != null ? ejb.getJndiName() : ejb.getImplementationName();
        sb.append("    @EJB(lookup = \"java:app/").append(ejbName).append("\")\n");
        sb.append("    private SynchroneService ejbService;\n\n");

        sb.append("    private final ").append(converterName).append(" converter = new ").append(converterName).append("();\n\n");

        for (MethodInfo method : ejb.getMethods()) {
            generateResourceMethod(sb, method, ejb);
        }

        sb.append("}\n");

        Files.writeString(resourceDir.resolve(resourceName + ".java"), sb.toString());
        log.debug("Generated resource: {}", resourceName);
    }

    private void generateResourceMethod(StringBuilder sb, MethodInfo method, EjbInfo ejb) {
        MethodInfo.HttpMethod httpMethod = method.getHttpMethod() != null ? method.getHttpMethod() : method.inferHttpMethod();
        String httpAnnotation = "@" + httpMethod.name();
        String methodPath = "/" + camelToKebab(method.getName());
        String dtoPrefix = capitalize(method.getName());
        String requestDtoName = dtoPrefix + "Request";
        String responseDtoName = dtoPrefix + "Response";

        // Determine HTTP method signature type
        boolean isSimpleGetParam = httpMethod == MethodInfo.HttpMethod.GET
                && method.getParameters().size() <= 1
                && (method.getParameters().isEmpty() || isPrimitiveType(method.getParameters().get(0).getTypeSimple()));

        // JavaDoc per-method
        sb.append("    /**\n");
        sb.append("     * Endpoint REST pour l'opération <b>").append(method.getName()).append("</b>.\n");
        sb.append("     *\n");
        sb.append("     * <p>Cet endpoint appelle l'EJB {@code ").append(ejb.getInterfaceName()).append("} via le bus EAI.\n");
        sb.append("     * Le code retour de l'Envelope détermine le statut HTTP de la réponse :\n");
        sb.append("     * \"000\" = 200 OK, \"001\" = 409 Conflict, \"003\" = 500 Internal Server Error.</p>\n");
        sb.append("     *\n");
        if (isSimpleGetParam) {
            if (!method.getParameters().isEmpty()) {
                ParameterInfo p = method.getParameters().get(0);
                sb.append("     * @param ").append(p.getName()).append(" paramètre de la requête (query string)\n");
            }
        } else {
            sb.append("     * @param request DTO contenant les données d'entrée (validé par Bean Validation)\n");
        }
        sb.append("     * @return {@link Response} contenant le DTO ").append(responseDtoName).append(" ou une {@link ErrorResponse}\n");
        sb.append("     */\n");
        sb.append("    ").append(httpAnnotation).append("\n");
        sb.append("    @Path(\"").append(methodPath).append("\")\n");

        // Signature
        if (isSimpleGetParam) {
            if (!method.getParameters().isEmpty()) {
                ParameterInfo param = method.getParameters().get(0);
                sb.append("    public Response ").append(method.getName())
                        .append("(@QueryParam(\"").append(param.getName()).append("\") ")
                        .append(param.getTypeSimple()).append(" ").append(param.getName()).append(") {\n");
            } else {
                sb.append("    public Response ").append(method.getName()).append("() {\n");
            }
        } else {
            sb.append("    public Response ").append(method.getName())
                    .append("(@Valid @NotNull ").append(requestDtoName).append(" request) {\n");
        }

        // Corps — pattern adaptateur
        sb.append("        log.info(\"[" + method.getName() + "] Appel reçu\");\n");
        sb.append("        try {\n");
        sb.append("            // 1. Convertir le DTO JSON en Envelope XML\n");

        if (isSimpleGetParam) {
            if (!method.getParameters().isEmpty()) {
                ParameterInfo param = method.getParameters().get(0);
                sb.append("            Envelope envelopeIn = converter.toEnvelope").append(dtoPrefix).append("(").append(param.getName()).append(");\n");
            } else {
                sb.append("            Envelope envelopeIn = converter.toEnvelope").append(dtoPrefix).append("();\n");
            }
        } else {
            sb.append("            Envelope envelopeIn = converter.toEnvelope").append(dtoPrefix).append("(request);\n");
        }

        sb.append("\n");
        sb.append("            // 2. Appeler le service EJB\n");
        sb.append("            Envelope envelopeOut = ejbService.process(envelopeIn);\n");
        sb.append("\n");
        // Avoid variable name collision if a param is named 'code'
        boolean hasCodeParam = method.getParameters().stream()
                .anyMatch(p -> "code".equals(p.getName()));
        String codeVar = hasCodeParam ? "responseCode" : "code";
        String msgVar = hasCodeParam ? "responseMessage" : "message";

        sb.append("            // 3. Extraire le code retour et mapper vers HTTP status\n");
        sb.append("            String ").append(codeVar).append(" = envelopeOut.getNodeAsString(\"flux/code\");\n");
        sb.append("            String ").append(msgVar).append(" = envelopeOut.getNodeAsString(\"flux/message\");\n");
        sb.append("\n");
        sb.append("            if (!CodeMapper.isSuccess(").append(codeVar).append(")) {\n");
        sb.append("                return Response.status(CodeMapper.toHttpStatus(").append(codeVar).append("))\n");
        sb.append("                        .entity(new ErrorResponse(").append(codeVar).append(", ").append(msgVar).append("))\n");
        sb.append("                        .build();\n");
        sb.append("            }\n");
        sb.append("\n");
        sb.append("            // 4. Convertir la réponse Envelope en DTO JSON\n");
        sb.append("            ").append(responseDtoName).append(" response = converter.from").append(dtoPrefix).append("Envelope(envelopeOut);\n");
        sb.append("            return Response.ok(response).build();\n");
        sb.append("\n");
        sb.append("        } catch (Exception e) {\n");
        sb.append("            log.error(\"[" + method.getName() + "] Erreur inattendue\", e);\n");
        sb.append("            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)\n");
        sb.append("                    .entity(new ErrorResponse(\"500\", e.getMessage()))\n");
        sb.append("                    .build();\n");
        sb.append("        }\n");

        sb.append("    }\n\n");
    }

    // ===== Resource V2 (Function-Code-Based, JNDI Lookup) =====

    /**
     * Génère la Resource JAX-RS V2 basée sur les codes fonction détectés.
     * <p>
     * Différences avec V1 :
     * <ul>
     *   <li>JNDI lookup via InitialContext au lieu de @EJB</li>
     *   <li>Un endpoint par FunctionCodeInfo (pas par MethodInfo)</li>
     *   <li>@GET avec @QueryParam quand le nombre de champs input est ≤ 3</li>
     *   <li>Fonctions _TST séparées sous /test/</li>
     * </ul>
     */
    private void generateResourceV2(Path resourceDir, EjbInfo ejb,
                                     SourceProjectMetadata sourceMetadata,
                                     Map<String, List<EnvelopeField>> inputFields,
                                     Map<String, List<EnvelopeField>> outputFields,
                                     String ejbDtoSubPackage) throws IOException {
        String baseName = capitalize(ejb.deriveResourceName().replace("-", ""));
        String resourceName = baseName + "Resource";
        String converterName = baseName + "Converter";
        String path = "/" + ejb.deriveResourceName();

        // Resolve JNDI name
        String ejbImplName = ejb.getImplementationName() != null ? ejb.getImplementationName() : ejb.getInterfaceName();
        String jndiName = sourceMetadata != null ? sourceMetadata.getJndiNameForEjb(ejbImplName) : null;
        if (jndiName == null) {
            jndiName = ejb.getJndiName() != null ? ejb.getJndiName() : "ejb/" + ejbImplName;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(".resource;\n\n");

        // Imports (javax.* pour Java EE 7 / WebSphere)
        sb.append("import javax.annotation.PostConstruct;\n");
        sb.append("import javax.enterprise.context.ApplicationScoped;\n");
        sb.append("import javax.naming.InitialContext;\n");
        sb.append("import javax.naming.NamingException;\n");
        sb.append("import javax.validation.Valid;\n");
        sb.append("import javax.validation.constraints.NotNull;\n");
        sb.append("import javax.ws.rs.*;\n");
        sb.append("import javax.ws.rs.core.MediaType;\n");
        sb.append("import javax.ws.rs.core.Response;\n");
        sb.append("import org.slf4j.Logger;\n");
        sb.append("import org.slf4j.LoggerFactory;\n");
        sb.append("import ma.eai.commons.services.parsing.Envelope;\n");
        sb.append("import ma.eai.midw.connectors.SynchroneService;\n");
        sb.append("import ").append(basePackage).append(".config.CodeMapper;\n");
        sb.append("import ").append(basePackage).append(".converter.").append(converterName).append(";\n");
        if (ejbDtoSubPackage != null) {
            sb.append("import ").append(basePackage).append(".dto.").append(ejbDtoSubPackage).append(".*;\n");
        } else {
            sb.append("import ").append(basePackage).append(".dto.*;\n");
        }
        sb.append("import ").append(basePackage).append(".dto.ErrorResponse;\n");
        sb.append("\n");

        sb.append("/**\n");
        sb.append(" * Resource JAX-RS — adaptateur REST pour {@link ").append(ejb.getInterfaceName()).append("}.\n");
        sb.append(" *\n");
        sb.append(" * <p>Ce composant expose les opérations de l'EJB legacy en tant qu'endpoints REST JSON.\n");
        sb.append(" * Il est déployé dans le même EAR que l'EJB sur WebSphere Application Server.</p>\n");
        sb.append(" *\n");
        sb.append(" * <p>L'EJB est résolu par lookup JNDI ({@code ").append(jndiName).append("})\n");
        sb.append(" * au démarrage du conteneur via {@code @PostConstruct}.</p>\n");
        sb.append(" */\n");
        sb.append("@Path(\"").append(path).append("\")\n");
        sb.append("@Produces(MediaType.APPLICATION_JSON)\n");
        sb.append("@Consumes(MediaType.APPLICATION_JSON)\n");
        sb.append("@ApplicationScoped\n");
        sb.append("public class ").append(resourceName).append(" {\n\n");

        // SLF4J Logger
        sb.append("    private static final Logger log = LoggerFactory.getLogger(").append(resourceName).append(".class);\n\n");

        // JNDI lookup field + @PostConstruct
        sb.append("    private SynchroneService ejbService;\n\n");

        sb.append("    private final ").append(converterName).append(" converter = new ").append(converterName).append("();\n\n");

        sb.append("    @PostConstruct\n");
        sb.append("    public void init() {\n");
        sb.append("        try {\n");
        sb.append("            InitialContext ctx = new InitialContext();\n");
        sb.append("            ejbService = (SynchroneService) ctx.lookup(\"").append(jndiName).append("\");\n");
        sb.append("        } catch (NamingException e) {\n");
        sb.append("            throw new RuntimeException(\"EJB lookup failed for JNDI name: ").append(jndiName).append("\", e);\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        // Generate endpoints for each function code
        List<FunctionCodeInfo> functionCodes = ejb.getFunctionCodes();
        for (FunctionCodeInfo fci : functionCodes) {
            if (fci.isTestFunction()) continue; // Handle test functions separately
            generateResourceMethodV2(sb, fci, ejb, inputFields, outputFields);
        }

        // Generate test endpoints under /test/ sub-path
        boolean hasTestFunctions = functionCodes.stream().anyMatch(FunctionCodeInfo::isTestFunction);
        if (hasTestFunctions) {
            sb.append("    // ===== Test Endpoints =====\n\n");
            for (FunctionCodeInfo fci : functionCodes) {
                if (!fci.isTestFunction()) continue;
                generateResourceMethodV2(sb, fci, ejb, inputFields, outputFields);
            }
        }

        sb.append("}\n");

        Files.writeString(resourceDir.resolve(resourceName + ".java"), sb.toString());
        log.debug("Generated V2 resource: {}", resourceName);
    }

    private void generateResourceMethodV2(StringBuilder sb, FunctionCodeInfo fci, EjbInfo ejb,
                                           Map<String, List<EnvelopeField>> inputFields,
                                           Map<String, List<EnvelopeField>> outputFields) {
        MethodInfo.HttpMethod httpMethod = fci.getHttpMethod() != null ? fci.getHttpMethod() : fci.inferHttpMethod();
        String endpointName = fci.deriveEndpointName();
        String methodPath = fci.isTestFunction() ? "/test/" + endpointName : "/" + endpointName;
        String dtoPrefix = capitalize(endpointName.replace("-", ""));
        String requestDtoName = dtoPrefix + "Request";
        String responseDtoName = dtoPrefix + "Response";

        // Determine if this should be a simple GET with @QueryParam
        List<String> fciInputFields = fci.getInputFields();
        boolean isSimpleGet = httpMethod == MethodInfo.HttpMethod.GET && fciInputFields.size() <= 3;

        // If GET but too many params, switch to POST
        if (httpMethod == MethodInfo.HttpMethod.GET && fciInputFields.size() > 3) {
            httpMethod = MethodInfo.HttpMethod.POST;
        }

        String httpAnnotation = "@" + httpMethod.name();

        // JavaDoc
        sb.append("    /**\n");
        sb.append("     * Endpoint REST pour le code fonction <b>").append(fci.getCode()).append("</b>.\n");
        sb.append("     *\n");
        sb.append("     * <p>Appelle l'EJB {@code ").append(ejb.getInterfaceName()).append("} via JNDI.\n");
        sb.append("     * Le code retour de l'Envelope détermine le statut HTTP de la réponse :\n");
        sb.append("     * \"000\" = 200 OK, \"001\" = 409 Conflict, \"003\" = 500 Internal Server Error.</p>\n");
        sb.append("     */\n");
        sb.append("    ").append(httpAnnotation).append("\n");
        sb.append("    @Path(\"").append(methodPath).append("\")\n");

        // Method signature
        String javaMethodName = endpointName.replace("-", "");
        if (fci.isTestFunction()) {
            javaMethodName = javaMethodName + "Test";
        }

        if (isSimpleGet && !fciInputFields.isEmpty()) {
            // @GET with @QueryParam
            sb.append("    public Response ").append(javaMethodName).append("(");
            for (int i = 0; i < fciInputFields.size(); i++) {
                String fieldPath = fciInputFields.get(i);
                String fieldName = extractFieldName(fieldPath);
                if (i > 0) sb.append(", ");
                sb.append("@QueryParam(\"").append(fieldName).append("\") String ").append(fieldName);
            }
            sb.append(") {\n");
        } else if (isSimpleGet && fciInputFields.isEmpty()) {
            // @GET without params
            sb.append("    public Response ").append(javaMethodName).append("() {\n");
        } else {
            // @POST/@PUT/@DELETE with request body
            sb.append("    public Response ").append(javaMethodName)
                    .append("(@Valid @NotNull ").append(requestDtoName).append(" request) {\n");
        }

        // Method body
        sb.append("        log.info(\"[").append(fci.getCode()).append("] Appel reçu\");\n");
        sb.append("        try {\n");
        sb.append("            // 1. Convertir en Envelope\n");

        if (isSimpleGet && !fciInputFields.isEmpty()) {
            sb.append("            Envelope envelopeIn = converter.toEnvelope").append(dtoPrefix).append("(");
            for (int i = 0; i < fciInputFields.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(extractFieldName(fciInputFields.get(i)));
            }
            sb.append(");\n");
        } else if (isSimpleGet && fciInputFields.isEmpty()) {
            sb.append("            Envelope envelopeIn = converter.toEnvelope").append(dtoPrefix).append("();\n");
        } else {
            sb.append("            Envelope envelopeIn = converter.toEnvelope").append(dtoPrefix).append("(request);\n");
        }

        sb.append("\n");
        sb.append("            // 2. Appeler le service EJB\n");
        sb.append("            Envelope envelopeOut = ejbService.process(envelopeIn);\n");
        sb.append("\n");
        sb.append("            // 3. Extraire le code retour et mapper vers HTTP status\n");
        sb.append("            String code = envelopeOut.getNodeAsString(\"flux/code\");\n");
        sb.append("            String message = envelopeOut.getNodeAsString(\"flux/message\");\n");
        sb.append("\n");
        sb.append("            if (!CodeMapper.isSuccess(code)) {\n");
        sb.append("                return Response.status(CodeMapper.toHttpStatus(code))\n");
        sb.append("                        .entity(new ErrorResponse(code, message))\n");
        sb.append("                        .build();\n");
        sb.append("            }\n");
        sb.append("\n");
        sb.append("            // 4. Convertir la réponse Envelope en DTO JSON\n");
        sb.append("            ").append(responseDtoName).append(" response = converter.from").append(dtoPrefix).append("Envelope(envelopeOut);\n");
        sb.append("            return Response.ok(response).build();\n");
        sb.append("\n");
        sb.append("        } catch (Exception e) {\n");
        sb.append("            log.error(\"[").append(fci.getCode()).append("] Erreur inattendue\", e);\n");
        sb.append("            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)\n");
        sb.append("                    .entity(new ErrorResponse(\"500\", e.getMessage()))\n");
        sb.append("                    .build();\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
    }

    // ===== DTOs =====

    private void generateDtos(Path dtoDir, EjbInfo ejb,
                              Map<String, List<EnvelopeField>> inputFields,
                              Map<String, List<EnvelopeField>> outputFields,
                              String ejbDtoSubPackage) throws IOException {
        for (MethodInfo method : ejb.getMethods()) {
            String dtoPrefix = capitalize(method.getName());
            List<EnvelopeField> inFields = inputFields.getOrDefault(method.getName(), Collections.emptyList());
            List<EnvelopeField> outFields = outputFields.getOrDefault(method.getName(), Collections.emptyList());

            generateRequestDto(dtoDir, dtoPrefix, method, inFields, ejbDtoSubPackage);
            generateResponseDto(dtoDir, dtoPrefix, outFields, ejbDtoSubPackage);
        }
    }

    private void generateRequestDto(Path dtoDir, String prefix, MethodInfo method,
                                    List<EnvelopeField> fields, String ejbDtoSubPackage) throws IOException {
        String className = prefix + "Request";
        StringBuilder sb = new StringBuilder();
        if (ejbDtoSubPackage != null) {
            sb.append("package ").append(basePackage).append(".dto.").append(ejbDtoSubPackage).append(";\n\n");
        } else {
            sb.append("package ").append(basePackage).append(".dto;\n\n");
        }
        sb.append("import java.io.Serializable;\n");
        sb.append("import javax.validation.constraints.NotNull;\n\n");
        sb.append("/**\n");
        sb.append(" * DTO Request pour ").append(method.getName()).append(".\n");
        sb.append(" * Champs issus de l'analyse des flux Envelope.\n");
        sb.append(" */\n");
        sb.append("public class ").append(className).append(" implements Serializable {\n\n");
        sb.append("    private static final long serialVersionUID = 1L;\n\n");

        for (EnvelopeField field : fields) {
            String jType = javaType(field.getType());
            // Field-level JavaDoc with path info for developer onboarding
            sb.append("    /** Champ m\u00e9tier mapp\u00e9 depuis Envelope path: ").append(field.getPath()).append(" */\n");
            // Add @NotNull for String fields (object types)
            if ("String".equals(jType)) {
                sb.append("    @NotNull\n");
            }
            sb.append("    private ").append(jType).append(" ").append(field.getFieldName()).append(";\n\n");
        }
        sb.append("\n");

        sb.append("    public ").append(className).append("() {\n");
        sb.append("    }\n\n");

        for (EnvelopeField field : fields) {
            String cap = capitalize(field.getFieldName());
            String jType = javaType(field.getType());
            sb.append("    public ").append(jType).append(" get").append(cap).append("() {\n");
            sb.append("        return ").append(field.getFieldName()).append(";\n");
            sb.append("    }\n\n");
            sb.append("    public void set").append(cap).append("(").append(jType).append(" ").append(field.getFieldName()).append(") {\n");
            sb.append("        this.").append(field.getFieldName()).append(" = ").append(field.getFieldName()).append(";\n");
            sb.append("    }\n\n");
        }

        sb.append("}\n");
        Files.writeString(dtoDir.resolve(className + ".java"), sb.toString());
    }

    private void generateResponseDto(Path dtoDir, String prefix,
                                     List<EnvelopeField> fields, String ejbDtoSubPackage) throws IOException {
        String className = prefix + "Response";
        StringBuilder sb = new StringBuilder();
        if (ejbDtoSubPackage != null) {
            sb.append("package ").append(basePackage).append(".dto.").append(ejbDtoSubPackage).append(";\n\n");
        } else {
            sb.append("package ").append(basePackage).append(".dto;\n\n");
        }
        sb.append("import java.io.Serializable;\n\n");
        sb.append("/**\n");
        sb.append(" * DTO Response pour ").append(prefix).append(".\n");
        sb.append(" * Champs issus de l'analyse des flux Envelope de sortie.\n");
        sb.append(" */\n");
        sb.append("public class ").append(className).append(" implements Serializable {\n\n");
        sb.append("    private static final long serialVersionUID = 1L;\n\n");

                for (EnvelopeField field : fields) {
            sb.append("    /** Champ de r\u00e9ponse extrait depuis Envelope path: ").append(field.getPath()).append(" */\n");
            sb.append("    private ").append(javaType(field.getType())).append(" ").append(field.getFieldName()).append(";\n\n");
        }
        sb.append("\n");
        sb.append("    public ").append(className).append("() {\n");
        sb.append("    }\n\n");
        for (EnvelopeField field : fields) {
            String cap = capitalize(field.getFieldName());
            String jType = javaType(field.getType());
            sb.append("    public ").append(jType).append(" get").append(cap).append("() {\n");
            sb.append("        return ").append(field.getFieldName()).append(";\n");
            sb.append("    }\n\n");
            sb.append("    public void set").append(cap).append("(").append(jType).append(" ").append(field.getFieldName()).append(") {\n");
            sb.append("        this.").append(field.getFieldName()).append(" = ").append(field.getFieldName()).append(";\n");
            sb.append("    }\n\n");
        }
        sb.append("}\n");
        Files.writeString(dtoDir.resolve(className + ".java"), sb.toString());
    }

    private void generateErrorResponseDto(Path dtoDir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(".dto;\n\n");
        sb.append("import java.io.Serializable;\n\n");
        sb.append("/**\n");
        sb.append(" * DTO standard pour les réponses d'erreur.\n");
        sb.append(" */\n");
        sb.append("public class ErrorResponse implements Serializable {\n\n");
        sb.append("    private static final long serialVersionUID = 1L;\n\n");
        sb.append("    private String code;\n");
        sb.append("    private String message;\n");
        sb.append("    private long timestamp;\n\n");
        sb.append("    public ErrorResponse() {\n");
        sb.append("        this.timestamp = System.currentTimeMillis();\n");
        sb.append("    }\n\n");
        sb.append("    public ErrorResponse(String code, String message) {\n");
        sb.append("        this();\n");
        sb.append("        this.code = code;\n");
        sb.append("        this.message = message;\n");
        sb.append("    }\n\n");
        sb.append("    public String getCode() {\n");
        sb.append("        return code;\n");
        sb.append("    }\n\n");
        sb.append("    public void setCode(String code) {\n");
        sb.append("        this.code = code;\n");
        sb.append("    }\n\n");
        sb.append("    public String getMessage() {\n");
        sb.append("        return message;\n");
        sb.append("    }\n\n");
        sb.append("    public void setMessage(String message) {\n");
        sb.append("        this.message = message;\n");
        sb.append("    }\n\n");
        sb.append("    public long getTimestamp() {\n");
        sb.append("        return timestamp;\n");
        sb.append("    }\n\n");
        sb.append("    public void setTimestamp(long timestamp) {\n");
        sb.append("        this.timestamp = timestamp;\n");
        sb.append("    }\n");
        sb.append("}\n");

        Files.writeString(dtoDir.resolve("ErrorResponse.java"), sb.toString());
    }

    // ===== Converter (JSON ↔ Envelope) =====

    private void generateConverter(Path converterDir, EjbInfo ejb,
                                   Map<String, List<EnvelopeField>> inputFields,
                                   Map<String, List<EnvelopeField>> outputFields,
                                   String ejbDtoSubPackage) throws IOException {
        String converterName = capitalize(ejb.deriveResourceName().replace("-", "")) + "Converter";

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(".converter;\n\n");
        sb.append("import ma.eai.commons.services.parsing.Envelope;\n");
        sb.append("import ").append(basePackage).append(".config.InputSanitizer;\n");
        if (ejbDtoSubPackage != null) {
            sb.append("import ").append(basePackage).append(".dto.").append(ejbDtoSubPackage).append(".*;\n\n");
        } else {
            sb.append("import ").append(basePackage).append(".dto.*;\n\n");
        }
        sb.append("/**\n");
        sb.append(" * Converter bidirectionnel JSON DTO <-> Envelope pour ").append(ejb.getInterfaceName()).append(".\n");
        sb.append(" * Assure la conversion :\n");
        sb.append(" *   DTO Request -> Envelope (addNode pour chaque champ)\n");
        sb.append(" *   Envelope -> DTO Response (getNodeAsString pour chaque champ)\n");
        sb.append(" */\n");
        sb.append("public class ").append(converterName).append(" {\n\n");

        for (MethodInfo method : ejb.getMethods()) {
            String dtoPrefix = capitalize(method.getName());
            List<EnvelopeField> inFields = inputFields.getOrDefault(method.getName(), Collections.emptyList());
            List<EnvelopeField> outFields = outputFields.getOrDefault(method.getName(), Collections.emptyList());

            generateToEnvelopeMethod(sb, method, dtoPrefix, inFields);
            generateFromEnvelopeMethod(sb, dtoPrefix, outFields);
        }

        sb.append("}\n");

        Files.writeString(converterDir.resolve(converterName + ".java"), sb.toString());
    }

    private void generateToEnvelopeMethod(StringBuilder sb, MethodInfo method,
                                          String dtoPrefix, List<EnvelopeField> fields) {
        String requestDtoName = dtoPrefix + "Request";
        String actionName = method.getName();
        MethodInfo.HttpMethod httpMethod = method.getHttpMethod() != null ? method.getHttpMethod() : method.inferHttpMethod();

        boolean isSimpleGetParam = httpMethod == MethodInfo.HttpMethod.GET
                && method.getParameters().size() <= 1
                && (method.getParameters().isEmpty() || isPrimitiveType(method.getParameters().get(0).getTypeSimple()));

        if (isSimpleGetParam) {
            if (!method.getParameters().isEmpty()) {
                ParameterInfo param = method.getParameters().get(0);
                sb.append("    /**\n");
                sb.append("     * Convertit le paramètre en Envelope pour l'action '").append(actionName).append("'.\n");
                sb.append("     */\n");
                sb.append("    public Envelope toEnvelope").append(dtoPrefix).append("(")
                        .append(param.getTypeSimple()).append(" ").append(param.getName()).append(") {\n");
                sb.append("        Envelope envelope = new Envelope();\n");
                sb.append("        envelope.addNode(\"flux/action\", \"").append(actionName).append("\");\n");
                sb.append("        envelope.addNode(\"flux/").append(param.getName()).append("\", String.valueOf(").append(param.getName()).append("));\n");
                sb.append("        return envelope;\n");
                sb.append("    }\n\n");
            } else {
                sb.append("    /**\n");
                sb.append("     * Crée un Envelope pour l'action '").append(actionName).append("' (sans paramètres).\n");
                sb.append("     */\n");
                sb.append("    public Envelope toEnvelope").append(dtoPrefix).append("() {\n");
                sb.append("        Envelope envelope = new Envelope();\n");
                sb.append("        envelope.addNode(\"flux/action\", \"").append(actionName).append("\");\n");
                sb.append("        return envelope;\n");
                sb.append("    }\n\n");
            }
        } else {
            sb.append("    /**\n");
            sb.append("     * Convertit le DTO ").append(requestDtoName).append(" en Envelope pour l'action '").append(actionName).append("'.\n");
            sb.append("     */\n");
            sb.append("    public Envelope toEnvelope").append(dtoPrefix).append("(").append(requestDtoName).append(" request) {\n");
            sb.append("        Envelope envelope = new Envelope();\n");
            sb.append("        envelope.addNode(\"flux/action\", \"").append(actionName).append("\");\n");
            for (EnvelopeField field : fields) {
                String getter = "request.get" + capitalize(field.getFieldName()) + "()";
                if ("int".equals(field.getType()) || "boolean".equals(field.getType())
                        || "long".equals(field.getType()) || "double".equals(field.getType())
                        || "float".equals(field.getType()) || "short".equals(field.getType())) {
                    sb.append("        envelope.addNode(\"").append(field.getPath()).append("\", String.valueOf(").append(getter).append("));").append("\n");
                } else {
                    // Apply XSS sanitization on all String inputs before injecting into Envelope
                    sb.append("        envelope.addNode(\"").append(field.getPath()).append("\", InputSanitizer.sanitize(").append(getter).append("));").append("\n");
                }
            }
            sb.append("        return envelope;\n");
            sb.append("    }\n\n");
        }
    }

    private void generateFromEnvelopeMethod(StringBuilder sb, String dtoPrefix,
                                            List<EnvelopeField> fields) {
        String responseDtoName = dtoPrefix + "Response";

        sb.append("    /**\n");
        sb.append("     * Convertit l'Envelope de réponse en DTO ").append(responseDtoName).append(".\n");
        sb.append("     */\n");
        sb.append("    public ").append(responseDtoName).append(" from").append(dtoPrefix).append("Envelope(Envelope envelope) {\n");
        sb.append("        ").append(responseDtoName).append(" response = new ").append(responseDtoName).append("();\n");
        for (EnvelopeField field : fields) {
            String setter = "response.set" + capitalize(field.getFieldName());
            String type = field.getType();
            if ("int".equals(type)) {
                sb.append("        ").append(setter).append("(envelope.getNodeAsInt(\"").append(field.getPath()).append("\"));").append("\n");
            } else if ("long".equals(type)) {
                sb.append("        ").append(setter).append("(envelope.getNodeAsLong(\"").append(field.getPath()).append("\"));").append("\n");
            } else if ("double".equals(type) || "float".equals(type)) {
                sb.append("        ").append(setter).append("(envelope.getNodeAsDouble(\"").append(field.getPath()).append("\"));").append("\n");
            } else if ("boolean".equals(type)) {
                sb.append("        ").append(setter).append("(envelope.getNodeAsBoolean(\"").append(field.getPath()).append("\"));").append("\n");
            } else {
                sb.append("        ").append(setter).append("(envelope.getNodeAsString(\"").append(field.getPath()).append("\"));").append("\n");
            }
        }
        sb.append("        return response;\n");
        sb.append("    }\n\n");
    }

    // ===== Converter V2 (Function-Code-Based Envelope Construction) =====

    /**
     * Génère le Converter V2 basé sur les codes fonction.
     * <p>
     * Différences avec V1 :
     * <ul>
     *   <li>Utilise le dispatch path réel (flux/action ou Flux/FONCTION) au lieu de "flux/action" en dur</li>
     *   <li>Construit l'Envelope avec le code fonction et les champs input spécifiques</li>
     *   <li>Mappe la réponse depuis les outputFields réels du FunctionCodeInfo</li>
     * </ul>
     */
    private void generateConverterV2(Path converterDir, EjbInfo ejb,
                                      Map<String, List<EnvelopeField>> inputFields,
                                      Map<String, List<EnvelopeField>> outputFields,
                                      String ejbDtoSubPackage) throws IOException {
        String converterName = capitalize(ejb.deriveResourceName().replace("-", "")) + "Converter";

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(".converter;\n\n");
        sb.append("import ma.eai.commons.services.parsing.Envelope;\n");
        sb.append("import ").append(basePackage).append(".config.InputSanitizer;\n");
        if (ejbDtoSubPackage != null) {
            sb.append("import ").append(basePackage).append(".dto.").append(ejbDtoSubPackage).append(".*;\n\n");
        } else {
            sb.append("import ").append(basePackage).append(".dto.*;\n\n");
        }
        sb.append("/**\n");
        sb.append(" * Converter bidirectionnel JSON DTO <-> Envelope pour ").append(ejb.getInterfaceName()).append(".\n");
        sb.append(" * G\u00e9n\u00e9r\u00e9 en mode V2 (codes fonction).\n");
        sb.append(" *\n");
        sb.append(" * <p>Chaque m\u00e9thode toEnvelope construit l'Envelope avec le dispatch path\n");
        sb.append(" * et les champs sp\u00e9cifiques au code fonction.</p>\n");
        sb.append(" */\n");
        sb.append("public class ").append(converterName).append(" {\n\n");

        for (FunctionCodeInfo fci : ejb.getFunctionCodes()) {
            String endpointName = fci.deriveEndpointName();
            String dtoPrefix = capitalize(endpointName.replace("-", ""));
            List<EnvelopeField> inFields = inputFields.getOrDefault(endpointName, Collections.emptyList());
            List<EnvelopeField> outFields = outputFields.getOrDefault(endpointName, Collections.emptyList());

            generateToEnvelopeMethodV2(sb, fci, dtoPrefix, inFields);
            generateFromEnvelopeMethodV2(sb, fci, dtoPrefix, outFields);
        }

        sb.append("}\n");

        Files.writeString(converterDir.resolve(converterName + ".java"), sb.toString());
        log.debug("Generated V2 converter: {}", converterName);
    }

    private void generateToEnvelopeMethodV2(StringBuilder sb, FunctionCodeInfo fci,
                                             String dtoPrefix, List<EnvelopeField> fields) {
        String requestDtoName = dtoPrefix + "Request";
        String functionCode = fci.getCode();
        String dispatchPath = fci.getDispatchPath() != null ? fci.getDispatchPath() : "flux/action";

        MethodInfo.HttpMethod httpMethod = fci.getHttpMethod() != null ? fci.getHttpMethod() : fci.inferHttpMethod();
        List<String> fciInputFields = fci.getInputFields();
        boolean isSimpleGet = httpMethod == MethodInfo.HttpMethod.GET && fciInputFields.size() <= 3;

        if (isSimpleGet && !fciInputFields.isEmpty()) {
            // toEnvelope with individual parameters (for @GET @QueryParam)
            sb.append("    /**\n");
            sb.append("     * Construit l'Envelope pour le code fonction '").append(functionCode).append("'.\n");
            sb.append("     */\n");
            sb.append("    public Envelope toEnvelope").append(dtoPrefix).append("(");
            for (int i = 0; i < fciInputFields.size(); i++) {
                String fieldName = extractFieldName(fciInputFields.get(i));
                if (i > 0) sb.append(", ");
                sb.append("String ").append(fieldName);
            }
            sb.append(") {\n");
            sb.append("        Envelope envelope = new Envelope();\n");
            sb.append("        envelope.addNode(\"").append(dispatchPath).append("\", \"").append(functionCode).append("\");\n");
            for (String fieldPath : fciInputFields) {
                String fieldName = extractFieldName(fieldPath);
                sb.append("        envelope.addNode(\"").append(fieldPath).append("\", ").append(fieldName).append(");\n");
            }
            sb.append("        return envelope;\n");
            sb.append("    }\n\n");
        } else if (isSimpleGet && fciInputFields.isEmpty()) {
            // toEnvelope without parameters
            sb.append("    /**\n");
            sb.append("     * Cr\u00e9e un Envelope pour le code fonction '").append(functionCode).append("' (sans param\u00e8tres).\n");
            sb.append("     */\n");
            sb.append("    public Envelope toEnvelope").append(dtoPrefix).append("() {\n");
            sb.append("        Envelope envelope = new Envelope();\n");
            sb.append("        envelope.addNode(\"").append(dispatchPath).append("\", \"").append(functionCode).append("\");\n");
            sb.append("        return envelope;\n");
            sb.append("    }\n\n");
        } else {
            // toEnvelope with DTO (for @POST/@PUT/@DELETE or GET with > 3 params)
            sb.append("    /**\n");
            sb.append("     * Convertit le DTO ").append(requestDtoName).append(" en Envelope pour le code fonction '").append(functionCode).append("'.\n");
            sb.append("     */\n");
            sb.append("    public Envelope toEnvelope").append(dtoPrefix).append("(").append(requestDtoName).append(" request) {\n");
            sb.append("        Envelope envelope = new Envelope();\n");
            sb.append("        envelope.addNode(\"").append(dispatchPath).append("\", \"").append(functionCode).append("\");\n");
            for (EnvelopeField field : fields) {
                String getter = "request.get" + capitalize(field.getFieldName()) + "()";
                if ("int".equals(field.getType()) || "boolean".equals(field.getType())
                        || "long".equals(field.getType()) || "double".equals(field.getType())
                        || "float".equals(field.getType()) || "short".equals(field.getType())) {
                    sb.append("        envelope.addNode(\"").append(field.getPath()).append("\", String.valueOf(").append(getter).append("));\n");
                } else {
                    sb.append("        envelope.addNode(\"").append(field.getPath()).append("\", InputSanitizer.sanitize(").append(getter).append("));\n");
                }
            }
            sb.append("        return envelope;\n");
            sb.append("    }\n\n");
        }
    }

    private void generateFromEnvelopeMethodV2(StringBuilder sb, FunctionCodeInfo fci,
                                               String dtoPrefix, List<EnvelopeField> fields) {
        String responseDtoName = dtoPrefix + "Response";

        sb.append("    /**\n");
        sb.append("     * Convertit l'Envelope de r\u00e9ponse en DTO ").append(responseDtoName).append(".\n");
        sb.append("     */\n");
        sb.append("    public ").append(responseDtoName).append(" from").append(dtoPrefix).append("Envelope(Envelope envelope) {\n");
        sb.append("        ").append(responseDtoName).append(" response = new ").append(responseDtoName).append("();\n");

        for (EnvelopeField field : fields) {
            String setter = "response.set" + capitalize(field.getFieldName());
            String type = field.getType();
            if ("int".equals(type)) {
                sb.append("        ").append(setter).append("(envelope.getNodeAsInt(\"").append(field.getPath()).append("\"));\n");
            } else if ("long".equals(type)) {
                sb.append("        ").append(setter).append("(envelope.getNodeAsLong(\"").append(field.getPath()).append("\"));\n");
            } else if ("double".equals(type) || "float".equals(type)) {
                sb.append("        ").append(setter).append("(envelope.getNodeAsDouble(\"").append(field.getPath()).append("\"));\n");
            } else if ("boolean".equals(type)) {
                sb.append("        ").append(setter).append("(envelope.getNodeAsBoolean(\"").append(field.getPath()).append("\"));\n");
            } else {
                sb.append("        ").append(setter).append("(envelope.getNodeAsString(\"").append(field.getPath()).append("\"));\n");
            }
        }

        sb.append("        return response;\n");
        sb.append("    }\n\n");
    }

    // ===== DTOs V2 (Function-Code-Based) =====

    /**
     * Génère les DTOs V2 basés sur les codes fonction.
     * Un Request DTO et un Response DTO par FunctionCodeInfo.
     */
    private void generateDtosV2(Path dtoDir, EjbInfo ejb,
                                 Map<String, List<EnvelopeField>> inputFields,
                                 Map<String, List<EnvelopeField>> outputFields,
                                 String ejbDtoSubPackage) throws IOException {
        for (FunctionCodeInfo fci : ejb.getFunctionCodes()) {
            String endpointName = fci.deriveEndpointName();
            String dtoPrefix = capitalize(endpointName.replace("-", ""));
            List<EnvelopeField> inFields = inputFields.getOrDefault(endpointName, Collections.emptyList());
            List<EnvelopeField> outFields = outputFields.getOrDefault(endpointName, Collections.emptyList());

            generateRequestDtoV2(dtoDir, dtoPrefix, fci, inFields, ejbDtoSubPackage);
            generateResponseDto(dtoDir, dtoPrefix, outFields, ejbDtoSubPackage);
        }
    }

    private void generateRequestDtoV2(Path dtoDir, String prefix, FunctionCodeInfo fci,
                                       List<EnvelopeField> fields, String ejbDtoSubPackage) throws IOException {
        String className = prefix + "Request";
        StringBuilder sb = new StringBuilder();
        if (ejbDtoSubPackage != null) {
            sb.append("package ").append(basePackage).append(".dto.").append(ejbDtoSubPackage).append(";\n\n");
        } else {
            sb.append("package ").append(basePackage).append(".dto;\n\n");
        }
        sb.append("import java.io.Serializable;\n");
        sb.append("import javax.validation.constraints.NotNull;\n\n");
        sb.append("/**\n");
        sb.append(" * DTO Request pour le code fonction '").append(fci.getCode()).append("'.\n");
        sb.append(" */\n");
        sb.append("public class ").append(className).append(" implements Serializable {\n\n");
        sb.append("    private static final long serialVersionUID = 1L;\n\n");

        for (EnvelopeField field : fields) {
            String jType = javaType(field.getType());
            sb.append("    /** Champ m\u00e9tier mapp\u00e9 depuis Envelope path: ").append(field.getPath()).append(" */\n");
            if ("String".equals(jType)) {
                sb.append("    @NotNull\n");
            }
            sb.append("    private ").append(jType).append(" ").append(field.getFieldName()).append(";\n\n");
        }
        sb.append("\n");

        sb.append("    public ").append(className).append("() {\n");
        sb.append("    }\n\n");

        for (EnvelopeField field : fields) {
            String cap = capitalize(field.getFieldName());
            String jType = javaType(field.getType());
            sb.append("    public ").append(jType).append(" get").append(cap).append("() {\n");
            sb.append("        return ").append(field.getFieldName()).append(";\n");
            sb.append("    }\n\n");
            sb.append("    public void set").append(cap).append("(").append(jType).append(" ").append(field.getFieldName()).append(") {\n");
            sb.append("        this.").append(field.getFieldName()).append(" = ").append(field.getFieldName()).append(";\n");
            sb.append("    }\n\n");
        }

        sb.append("}\n");
        Files.writeString(dtoDir.resolve(className + ".java"), sb.toString());
    }

    // ===== Security Headers Filter =====

    /**
     * Génère un filtre JAX-RS qui ajoute les headers de sécurité HTTP
     * sur toutes les réponses (X-Content-Type-Options, X-Frame-Options, etc.).
     */
    private void generateSecurityHeadersFilter(Path configDir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(".config;\n\n");
        sb.append("import javax.ws.rs.container.ContainerRequestContext;\n");
        sb.append("import javax.ws.rs.container.ContainerResponseContext;\n");
        sb.append("import javax.ws.rs.container.ContainerResponseFilter;\n");
        sb.append("import javax.ws.rs.ext.Provider;\n");
        sb.append("import java.io.IOException;\n\n");
        sb.append("/**\n");
        sb.append(" * Filtre de sécurité HTTP qui ajoute les headers de protection sur chaque réponse.\n");
        sb.append(" *\n");
        sb.append(" * <p>Headers ajoutés :</p>\n");
        sb.append(" * <ul>\n");
        sb.append(" *   <li><b>X-Content-Type-Options: nosniff</b> — empêche le MIME-sniffing</li>\n");
        sb.append(" *   <li><b>X-Frame-Options: DENY</b> — protège contre le clickjacking</li>\n");
        sb.append(" *   <li><b>X-XSS-Protection: 1; mode=block</b> — active le filtre XSS du navigateur</li>\n");
        sb.append(" *   <li><b>Cache-Control: no-store</b> — empêche la mise en cache de données sensibles</li>\n");
        sb.append(" *   <li><b>Strict-Transport-Security</b> — force HTTPS (HSTS)</li>\n");
        sb.append(" *   <li><b>Content-Security-Policy</b> — restreint les sources de contenu</li>\n");
        sb.append(" * </ul>\n");
        sb.append(" *\n");
        sb.append(" * @author Générateur EJB-to-REST\n");
        sb.append(" * @version 1.0.0\n");
        sb.append(" */\n");
        sb.append("@Provider\n");
        sb.append("public class SecurityHeadersFilter implements ContainerResponseFilter {\n\n");
        sb.append("    @Override\n");
        sb.append("    public void filter(ContainerRequestContext requestContext,\n");
        sb.append("                       ContainerResponseContext responseContext) throws IOException {\n");
        sb.append("        responseContext.getHeaders().putSingle(\"X-Content-Type-Options\", \"nosniff\");\n");
        sb.append("        responseContext.getHeaders().putSingle(\"X-Frame-Options\", \"DENY\");\n");
        sb.append("        responseContext.getHeaders().putSingle(\"X-XSS-Protection\", \"1; mode=block\");\n");
        sb.append("        responseContext.getHeaders().putSingle(\"Cache-Control\", \"no-store, no-cache, must-revalidate\");\n");
        sb.append("        responseContext.getHeaders().putSingle(\"Pragma\", \"no-cache\");\n");
        sb.append("        responseContext.getHeaders().putSingle(\"Strict-Transport-Security\", \"max-age=31536000; includeSubDomains\");\n");
        sb.append("        responseContext.getHeaders().putSingle(\"Content-Security-Policy\", \"default-src 'none'; frame-ancestors 'none'\");\n");
        sb.append("        responseContext.getHeaders().putSingle(\"Referrer-Policy\", \"strict-origin-when-cross-origin\");\n");
        sb.append("    }\n");
        sb.append("}\n");
        Files.writeString(configDir.resolve("SecurityHeadersFilter.java"), sb.toString());
    }

    // ===== Request Logging Filter =====

    /**
     * Génère un filtre JAX-RS pour tracer les requêtes entrantes (méthode, URI, durée).
     */
    private void generateRequestLoggingFilter(Path configDir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(".config;\n\n");
        sb.append("import javax.ws.rs.container.ContainerRequestContext;\n");
        sb.append("import javax.ws.rs.container.ContainerRequestFilter;\n");
        sb.append("import javax.ws.rs.container.ContainerResponseContext;\n");
        sb.append("import javax.ws.rs.container.ContainerResponseFilter;\n");
        sb.append("import javax.ws.rs.ext.Provider;\n");
        sb.append("import org.slf4j.Logger;\n");
        sb.append("import org.slf4j.LoggerFactory;\n");
        sb.append("import java.io.IOException;\n\n");
        sb.append("/**\n");
        sb.append(" * Filtre de journalisation des requêtes HTTP entrantes et sortantes.\n");
        sb.append(" *\n");
        sb.append(" * <p>Enregistre pour chaque requête :</p>\n");
        sb.append(" * <ul>\n");
        sb.append(" *   <li>Méthode HTTP et URI</li>\n");
        sb.append(" *   <li>Durée de traitement (ms)</li>\n");
        sb.append(" *   <li>Code de statut HTTP retourné</li>\n");
        sb.append(" * </ul>\n");
        sb.append(" *\n");
        sb.append(" * @author Générateur EJB-to-REST\n");
        sb.append(" * @version 1.0.0\n");
        sb.append(" */\n");
        sb.append("@Provider\n");
        sb.append("public class RequestLoggingFilter implements ContainerRequestFilter, ContainerResponseFilter {\n\n");
        sb.append("    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);\n");
        sb.append("    private static final String START_TIME = \"request-start-time\";\n\n");
        sb.append("    @Override\n");
        sb.append("    public void filter(ContainerRequestContext requestContext) throws IOException {\n");
        sb.append("        requestContext.setProperty(START_TIME, System.currentTimeMillis());\n");
        sb.append("        log.info(\"[REQ] {} {}\", requestContext.getMethod(), requestContext.getUriInfo().getRequestUri());\n");
        sb.append("    }\n\n");
        sb.append("    @Override\n");
        sb.append("    public void filter(ContainerRequestContext requestContext,\n");
        sb.append("                       ContainerResponseContext responseContext) throws IOException {\n");
        sb.append("        Long startTime = (Long) requestContext.getProperty(START_TIME);\n");
        sb.append("        long duration = startTime != null ? System.currentTimeMillis() - startTime : -1;\n");
        sb.append("        log.info(\"[RES] {} {} → {} ({}ms)\",\n");
        sb.append("                requestContext.getMethod(),\n");
        sb.append("                requestContext.getUriInfo().getRequestUri(),\n");
        sb.append("                responseContext.getStatus(),\n");
        sb.append("                duration);\n");
        sb.append("    }\n");
        sb.append("}\n");
        Files.writeString(configDir.resolve("RequestLoggingFilter.java"), sb.toString());
    }

    // ===== Input Sanitizer =====

    /**
     * Génère un utilitaire de sanitization des entrées pour prévenir les injections XSS.
     */
    private void generateInputSanitizer(Path configDir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(".config;\n\n");
        sb.append("/**\n");
        sb.append(" * Utilitaire de sanitization des entrées utilisateur.\n");
        sb.append(" *\n");
        sb.append(" * <p>Fournit des méthodes statiques pour nettoyer les chaînes de caractères\n");
        sb.append(" * avant de les passer au bus EAI, prévenant ainsi les attaques XSS\n");
        sb.append(" * et les injections dans les Envelopes XML.</p>\n");
        sb.append(" *\n");
        sb.append(" * <h3>Utilisation dans les Converters :</h3>\n");
        sb.append(" * <pre>\n");
        sb.append(" * envelope.addNode(\"flux/nom\", InputSanitizer.sanitize(request.getNom()));\n");
        sb.append(" * </pre>\n");
        sb.append(" *\n");
        sb.append(" * @author Générateur EJB-to-REST\n");
        sb.append(" * @version 1.0.0\n");
        sb.append(" */\n");
        sb.append("public final class InputSanitizer {\n\n");
        sb.append("    private InputSanitizer() {}\n\n");
        sb.append("    /**\n");
        sb.append("     * Nettoie une chaîne en échappant les caractères HTML dangereux.\n");
        sb.append("     *\n");
        sb.append("     * @param input la chaîne brute (peut être null)\n");
        sb.append("     * @return la chaîne sanitizée, ou null si l'entrée est null\n");
        sb.append("     */\n");
        sb.append("    public static String sanitize(String input) {\n");
        sb.append("        if (input == null) return null;\n");
        sb.append("        return input\n");
        sb.append("                .replace(\"&\", \"&amp;\")\n");
        sb.append("                .replace(\"<\", \"&lt;\")\n");
        sb.append("                .replace(\">\", \"&gt;\")\n");
        sb.append("                .replace(\"\\\"\", \"&quot;\")\n");
        sb.append("                .replace(\"'\", \"&#x27;\")\n");
        sb.append("                .replace(\"/\", \"&#x2F;\");\n");
        sb.append("    }\n\n");
        sb.append("    /**\n");
        sb.append("     * Vérifie qu'une chaîne ne contient pas de caractères de contrôle\n");
        sb.append("     * ou de séquences d'injection XML/CRLF.\n");
        sb.append("     *\n");
        sb.append("     * @param input la chaîne à vérifier\n");
        sb.append("     * @return true si la chaîne est sûre\n");
        sb.append("     */\n");
        sb.append("    public static boolean isSafe(String input) {\n");
        sb.append("        if (input == null) return true;\n");
        sb.append("        // Rejeter les caractères de contrôle (sauf espace, tab, newline)\n");
        sb.append("        for (char c : input.toCharArray()) {\n");
        sb.append("            if (c < 0x20 && c != '\\t' && c != '\\n' && c != '\\r') return false;\n");
        sb.append("        }\n");
        sb.append("        // Rejeter les séquences CRLF injection\n");
        sb.append("        if (input.contains(\"\\r\\n\")) return false;\n");
        sb.append("        return true;\n");
        sb.append("    }\n");
        sb.append("}\n");
        Files.writeString(configDir.resolve("InputSanitizer.java"), sb.toString());
    }

    // ===== beans.xml =====

    private void generateBeansXml(Path resourcesDir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<beans xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\"\n");
        sb.append("       xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        sb.append("       xsi:schemaLocation=\"http://xmlns.jcp.org/xml/ns/javaee\n");
        sb.append("           http://xmlns.jcp.org/xml/ns/javaee/beans_1_1.xsd\"\n");
        sb.append("       bean-discovery-mode=\"all\">\n");
        sb.append("</beans>\n");

        Path metaInf = resourcesDir.resolve("META-INF");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("beans.xml"), sb.toString());
    }

    // ===== Utilitaires =====

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    private String camelToKebab(String str) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('-');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private boolean isPrimitiveType(String type) {
        if (type == null) return true;
        switch (type) {
            case "String":
            case "int":
            case "Integer":
            case "long":
            case "Long":
            case "double":
            case "Double":
            case "float":
            case "Float":
            case "boolean":
            case "Boolean":
            case "short":
            case "Short":
            case "byte":
            case "Byte":
            case "char":
            case "Character":
                return true;
            default:
                return false;
        }
    }

    private String javaType(String type) {
        if (type == null) return "String";
        switch (type) {
            case "int": return "int";
            case "boolean": return "boolean";
            case "double": case "float": return "double";
            case "long": return "long";
            default: return "String";
        }
    }

    // ===== README.md =====

    private void generateReadme(Path outputDir, List<EjbInfo> ejbs) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(artifactId).append("\n\n");
        sb.append("Module WAR JAX-RS — Adaptateur REST pour les EJBs du projet legacy.\n\n");
        sb.append("## Architecture\n\n");
        sb.append("Ce module est un **adaptateur pur** (pattern Adapter) qui expose les EJBs existants\n");
        sb.append("via des endpoints REST JSON. Il est déployé dans le même EAR que les EJBs sur WebSphere.\n\n");
        sb.append("```\n");
        sb.append("Client HTTP ──> [Resource JAX-RS] ──> [Converter DTO↔Envelope] ──> [EJB via @EJB]\n");
        sb.append("```\n\n");
        sb.append("## EJBs exposés\n\n");
        sb.append("| EJB Interface | Resource | Endpoints |\n");
        sb.append("|---|---|---|\n");
        for (EjbInfo ejb : ejbs) {
            String resourceName = capitalize(ejb.deriveResourceName().replace("-", "")) + "Resource";
            sb.append("| ").append(ejb.getInterfaceName())
              .append(" | ").append(resourceName)
              .append(" | ").append(ejb.getMethods().size())
              .append(" |\n");
        }
        sb.append("\n");
        sb.append("## Endpoints\n\n");
        for (EjbInfo ejb : ejbs) {
            sb.append("### /api/").append(ejb.deriveResourceName()).append("\n\n");
            for (MethodInfo method : ejb.getMethods()) {
                MethodInfo.HttpMethod httpMethod = method.getHttpMethod() != null ? method.getHttpMethod() : method.inferHttpMethod();
                sb.append("- `").append(httpMethod.name()).append(" /api/")
                  .append(ejb.deriveResourceName()).append("/")
                  .append(camelToKebab(method.getName())).append("`\n");
            }
            sb.append("\n");
        }
        sb.append("## Structure multi-modules\n\n");
        sb.append("```\n");
        sb.append(artifactId).append("/\n");
        sb.append("├── pom.xml                    (parent POM, packaging=pom)\n");
        sb.append("├── ").append(artifactId).append("-ejb/          (module EJB, packaging=ejb)\n");
        sb.append("├── ").append(artifactId).append("-ear/          (module EAR, packaging=ear)\n");
        sb.append("└── ").append(artifactId).append("-web/          (module WAR, packaging=war)\n");
        sb.append("```\n\n");
        sb.append("## Build\n\n");
        sb.append("```bash\n");
        sb.append("# Compiler tout le projet (EJB + WAR + EAR)\n");
        sb.append("mvn clean package\n\n");
        sb.append("# L'EAR g\u00e9n\u00e9r\u00e9 se trouve dans :\n");
        sb.append("# ").append(artifactId).append("-ear/target/").append(artifactId).append("-ear.ear\n");
        sb.append("```\n\n");
        sb.append("## D\u00e9ploiement\n\n");
        sb.append("### Via Docker\n\n");
        sb.append("```bash\n");
        sb.append("cd ").append(artifactId).append("-web\n");
        sb.append("docker build -t ").append(artifactId).append(" .\n");
        sb.append("docker run -p 9080:9080 ").append(artifactId).append("\n");
        sb.append("```\n\n");
        sb.append("### Via install_app (WebSphere)\n\n");
        sb.append("```bash\n");
        sb.append("cd ").append(artifactId).append("-web\n");
        sb.append("chmod +x install_app\n");
        sb.append("./install_app\n");
        sb.append("```\n\n");
        sb.append("## Pr\u00e9requis\n\n");
        sb.append("- Java 8+\n");
        sb.append("- WebSphere Application Server 8.5.5+ / Liberty\n");
        sb.append("- Les JARs `eai-commons-services` et `eai-midw-connectors` dans le classpath EAR\n\n");
        sb.append("## Configuration\n\n");
        sb.append("Le lookup JNDI est configur\u00e9 via `@EJB(lookup = \"java:app/...\")`.\n");
        sb.append("Adapter le lookup si le nom JNDI diff\u00e8re dans votre environnement.\n");

        Files.writeString(outputDir.resolve("README.md"), sb.toString());
    }

    // ===== Deployment files (Dockerfile, install_app, run-local) =====

    /**
     * G\u00e9n\u00e8re un Dockerfile pour d\u00e9ployer le WAR sur WebSphere Liberty.
     */
    private void generateDockerfile(Path webModuleDir) throws IOException {
        String webArtifactId = artifactId + "-web";
        StringBuilder df = new StringBuilder();
        df.append("FROM websphere-liberty:24.0.0.6-javaee8\n\n");
        df.append("# Configuration Liberty\n");
        df.append("COPY --chown=1001:0 src/main/liberty/config/server.xml /config/server.xml\n\n");
        df.append("# Copier les librairies partag\u00e9es (EAI commons)\n");
        df.append("COPY --chown=1001:0 libs/ /config/lib/global/\n\n");
        df.append("# Copier le WAR\n");
        df.append("COPY --chown=1001:0 target/").append(webArtifactId).append(".war /config/apps/\n\n");
        df.append("# Copier l'EAR si disponible\n");
        df.append("# COPY --chown=1001:0 ../").append(artifactId).append("-ear/target/").append(artifactId).append("-ear.ear /config/apps/\n\n");
        df.append("EXPOSE 9080 9443\n");
        df.append("RUN configure.sh\n");
        Files.writeString(webModuleDir.resolve("Dockerfile"), df.toString());

        // Also generate a minimal server.xml for Liberty
        Path libertyConfig = webModuleDir.resolve("src/main/liberty/config");
        Files.createDirectories(libertyConfig);
        StringBuilder serverXml = new StringBuilder();
        serverXml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        serverXml.append("<server description=\"").append(artifactId).append(" REST Adapter\">\n\n");
        serverXml.append("    <featureManager>\n");
        serverXml.append("        <feature>javaee-7.0</feature>\n");
        serverXml.append("        <feature>jaxrs-2.0</feature>\n");
        serverXml.append("        <feature>ejbLite-3.2</feature>\n");
        serverXml.append("        <feature>jsonp-1.0</feature>\n");
        serverXml.append("    </featureManager>\n\n");
        serverXml.append("    <httpEndpoint id=\"defaultHttpEndpoint\"\n");
        serverXml.append("                  host=\"*\"\n");
        serverXml.append("                  httpPort=\"9080\"\n");
        serverXml.append("                  httpsPort=\"9443\" />\n\n");
        serverXml.append("    <application location=\"").append(webArtifactId).append(".war\"\n");
        serverXml.append("                 context-root=\"/api\"\n");
        serverXml.append("                 type=\"war\" />\n\n");
        serverXml.append("    <library id=\"eaiLib\">\n");
        serverXml.append("        <fileset dir=\"${server.config.dir}/lib/global\" includes=\"*.jar\" />\n");
        serverXml.append("    </library>\n\n");
        serverXml.append("</server>\n");
        Files.writeString(libertyConfig.resolve("server.xml"), serverXml.toString());

        // Create libs/ placeholder
        Path libsDir = webModuleDir.resolve("libs");
        Files.createDirectories(libsDir);
        Files.writeString(libsDir.resolve(".gitkeep"), "# Place eai-commons-services.jar and eai-midw-connectors.jar here\n");
    }

    /**
     * G\u00e9n\u00e8re le script install_app pour d\u00e9ployer sur WebSphere via wsadmin.
     */
    private void generateInstallApp(Path webModuleDir) throws IOException {
        String earArtifactId = artifactId + "-ear";
        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n");
        script.append("# Script d'installation de l'application sur WebSphere\n");
        script.append("# Adapter les variables selon votre environnement\n\n");
        script.append("WAS_HOME=\"/opt/IBM/WebSphere/AppServer\"\n");
        script.append("CELL=\"$(hostname)Cell01\"\n");
        script.append("NODE=\"$(hostname)Node01\"\n");
        script.append("SERVER=\"server1\"\n");
        script.append("APP_NAME=\"").append(artifactId).append("\"\n");
        script.append("EAR_PATH=\"$(dirname $0)/../").append(earArtifactId).append("/target/").append(earArtifactId).append(".ear\"\n\n");
        script.append("echo \"=== Installation de ${APP_NAME} sur WebSphere ==\"\n");
        script.append("echo \"EAR: ${EAR_PATH}\"\n");
        script.append("echo \"Serveur: ${CELL}/${NODE}/${SERVER}\"\n\n");
        script.append("if [ ! -f \"${EAR_PATH}\" ]; then\n");
        script.append("    echo \"ERREUR: EAR non trouv\u00e9. Lancez 'mvn clean package' depuis la racine du projet.\"\n");
        script.append("    exit 1\n");
        script.append("fi\n\n");
        script.append("${WAS_HOME}/bin/wsadmin.sh -lang jython -c \"\n");
        script.append("AdminApp.install('${EAR_PATH}', [\n");
        script.append("    '-appname', '${APP_NAME}',\n");
        script.append("    '-cell', '${CELL}',\n");
        script.append("    '-node', '${NODE}',\n");
        script.append("    '-server', '${SERVER}',\n");
        script.append("    '-contextroot', '/api'\n");
        script.append("])\n");
        script.append("AdminConfig.save()\n");
        script.append("\"\n\n");
        script.append("echo \"=== Installation termin\u00e9e ===\"\n");
        Files.writeString(webModuleDir.resolve("install_app"), script.toString());
    }

    /**
     * G\u00e9n\u00e8re le script run-local pour lancer en local avec Liberty.
     */
    private void generateRunLocal(Path webModuleDir) throws IOException {
        String webArtifactId = artifactId + "-web";
        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n");
        script.append("# Script pour lancer l'application en local avec WebSphere Liberty\n");
        script.append("# Pr\u00e9requis: Liberty install\u00e9 ou Docker\n\n");
        script.append("set -e\n\n");
        script.append("echo \"=== Build du projet ===\"\n");
        script.append("cd $(dirname $0)/..\n");
        script.append("mvn clean package -DskipTests\n\n");
        script.append("echo \"=== Lancement via Docker ===\"\n");
        script.append("cd ").append(webArtifactId).append("\n");
        script.append("docker build -t ").append(artifactId).append("-local .\n");
        script.append("docker run --rm -p 9080:9080 -p 9443:9443 \\ \n");
        script.append("    --name ").append(artifactId).append("-local \\\n");
        script.append("    ").append(artifactId).append("-local\n\n");
        script.append("echo \"Application disponible sur http://localhost:9080/api\"\n");
        Files.writeString(webModuleDir.resolve("run-local"), script.toString());
    }
}
