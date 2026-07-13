package com.bank.tools.jaxrs.parser;

import com.bank.tools.jaxrs.model.EjbInfo;
import com.bank.tools.jaxrs.model.MethodInfo;
import com.bank.tools.jaxrs.model.ParameterInfo;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Parse un projet EJB depuis un fichier .zip ou un répertoire.
 * Détecte :
 * <ul>
 *   <li>Interfaces @Remote/@Local et classes @Stateless/@Stateful/@Singleton (EJB classique)</li>
 *   <li>Classes @WebService avec @WebMethod (JAX-WS SOAP)</li>
 * </ul>
 * Produit une liste d'EjbInfo prêts à être transformés en JAX-RS Resources.
 * <p>
 * Le parser extrait également le corps des méthodes depuis les implémentations
 * pour permettre une transformation directe du code métier.
 */
public class EjbZipParser {

    private static final Logger log = LoggerFactory.getLogger(EjbZipParser.class);

    private static final Set<String> EJB_ANNOTATIONS = Set.of(
            "Stateless", "Stateful", "Singleton", "MessageDriven"
    );
    private static final Set<String> INTERFACE_ANNOTATIONS = Set.of(
            "Remote", "Local"
    );
    private static final Set<String> WEBSERVICE_ANNOTATIONS = Set.of(
            "WebService"
    );
    private static final Set<String> WEBMETHOD_ANNOTATIONS = Set.of(
            "WebMethod"
    );

    private final JavaParser javaParser = new JavaParser();
    private final DtoClassParser dtoClassParser = new DtoClassParser();
    private final FunctionCodeParser functionCodeParser = new FunctionCodeParser();
    private final SourceProjectMetadataParser metadataParser = new SourceProjectMetadataParser();

    /**
     * Map des classes parsées dans le dernier projet analysé.
     * Clé = nom simple de la classe, Valeur = métadonnées avec champs.
     * Disponible après un appel à parse() ou parseDirectory().
     */
    private Map<String, DtoClassParser.ParsedClass> parsedClassMap = Collections.emptyMap();
    private com.bank.tools.jaxrs.model.SourceProjectMetadata sourceMetadata;

    /**
     * Retourne la map des classes parsées lors du dernier appel à parse/parseDirectory.
     */
    public Map<String, DtoClassParser.ParsedClass> getParsedClassMap() {
        return parsedClassMap;
    }

    /**
     * Retourne les métadonnées du projet source (POM, JNDI bindings).
     */
    public com.bank.tools.jaxrs.model.SourceProjectMetadata getSourceMetadata() {
        return sourceMetadata;
    }

    /**
     * Parse un fichier .zip contenant un projet EJB.
     *
     * @param zipPath chemin vers le fichier .zip
     * @return liste des EJB détectés
     */
    public List<EjbInfo> parse(Path zipPath) throws IOException {
        log.info("Parsing EJB project from: {}", zipPath);

        // Extraire dans un répertoire temporaire
        Path tempDir = Files.createTempDirectory("ejb-parse-");
        try {
            extractZip(zipPath, tempDir);
            return parseDirectory(tempDir);
        } finally {
            deleteRecursive(tempDir);
        }
    }

