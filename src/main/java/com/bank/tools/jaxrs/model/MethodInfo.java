package com.bank.tools.jaxrs.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Représente une méthode d'un EJB à transformer en endpoint REST.
 */
public class MethodInfo {

    private String name;
    private String returnType;
    private String returnTypeSimple;
    private List<ParameterInfo> parameters = new ArrayList<>();
    private List<String> thrownExceptions = new ArrayList<>();
    private HttpMethod httpMethod;
    private String methodBody;

    public enum HttpMethod {
        GET, POST, PUT, DELETE
    }

    public MethodInfo() {}

    public MethodInfo(String name, String returnType) {
        this.name = name;
        this.returnType = returnType;
        this.returnTypeSimple = extractSimpleType(returnType);
    }

    // --- Getters & Setters ---

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getReturnType() { return returnType; }
    public void setReturnType(String returnType) {
        this.returnType = returnType;
        this.returnTypeSimple = extractSimpleType(returnType);
    }

    public String getReturnTypeSimple() { return returnTypeSimple; }

    public List<ParameterInfo> getParameters() { return parameters; }
    public void setParameters(List<ParameterInfo> parameters) { this.parameters = parameters; }

    public void addParameter(ParameterInfo param) { this.parameters.add(param); }

    public List<String> getThrownExceptions() { return thrownExceptions; }
    public void setThrownExceptions(List<String> thrownExceptions) { this.thrownExceptions = thrownExceptions; }

    public HttpMethod getHttpMethod() { return httpMethod; }
    public void setHttpMethod(HttpMethod httpMethod) { this.httpMethod = httpMethod; }

    public String getMethodBody() { return methodBody; }
    public void setMethodBody(String methodBody) { this.methodBody = methodBody; }

    /**
     * Indique si le corps de la méthode a été extrait depuis l'implémentation EJB.
     */
    public boolean hasMethodBody() {
        return methodBody != null && !methodBody.isBlank();
    }

    /**
     * Déduit la méthode HTTP à partir du nom de la méthode Java.
     */
    public HttpMethod inferHttpMethod() {
        String lower = name.toLowerCase();
        if (lower.startsWith("get") || lower.startsWith("find") || lower.startsWith("search")
                || lower.startsWith("list") || lower.startsWith("read") || lower.startsWith("fetch")
                || lower.startsWith("consulter") || lower.startsWith("lire") || lower.startsWith("rechercher")) {
            return HttpMethod.GET;
        }
        if (lower.startsWith("update") || lower.startsWith("modify") || lower.startsWith("edit")
                || lower.startsWith("modifier") || lower.startsWith("maj")) {
            return HttpMethod.PUT;
        }
        if (lower.startsWith("delete") || lower.startsWith("remove") || lower.startsWith("supprimer")
                || lower.startsWith("annuler")) {
            return HttpMethod.DELETE;
        }
        // Par défaut: POST (create, enregistrer, executer, etc.)
        return HttpMethod.POST;
    }

    /**
     * Détermine si la méthode a besoin d'un DTO Request (plus d'un paramètre ou paramètre complexe).
     */
    public boolean needsRequestDto() {
        if (parameters.isEmpty()) return false;
        if (parameters.size() > 1) return true;
        // Un seul paramètre: DTO si c'est un type complexe
        return parameters.get(0).isComplexType();
    }

    /**
     * Détermine si la méthode a besoin d'un DTO Response (retour non-void et non-primitif).
     */
    public boolean needsResponseDto() {
        if (returnType == null || "void".equals(returnType)) return false;
        return !isPrimitiveOrWrapper(returnTypeSimple);
    }

    private boolean isPrimitiveOrWrapper(String type) {
        return List.of("void", "int", "long", "double", "float", "boolean", "byte", "short", "char",
                "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short", "Character",
                "String", "BigDecimal", "BigInteger", "Date", "LocalDate", "LocalDateTime")
                .contains(type);
    }

    private String extractSimpleType(String fullType) {
        if (fullType == null) return "void";
        int lastDot = fullType.lastIndexOf('.');
        return lastDot >= 0 ? fullType.substring(lastDot + 1) : fullType;
    }

    @Override
    public String toString() {
        return "MethodInfo{" + name + "(" + parameters.size() + " params) -> " + returnType + "}";
    }
}
