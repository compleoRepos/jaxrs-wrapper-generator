package com.bank.tools.jaxrs.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Représente un EJB parsé depuis le projet source.
 * Contient le nom de l'interface, le JNDI name déduit, et les méthodes exposées.
 */
public class EjbInfo {

    private String interfaceName;
    private String implementationName;
    private String packageName;
    private String jndiName;
    private EjbType ejbType;
    private List<MethodInfo> methods = new ArrayList<>();

    public enum EjbType {
        STATELESS, STATEFUL, SINGLETON, MESSAGE_DRIVEN
    }

    public EjbInfo() {}

    public EjbInfo(String interfaceName, String packageName) {
        this.interfaceName = interfaceName;
        this.packageName = packageName;
    }

    // --- Getters & Setters ---

    public String getInterfaceName() { return interfaceName; }
    public void setInterfaceName(String interfaceName) { this.interfaceName = interfaceName; }

    public String getImplementationName() { return implementationName; }
    public void setImplementationName(String implementationName) { this.implementationName = implementationName; }

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public String getJndiName() { return jndiName; }
    public void setJndiName(String jndiName) { this.jndiName = jndiName; }

    public EjbType getEjbType() { return ejbType; }
    public void setEjbType(EjbType ejbType) { this.ejbType = ejbType; }

    public List<MethodInfo> getMethods() { return methods; }
    public void setMethods(List<MethodInfo> methods) { this.methods = methods; }

    public void addMethod(MethodInfo method) { this.methods.add(method); }

    /**
     * Déduit le nom REST resource à partir de l'interface.
     * Ex: "CommandeChequierService" → "commande-chequier"
     */
    public String deriveResourceName() {
        String name = interfaceName;
        // Retirer les suffixes courants
        for (String suffix : List.of("Service", "Remote", "Local", "Bean", "EJB", "Facade")) {
            if (name.endsWith(suffix)) {
                name = name.substring(0, name.length() - suffix.length());
                break;
            }
        }
        // CamelCase → kebab-case
        return camelToKebab(name);
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

    @Override
    public String toString() {
        return "EjbInfo{" + interfaceName + ", methods=" + methods.size() + "}";
    }
}