    /**
     * Parse un répertoire contenant un projet EJB (déjà extrait).
     */
    public List<EjbInfo> parseDirectory(Path projectDir) throws IOException {
        log.info("Scanning directory: {}", projectDir);

        // Phase 0a: Extraire les métadonnées du projet source (POM, JNDI bindings)
        this.sourceMetadata = metadataParser.parse(projectDir);
        log.info("Source metadata: {}", sourceMetadata);

        // Phase 0b: Scanner toutes les classes pour extraire les champs DTO/Entity
        this.parsedClassMap = dtoClassParser.parseAllClasses(projectDir);
        log.info("DtoClassParser found {} classes with fields", parsedClassMap.size());

        // Collecter tous les fichiers .java (hors src/test et target)
        List<Path> javaFiles;
        try (var stream = Files.walk(projectDir)) {
            javaFiles = stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        // Utiliser le chemin relatif au répertoire du projet
                        String rel = projectDir.relativize(p).toString();
                        return !rel.startsWith("test/") && !rel.contains("/test/java/")
                                && !rel.contains("/target/");
                    })
                    .collect(Collectors.toList());
        }

        log.info("Found {} Java files", javaFiles.size());

        // Phase 1: identifier les interfaces @Remote/@Local
        Map<String, EjbInfo> interfaceMap = new LinkedHashMap<>();
        // Phase 2: identifier les implémentations @Stateless etc.
        Map<String, String> implToInterface = new LinkedHashMap<>();
        // Phase 3: stocker les corps de méthodes des implémentations
        Map<String, Map<String, String>> implMethodBodies = new LinkedHashMap<>();
        // Phase 4: identifier les classes @WebService
        List<EjbInfo> webServiceEjbs = new ArrayList<>();

        for (Path file : javaFiles) {
            parseJavaFile(file, interfaceMap, implToInterface, implMethodBodies, webServiceEjbs);
        }

        // Phase 5: résoudre les implémentations et injecter les corps de méthodes
        for (var entry : implToInterface.entrySet()) {
            String implName = entry.getKey();
            String ifaceName = entry.getValue();
            EjbInfo ejb = interfaceMap.get(ifaceName);
            if (ejb != null) {
                ejb.setImplementationName(implName);
                Map<String, String> bodies = implMethodBodies.get(implName);
                if (bodies != null) {
                    for (MethodInfo method : ejb.getMethods()) {
                        String body = bodies.get(method.getName());
                        if (body != null) {
                            method.setMethodBody(body);
                        }
                    }
                }
            }
        }

        // Phase 6: si aucune interface @Remote/@Local trouvée, traiter les @Stateless directement
        if (interfaceMap.isEmpty()) {
            log.info("No @Remote/@Local interfaces found, parsing @Stateless classes directly");
            for (Path file : javaFiles) {
                parseStatelessAsEjb(file, interfaceMap);
            }
        }

        // Phase 7: si toujours rien, ajouter les @WebService détectés
        if (interfaceMap.isEmpty() && !webServiceEjbs.isEmpty()) {
            log.info("No EJB classes found, using {} @WebService classes", webServiceEjbs.size());
            for (EjbInfo ws : webServiceEjbs) {
                interfaceMap.put(ws.getInterfaceName(), ws);
            }
        } else if (!interfaceMap.isEmpty() && !webServiceEjbs.isEmpty()) {
            // Ajouter les @WebService en complément si pas déjà présents
            for (EjbInfo ws : webServiceEjbs) {
                if (!interfaceMap.containsKey(ws.getInterfaceName())) {
                    interfaceMap.put(ws.getInterfaceName(), ws);
                }
            }
        }

        List<EjbInfo> result = new ArrayList<>(interfaceMap.values());

        // Phase 8: Extraire les codes fonction depuis le corps de process()
        // et stocker le corps complet de la classe pour le FunctionCodeParser
        Map<String, String> fullClassBodies = new LinkedHashMap<>();
        for (Path file : javaFiles) {
            try {
                String source = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
                com.github.javaparser.ParseResult<com.github.javaparser.ast.CompilationUnit> pr = javaParser.parse(source);
                if (pr.isSuccessful() && pr.getResult().isPresent()) {
                    for (var decl : pr.getResult().get().findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)) {
                        if (!decl.isInterface()) {
                            fullClassBodies.put(decl.getNameAsString(), source);
                        }
                    }
                }
            } catch (Exception e) {
                // ignore
            }
        }

        for (EjbInfo ejb : result) {
            // Stocker le corps complet de la classe d'implémentation
            if (ejb.getImplementationName() != null) {
                String classBody = fullClassBodies.get(ejb.getImplementationName());
                if (classBody != null) {
                    ejb.setFullClassBody(classBody);
                    // Extraire les codes fonction depuis le process() method body
                    String processBody = null;
                    for (MethodInfo method : ejb.getMethods()) {
                        if ("process".equals(method.getName()) && method.hasMethodBody()) {
                            processBody = method.getMethodBody();
                            break;
                        }
                    }
                    if (processBody != null) {
                        var functionCodes = functionCodeParser.parse(processBody, classBody);
                        ejb.setFunctionCodes(functionCodes);
                        log.info("EJB {} has {} function codes", ejb.getInterfaceName(), functionCodes.size());
                    }
                }
            }

            // Déduire les méthodes HTTP
            for (MethodInfo method : ejb.getMethods()) {
                if (method.getHttpMethod() == null) {
                    method.setHttpMethod(method.inferHttpMethod());
                }
            }

            // Résoudre le JNDI name depuis les bindings du projet source
            if (sourceMetadata != null && !sourceMetadata.getJndiBindings().isEmpty()) {
                String implName = ejb.getImplementationName();
                if (implName != null) {
                    String jndi = sourceMetadata.getJndiNameForEjb(implName);
                    if (jndi != null) {
                        ejb.setJndiName(jndi);
                    }
                }
            }
            // Fallback JNDI
            if (ejb.getJndiName() == null || ejb.getJndiName().isBlank()) {
                ejb.setJndiName("ejb/" + ejb.getImplementationName());
            }
        }

        log.info("Parsed {} EJBs", result.size());
        return result;
    }

    private void parseJavaFile(Path file, Map<String, EjbInfo> interfaceMap,
                               Map<String, String> implToInterface,
                               Map<String, Map<String, String>> implMethodBodies,
                               List<EjbInfo> webServiceEjbs) {
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            ParseResult<CompilationUnit> result = javaParser.parse(source);
            if (!result.isSuccessful() || result.getResult().isEmpty()) return;

            CompilationUnit cu = result.getResult().get();
            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");

            for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (decl.isInterface()) {
                    // Vérifier si c'est une interface @Remote ou @Local
                    boolean isEjbInterface = decl.getAnnotations().stream()
                            .anyMatch(a -> INTERFACE_ANNOTATIONS.contains(a.getNameAsString()));

                    if (isEjbInterface) {
                        EjbInfo ejb = new EjbInfo(decl.getNameAsString(), packageName);
                        extractMethods(decl, ejb);
                        interfaceMap.put(decl.getNameAsString(), ejb);
                        log.debug("Found EJB interface: {}", decl.getNameAsString());
                    }

                    // Vérifier si c'est une interface @WebService (JAX-WS generated)
                    Optional<AnnotationExpr> wsIfaceAnnotation = decl.getAnnotations().stream()
                            .filter(a -> WEBSERVICE_ANNOTATIONS.contains(a.getNameAsString()))
                            .findFirst();
                    if (wsIfaceAnnotation.isPresent()) {
                        handleWebServiceInterface(decl, wsIfaceAnnotation.get(), packageName, webServiceEjbs);
                    }
                } else {
                    // Classe: vérifier si c'est un @Stateless/@Stateful etc.
                    Optional<AnnotationExpr> ejbAnnotation = decl.getAnnotations().stream()
                            .filter(a -> EJB_ANNOTATIONS.contains(a.getNameAsString()))
                            .findFirst();

                    if (ejbAnnotation.isPresent()) {
                        handleEjbClass(decl, ejbAnnotation.get(), interfaceMap, implToInterface, implMethodBodies);
                    }

                    // Vérifier si c'est un @WebService
                    Optional<AnnotationExpr> wsAnnotation = decl.getAnnotations().stream()
                            .filter(a -> WEBSERVICE_ANNOTATIONS.contains(a.getNameAsString()))
                            .findFirst();

                    if (wsAnnotation.isPresent()) {
                        handleWebServiceClass(decl, wsAnnotation.get(), packageName, webServiceEjbs);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse file: {} - {}", file, e.getMessage());
        }
    }

    private void handleEjbClass(ClassOrInterfaceDeclaration decl, AnnotationExpr ejbAnnotation,
                                Map<String, EjbInfo> interfaceMap,
                                Map<String, String> implToInterface,
                                Map<String, Map<String, String>> implMethodBodies) {
        String ejbTypeName = ejbAnnotation.getNameAsString();
        EjbInfo.EjbType ejbType = EjbInfo.EjbType.valueOf(ejbTypeName.toUpperCase());

        // Trouver l'interface implémentée
        String ifaceName = decl.getImplementedTypes().stream()
                .map(t -> t.getNameAsString())
                .findFirst()
                .orElse(null);

        if (ifaceName != null) {
            implToInterface.put(decl.getNameAsString(), ifaceName);
        }

        // Extraire le JNDI name depuis l'annotation si présent
        String jndiName = extractAnnotationName(ejbAnnotation, decl.getNameAsString());

        // Si l'interface existe déjà dans la map, mettre à jour
        if (ifaceName != null && interfaceMap.containsKey(ifaceName)) {
            EjbInfo ejb = interfaceMap.get(ifaceName);
            ejb.setEjbType(ejbType);
            ejb.setImplementationName(decl.getNameAsString());
            if (jndiName != null) ejb.setJndiName(jndiName);
        }

        // Extraire les corps de méthodes de l'implémentation
        Map<String, String> bodies = extractMethodBodies(decl);
        if (!bodies.isEmpty()) {
            implMethodBodies.put(decl.getNameAsString(), bodies);
        }
    }

    /**
     * Traite une classe annotée @WebService : extrait les méthodes @WebMethod
     * comme des opérations à transformer en endpoints REST.
     */
    private void handleWebServiceClass(ClassOrInterfaceDeclaration decl, AnnotationExpr wsAnnotation,
                                       String packageName, List<EjbInfo> webServiceEjbs) {
        EjbInfo ejb = new EjbInfo(decl.getNameAsString(), packageName);
        ejb.setImplementationName(decl.getNameAsString());
        ejb.setEjbType(EjbInfo.EjbType.WEBSERVICE);

        // Extraire le serviceName depuis @WebService si disponible
        String serviceName = extractAnnotationAttribute(wsAnnotation, "serviceName");
        if (serviceName != null) {
            ejb.setJndiName(serviceName);
        }

        // Extraire les méthodes @WebMethod (ou toutes les méthodes publiques si pas d'annotation @WebMethod)
        boolean hasWebMethodAnnotations = decl.getMethods().stream()
                .anyMatch(md -> md.getAnnotations().stream()
                        .anyMatch(a -> WEBMETHOD_ANNOTATIONS.contains(a.getNameAsString())));

        for (MethodDeclaration md : decl.getMethods()) {
            if (md.isPrivate() || md.isProtected()) continue;

            // Si des @WebMethod existent, ne prendre que celles annotées
            if (hasWebMethodAnnotations) {
                boolean isWebMethod = md.getAnnotations().stream()
                        .anyMatch(a -> WEBMETHOD_ANNOTATIONS.contains(a.getNameAsString()));
                if (!isWebMethod) continue;
            }

            MethodInfo method = new MethodInfo(
                    md.getNameAsString(),
                    md.getType().asString()
            );

            // Paramètres (ignorer les annotations @WebParam pour le nom)
            md.getParameters().forEach(p -> {
                method.addParameter(new ParameterInfo(
                        p.getNameAsString(),
                        p.getType().asString()
                ));
            });

            // Exceptions
            md.getThrownExceptions().forEach(ex -> {
                method.getThrownExceptions().add(ex.asString());
            });

            // Corps de méthode
            md.getBody().ifPresent(body -> method.setMethodBody(body.toString()));

            ejb.addMethod(method);
        }

        if (!ejb.getMethods().isEmpty()) {
            webServiceEjbs.add(ejb);
            log.debug("Found @WebService class: {} ({} methods)", decl.getNameAsString(), ejb.getMethods().size());
        }
    }

    /**
     * Traite une interface annotée @WebService (générée par JAX-WS RI) :
     * extrait les méthodes @WebMethod comme des opérations à transformer en endpoints REST.
     * Contrairement aux classes @WebService, les interfaces n'ont pas de corps de méthode.
     */
    private void handleWebServiceInterface(ClassOrInterfaceDeclaration decl, AnnotationExpr wsAnnotation,
                                           String packageName, List<EjbInfo> webServiceEjbs) {
        EjbInfo ejb = new EjbInfo(decl.getNameAsString(), packageName);
        ejb.setImplementationName(decl.getNameAsString());
        ejb.setEjbType(EjbInfo.EjbType.WEBSERVICE);

        // Extraire le name ou targetNamespace depuis @WebService si disponible
        String serviceName = extractAnnotationAttribute(wsAnnotation, "name");
        if (serviceName != null) {
            ejb.setJndiName(serviceName);
        }

        // Extraire les méthodes @WebMethod (ou toutes les méthodes publiques)
        boolean hasWebMethodAnnotations = decl.getMethods().stream()
                .anyMatch(md -> md.getAnnotations().stream()
                        .anyMatch(a -> WEBMETHOD_ANNOTATIONS.contains(a.getNameAsString())));

        for (MethodDeclaration md : decl.getMethods()) {
            if (md.isPrivate() || md.isProtected()) continue;

            if (hasWebMethodAnnotations) {
                boolean isWebMethod = md.getAnnotations().stream()
                        .anyMatch(a -> WEBMETHOD_ANNOTATIONS.contains(a.getNameAsString()));
                if (!isWebMethod) continue;
            }

            MethodInfo method = new MethodInfo(
                    md.getNameAsString(),
                    md.getType().asString()
            );

            md.getParameters().forEach(p -> {
                method.addParameter(new ParameterInfo(
                        p.getNameAsString(),
                        p.getType().asString()
                ));
            });

            md.getThrownExceptions().forEach(ex -> {
                method.getThrownExceptions().add(ex.asString());
            });

            // Pas de corps pour les interfaces
            ejb.addMethod(method);
        }

        if (!ejb.getMethods().isEmpty()) {
            webServiceEjbs.add(ejb);
            log.debug("Found @WebService interface: {} ({} methods)", decl.getNameAsString(), ejb.getMethods().size());
        }
    }

    private void parseStatelessAsEjb(Path file, Map<String, EjbInfo> interfaceMap) {
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            ParseResult<CompilationUnit> result = javaParser.parse(source);
            if (!result.isSuccessful() || result.getResult().isEmpty()) return;

            CompilationUnit cu = result.getResult().get();
            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");

            for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (decl.isInterface()) continue;

                Optional<AnnotationExpr> ejbAnnotation = decl.getAnnotations().stream()
                        .filter(a -> EJB_ANNOTATIONS.contains(a.getNameAsString()))
                        .findFirst();

                if (ejbAnnotation.isPresent()) {
                    EjbInfo ejb = new EjbInfo(decl.getNameAsString(), packageName);
                    ejb.setImplementationName(decl.getNameAsString());
                    ejb.setEjbType(EjbInfo.EjbType.valueOf(ejbAnnotation.get().getNameAsString().toUpperCase()));
                    ejb.setJndiName(extractAnnotationName(ejbAnnotation.get(), decl.getNameAsString()));
                    extractMethodsWithBodies(decl, ejb);
                    interfaceMap.put(decl.getNameAsString(), ejb);
                    log.debug("Found @Stateless class (no interface): {}", decl.getNameAsString());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse file: {} - {}", file, e.getMessage());
        }
    }

    private void extractMethods(ClassOrInterfaceDeclaration decl, EjbInfo ejb) {
        for (MethodDeclaration md : decl.getMethods()) {
            if (md.isPrivate() || md.isProtected()) continue;

            MethodInfo method = new MethodInfo(
                    md.getNameAsString(),
                    md.getType().asString()
            );

            md.getParameters().forEach(p -> {
                method.addParameter(new ParameterInfo(
                        p.getNameAsString(),
                        p.getType().asString()
                ));
            });

            md.getThrownExceptions().forEach(ex -> {
                method.getThrownExceptions().add(ex.asString());
            });

            md.getBody().ifPresent(body -> method.setMethodBody(body.toString()));

            ejb.addMethod(method);
        }
    }

    /**
     * Extrait les méthodes avec leurs corps depuis une classe d'implémentation.
     * Utilisé quand le @Stateless est traité directement (pas d'interface séparée).
     */
    private void extractMethodsWithBodies(ClassOrInterfaceDeclaration decl, EjbInfo ejb) {
        for (MethodDeclaration md : decl.getMethods()) {
            if (md.isPrivate() || md.isProtected()) continue;

            MethodInfo method = new MethodInfo(
                    md.getNameAsString(),
                    md.getType().asString()
            );

            md.getParameters().forEach(p -> {
                method.addParameter(new ParameterInfo(
                        p.getNameAsString(),
                        p.getType().asString()
                ));
            });

            md.getThrownExceptions().forEach(ex -> {
                method.getThrownExceptions().add(ex.asString());
            });

            md.getBody().ifPresent(body -> method.setMethodBody(body.toString()));

            ejb.addMethod(method);
        }
    }

    /**
     * Extrait les corps de toutes les méthodes publiques d'une classe.
     */
    private Map<String, String> extractMethodBodies(ClassOrInterfaceDeclaration decl) {
        Map<String, String> bodies = new LinkedHashMap<>();
        for (MethodDeclaration md : decl.getMethods()) {
            if (md.isPrivate() || md.isProtected()) continue;
            md.getBody().ifPresent(body -> bodies.put(md.getNameAsString(), body.toString()));
        }
        return bodies;
    }

    /**
     * Extrait le nom JNDI ou mappedName depuis une annotation EJB.
     */
    private String extractAnnotationName(AnnotationExpr annotation, String className) {
        if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if ("mappedName".equals(pair.getNameAsString()) || "name".equals(pair.getNameAsString())) {
                    try {
                        return pair.getValue().asStringLiteralExpr().getValue();
                    } catch (Exception e) {
                        // Pas une string literal
                    }
                }
            }
        } else if (annotation instanceof SingleMemberAnnotationExpr single) {
            try {
                return single.getMemberValue().asStringLiteralExpr().getValue();
            } catch (Exception e) {
                // Pas une string literal
            }
        }
        return "java:global/" + className;
    }

    /**
     * Extrait un attribut spécifique depuis une annotation (ex: serviceName de @WebService).
     */
    private String extractAnnotationAttribute(AnnotationExpr annotation, String attributeName) {
        if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (attributeName.equals(pair.getNameAsString())) {
                    try {
                        return pair.getValue().asStringLiteralExpr().getValue();
                    } catch (Exception e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    // --- Utilitaires ZIP ---

    private void extractZip(Path zipPath, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(zipPath)), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName()).normalize();
                if (!entryPath.startsWith(targetDir)) {
                    throw new IOException("Zip entry outside target dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private void deleteRecursive(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }
}
