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
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Parse un projet EJB depuis un fichier .zip.
 * Extrait les interfaces @Remote/@Local et les classes @Stateless/@Stateful/@Singleton.
 * Produit une liste d'EjbInfo prêts à être transformés en JAX-RS Resources.
 */
public class EjbZipParser {

    private static final Logger log = LoggerFactory.getLogger(EjbZipParser.class);

    private static final Set<String> EJB_ANNOTATIONS = Set.of(
            "Stateless", "Stateful", "Singleton", "MessageDriven"
    );
    private static final Set<String> INTERFACE_ANNOTATIONS = Set.of(
            "Remote", "Local"
    );

    private final JavaParser javaParser = new JavaParser();

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

        // Collecter tous les fichiers .java
        List<Path> javaFiles;
        try (var stream = Files.walk(projectDir)) {
            javaFiles = stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        String rel = projectDir.relativize(p).toString();
                        return !rel.contains("/test/") && !rel.startsWith("test/");
                    })
                    .collect(Collectors.toList());
        }

        log.info("Found {} Java files", javaFiles.size());

        // Phase 1: identifier les interfaces @Remote/@Local
        Map<String, EjbInfo> interfaceMap = new LinkedHashMap<>();
        // Phase 2: identifier les implémentations @Stateless etc.
        Map<String, String> implToInterface = new LinkedHashMap<>();

        for (Path file : javaFiles) {
            parseJavaFile(file, interfaceMap, implToInterface);
        }

        // Phase 3: résoudre les implémentations
        for (var entry : implToInterface.entrySet()) {
            String implName = entry.getKey();
            String ifaceName = entry.getValue();
            EjbInfo ejb = interfaceMap.get(ifaceName);
            if (ejb != null) {
                ejb.setImplementationName(implName);
            }
        }

        // Phase 4: si aucune interface @Remote/@Local trouvée, traiter les @Stateless directement
        if (interfaceMap.isEmpty()) {
            log.info("No @Remote/@Local interfaces found, parsing @Stateless classes directly");
            for (Path file : javaFiles) {
                parseStatelessAsEjb(file, interfaceMap);
            }
        }

        List<EjbInfo> result = new ArrayList<>(interfaceMap.values());
        // Déduire les méthodes HTTP
        for (EjbInfo ejb : result) {
            for (MethodInfo method : ejb.getMethods()) {
                if (method.getHttpMethod() == null) {
                    method.setHttpMethod(method.inferHttpMethod());
                }
            }
            // Déduire le JNDI name si absent
            if (ejb.getJndiName() == null || ejb.getJndiName().isBlank()) {
                ejb.setJndiName("java:global/" + ejb.getInterfaceName());
            }
        }

        log.info("Parsed {} EJBs", result.size());
        return result;
    }

    private void parseJavaFile(Path file, Map<String, EjbInfo> interfaceMap,
                               Map<String, String> implToInterface) {
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
                } else {
                    // Classe: vérifier si c'est un @Stateless/@Stateful etc.
                    Optional<AnnotationExpr> ejbAnnotation = decl.getAnnotations().stream()
                            .filter(a -> EJB_ANNOTATIONS.contains(a.getNameAsString()))
                            .findFirst();

                    if (ejbAnnotation.isPresent()) {
                        String ejbTypeName = ejbAnnotation.get().getNameAsString();
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
                        String jndiName = extractJndiName(ejbAnnotation.get(), decl.getNameAsString());

                        // Si l'interface existe déjà dans la map, mettre à jour
                        if (ifaceName != null && interfaceMap.containsKey(ifaceName)) {
                            EjbInfo ejb = interfaceMap.get(ifaceName);
                            ejb.setEjbType(ejbType);
                            ejb.setImplementationName(decl.getNameAsString());
                            if (jndiName != null) ejb.setJndiName(jndiName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse file: {} - {}", file, e.getMessage());
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
                    ejb.setJndiName(extractJndiName(ejbAnnotation.get(), decl.getNameAsString()));
                    extractMethods(decl, ejb);
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
            // Ignorer les méthodes privées/protected
            if (md.isPrivate() || md.isProtected()) continue;

            MethodInfo method = new MethodInfo(
                    md.getNameAsString(),
                    md.getType().asString()
            );

            // Paramètres
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

            ejb.addMethod(method);
        }
    }

    private String extractJndiName(AnnotationExpr annotation, String className) {
        if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if ("mappedName".equals(pair.getNameAsString()) || "name".equals(pair.getNameAsString())) {
                    return pair.getValue().asStringLiteralExpr().getValue();
                }
            }
        } else if (annotation instanceof SingleMemberAnnotationExpr single) {
            return single.getMemberValue().asStringLiteralExpr().getValue();
        }
        return "java:global/" + className;
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
