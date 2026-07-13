package com.bank.tools.jaxrs.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Parse toutes les classes Java d'un projet pour en extraire les champs (fields).
 * Utilisé pour résoudre les types de retour et les types de paramètres complexes
 * des méthodes EJB afin de générer des DTOs avec les vrais noms de propriétés.
 *
 * <p>Stratégie d'extraction :</p>
 * <ol>
 *   <li>Champs déclarés (private/protected/public fields)</li>
 *   <li>Getters/Setters (déduit le nom du champ depuis getName() → "name")</li>
 *   <li>Annotations @XmlElement pour les noms JAXB</li>
 * </ol>
 */
public class DtoClassParser {

    private static final Logger log = LoggerFactory.getLogger(DtoClassParser.class);

    private final JavaParser javaParser = new JavaParser();

    /**
     * Représente un champ extrait d'une classe Java.
     */
    public static class ClassField {
        private final String name;
        private final String type;
        private final String typeSimple;
        private final boolean isList;

        public ClassField(String name, String type) {
            this.name = name;
            this.isList = type != null && (type.startsWith("List<") || type.startsWith("java.util.List<")
                    || type.startsWith("ArrayList<") || type.startsWith("Collection<"));
            if (this.isList) {
                // Extract inner type: List<Foo> → Foo
                int start = type.indexOf('<');
                int end = type.lastIndexOf('>');
                this.type = (start >= 0 && end > start) ? type.substring(start + 1, end).trim() : "Object";
            } else {
                this.type = type != null ? type : "Object";
            }
            int lastDot = this.type.lastIndexOf('.');
            this.typeSimple = lastDot >= 0 ? this.type.substring(lastDot + 1) : this.type;
        }

        public String getName() { return name; }
        public String getType() { return type; }
        public String getTypeSimple() { return typeSimple; }
        public boolean isList() { return isList; }

        @Override
        public String toString() {
            return (isList ? "List<" + typeSimple + ">" : typeSimple) + " " + name;
        }
    }

    /**
     * Représente les métadonnées d'une classe parsée.
     */
    public static class ParsedClass {
        private final String simpleName;
        private final String fullName;
        private final String packageName;
        private final List<ClassField> fields;

        public ParsedClass(String simpleName, String packageName, List<ClassField> fields) {
            this.simpleName = simpleName;
            this.packageName = packageName;
            this.fullName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
            this.fields = fields;
        }

        public String getSimpleName() { return simpleName; }
        public String getFullName() { return fullName; }
        public String getPackageName() { return packageName; }
        public List<ClassField> getFields() { return fields; }

        @Override
        public String toString() {
            return "ParsedClass{" + fullName + ", fields=" + fields.size() + "}";
        }
    }

