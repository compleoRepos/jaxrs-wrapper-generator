package com.bank.tools.jaxrs.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Représente un code fonction détecté dans le switch/enum d'un EJB.
 * Chaque code fonction correspond à un endpoint REST à générer.
 * <p>
 * Exemple : dans CommandChequier, le code "enrgCommande" lit les champs
 * flux/numAccount, flux/typeCommand, flux/nbVignettes, etc.
 */
public class FunctionCodeInfo {

    private String code;           // Le code brut (ex: "enrgCommande", "LSTCRTS")
    private String enumValue;      // La valeur enum (ex: "ENRG_COMMANDE", "LSTCRTS")
    private String dispatchPath;   // Le chemin Envelope pour le dispatch (ex: "flux/action", "Flux/FONCTION")
    private List<String> inputFields = new ArrayList<>();   // Champs lus dans ce case
    private List<String> outputFields = new ArrayList<>();  // Champs écrits dans ce case
    private boolean isTestFunction; // true si suffixé par _TST
    private MethodInfo.HttpMethod httpMethod; // GET/POST déduit du nom

    public FunctionCodeInfo() {}

    public FunctionCodeInfo(String code, String enumValue) {
        this.code = code;
        this.enumValue = enumValue;
        this.isTestFunction = code != null && code.toUpperCase().endsWith("_TST");
    }

    // --- Getters & Setters ---

    public String getCode() { return code; }
    public void setCode(String code) {
        this.code = code;
        this.isTestFunction = code != null && code.toUpperCase().endsWith("_TST");
    }

    public String getEnumValue() { return enumValue; }
    public void setEnumValue(String enumValue) { this.enumValue = enumValue; }

    public String getDispatchPath() { return dispatchPath; }
    public void setDispatchPath(String dispatchPath) { this.dispatchPath = dispatchPath; }

    public List<String> getInputFields() { return inputFields; }
    public void setInputFields(List<String> inputFields) { this.inputFields = inputFields; }
    public void addInputField(String field) { this.inputFields.add(field); }

    public List<String> getOutputFields() { return outputFields; }
    public void setOutputFields(List<String> outputFields) { this.outputFields = outputFields; }
    public void addOutputField(String field) { this.outputFields.add(field); }

    public boolean isTestFunction() { return isTestFunction; }
    public void setTestFunction(boolean testFunction) { isTestFunction = testFunction; }

    public MethodInfo.HttpMethod getHttpMethod() { return httpMethod; }
    public void setHttpMethod(MethodInfo.HttpMethod httpMethod) { this.httpMethod = httpMethod; }

    /**
     * Déduit la méthode HTTP depuis le nom du code fonction.
     */
    public MethodInfo.HttpMethod inferHttpMethod() {
        if (code == null) return MethodInfo.HttpMethod.POST;
        String lower = code.toLowerCase();
        if (lower.startsWith("get") || lower.startsWith("lst") || lower.startsWith("list")
                || lower.startsWith("suivi") || lower.startsWith("history")
                || lower.startsWith("consulter") || lower.startsWith("recherch")
                || lower.startsWith("find") || lower.startsWith("search")
                || lower.startsWith("afficher") || lower.contains("liste")) {
            return MethodInfo.HttpMethod.GET;
        }
        if (lower.startsWith("suppr") || lower.startsWith("delete") || lower.startsWith("annul")) {
            return MethodInfo.HttpMethod.DELETE;
        }
        if (lower.startsWith("modif") || lower.startsWith("update") || lower.startsWith("maj")
                || lower.startsWith("activer") || lower.startsWith("desactiver")
                || lower.startsWith("augmenter") || lower.startsWith("affecter")) {
            return MethodInfo.HttpMethod.PUT;
        }
        return MethodInfo.HttpMethod.POST;
    }

    /**
     * Dérive le nom du endpoint REST depuis le code fonction.
     * Ex: "enrgCommande" → "enrg-commande"
     * Ex: "LSTCRTS" → "lstcrts"
     * Ex: "GETHISTORIQUEMODEDEGRADER" → "get-historique-mode-degrader"
     */
    public String deriveEndpointName() {
        if (code == null) return "unknown";
        // Retirer le suffixe _TST pour les fonctions de test
        String base = code;
        if (isTestFunction && base.toUpperCase().endsWith("_TST")) {
            base = base.substring(0, base.length() - 4);
        }
        // CamelCase → kebab-case
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (c == '_') {
                sb.append('-');
            } else if (Character.isUpperCase(c) && i > 0 && !Character.isUpperCase(base.charAt(i - 1))) {
                sb.append('-').append(Character.toLowerCase(c));
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "FunctionCodeInfo{" + code + ", inputs=" + inputFields.size()
                + ", outputs=" + outputFields.size() + ", test=" + isTestFunction + "}";
    }
}
