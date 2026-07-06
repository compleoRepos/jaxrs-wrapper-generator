package com.bank.tools.jaxrs.model;

import java.util.List;

/**
 * Représente un paramètre d'une méthode EJB.
 */
public class ParameterInfo {

    private String name;
    private String type;
    private String typeSimple;

    private static final List<String> PRIMITIVE_TYPES = List.of(
            "int", "long", "double", "float", "boolean", "byte", "short", "char",
            "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short", "Character",
            "String", "BigDecimal", "BigInteger", "Date", "LocalDate", "LocalDateTime"
    );

    public ParameterInfo() {}

    public ParameterInfo(String name, String type) {
        this.name = name;
        this.type = type;
        this.typeSimple = extractSimpleType(type);
    }

    // --- Getters & Setters ---

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) {
        this.type = type;
        this.typeSimple = extractSimpleType(type);
    }

    public String getTypeSimple() { return typeSimple; }

    /**
     * Détermine si le type est complexe (pas un primitif/wrapper/String).
     */
    public boolean isComplexType() {
        return !PRIMITIVE_TYPES.contains(typeSimple);
    }

    private String extractSimpleType(String fullType) {
        if (fullType == null) return "Object";
        // Gérer les génériques: List<Foo> → List
        int genIdx = fullType.indexOf('<');
        String base = genIdx >= 0 ? fullType.substring(0, genIdx) : fullType;
        int lastDot = base.lastIndexOf('.');
        return lastDot >= 0 ? base.substring(lastDot + 1) : base;
    }

    @Override
    public String toString() {
        return typeSimple + " " + name;
    }
}