    /**
     * Scanne tous les fichiers Java d'un répertoire et extrait les métadonnées de chaque classe.
     *
     * @param projectDir répertoire racine du projet
     * @return map de nom simple de classe → ParsedClass
     */
    public Map<String, ParsedClass> parseAllClasses(Path projectDir) throws IOException {
        Map<String, ParsedClass> classMap = new LinkedHashMap<>();

        List<Path> javaFiles;
        try (var stream = Files.walk(projectDir)) {
            javaFiles = stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        String rel = projectDir.relativize(p).toString();
                        return !rel.contains("/test/") && !rel.contains("/target/");
                    })
                    .collect(Collectors.toList());
        }

        log.info("DtoClassParser: scanning {} Java files for class metadata", javaFiles.size());

        for (Path file : javaFiles) {
            try {
                parseFile(file, classMap);
            } catch (Exception e) {
                log.debug("DtoClassParser: failed to parse {} - {}", file.getFileName(), e.getMessage());
            }
        }

        log.info("DtoClassParser: extracted {} classes with fields", classMap.size());
        return classMap;
    }

    private void parseFile(Path file, Map<String, ParsedClass> classMap) throws IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        ParseResult<CompilationUnit> result = javaParser.parse(source);
        if (!result.isSuccessful() || result.getResult().isEmpty()) return;

        CompilationUnit cu = result.getResult().get();
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (decl.isInterface()) continue;

            List<ClassField> fields = extractFields(decl);
            if (!fields.isEmpty()) {
                ParsedClass parsedClass = new ParsedClass(decl.getNameAsString(), packageName, fields);
                classMap.put(decl.getNameAsString(), parsedClass);
                log.debug("DtoClassParser: {} → {} fields", decl.getNameAsString(), fields.size());
            }
        }
    }

    /**
     * Extrait les champs d'une classe en combinant :
     * 1. Les déclarations de champs
     * 2. Les getters (pour capturer des champs non déclarés ou avec noms différents)
     */
    private List<ClassField> extractFields(ClassOrInterfaceDeclaration decl) {
        Map<String, ClassField> fieldMap = new LinkedHashMap<>();

        // 1. Champs déclarés
        for (FieldDeclaration fieldDecl : decl.getFields()) {
            // Skip static and final constants
            if (fieldDecl.isStatic()) continue;

            for (VariableDeclarator var : fieldDecl.getVariables()) {
                String name = var.getNameAsString();
                String type = var.getType().asString();

                // Skip serialVersionUID and logger-like fields
                if ("serialVersionUID".equals(name) || name.startsWith("log")) continue;

                fieldMap.put(name, new ClassField(name, type));
            }
        }

        // 2. Getters — déduit les champs depuis les noms de méthodes get*/is*
        for (MethodDeclaration method : decl.getMethods()) {
            String methodName = method.getNameAsString();
            if (method.isPrivate() || method.isStatic()) continue;
            if (!method.getParameters().isEmpty()) continue; // getter = 0 params

            String fieldName = null;
            if (methodName.startsWith("get") && methodName.length() > 3) {
                fieldName = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
            } else if (methodName.startsWith("is") && methodName.length() > 2
                    && Character.isUpperCase(methodName.charAt(2))) {
                fieldName = Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
            }

            if (fieldName != null && !fieldMap.containsKey(fieldName)) {
                String returnType = method.getType().asString();
                if (!"void".equals(returnType)) {
                    fieldMap.put(fieldName, new ClassField(fieldName, returnType));
                }
            }
        }

        // Filter out generic/meaningless field names (in0, in1, arg0, etc.)
        List<ClassField> result = new ArrayList<>();
        for (ClassField field : fieldMap.values()) {
            if (!isGenericFieldName(field.getName())) {
                result.add(field);
            }
        }

        return result;
    }

    /**
     * Détecte les noms de champs génériques (in0, in1, arg0, param1, etc.)
     * qui n'apportent pas de valeur métier.
     */
    private boolean isGenericFieldName(String name) {
        if (name == null) return true;
        // Patterns: in0, in1, arg0, arg1, param0, param1, p0, p1
        return name.matches("^(in|arg|param|p)\\d+$");
    }

    /**
     * Résout un type simple (nom de classe) vers ses champs parsés.
     * Gère les types génériques comme List<Foo> en retournant les champs de Foo.
     *
     * @param typeName le nom du type (simple ou qualifié)
     * @param classMap la map des classes parsées
     * @return les champs de la classe, ou empty si non trouvée
     */
    public Optional<ParsedClass> resolveType(String typeName, Map<String, ParsedClass> classMap) {
        if (typeName == null || typeName.isBlank()) return Optional.empty();

        // Handle List<Foo> → resolve Foo
        if (typeName.contains("<")) {
            int start = typeName.indexOf('<');
            int end = typeName.lastIndexOf('>');
            if (start >= 0 && end > start) {
                String innerType = typeName.substring(start + 1, end).trim();
                return resolveType(innerType, classMap);
            }
        }

        // Extract simple name from fully qualified
        String simpleName = typeName;
        int lastDot = typeName.lastIndexOf('.');
        if (lastDot >= 0) {
            simpleName = typeName.substring(lastDot + 1);
        }

        // Skip primitives and standard library types
        if (isPrimitiveOrStandard(simpleName)) return Optional.empty();

        return Optional.ofNullable(classMap.get(simpleName));
    }

    private boolean isPrimitiveOrStandard(String type) {
        return Set.of(
                "void", "int", "long", "double", "float", "boolean", "byte", "short", "char",
                "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short", "Character",
                "String", "BigDecimal", "BigInteger", "Date", "LocalDate", "LocalDateTime",
                "Object", "Envelope", "List", "Map", "Set", "Collection", "Optional"
        ).contains(type);
    }
}
