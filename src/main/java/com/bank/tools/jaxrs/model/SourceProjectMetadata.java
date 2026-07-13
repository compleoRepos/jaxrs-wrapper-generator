package com.bank.tools.jaxrs.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Métadonnées extraites du projet source EJB pour guider la génération.
 * Contient les coordonnées Maven, le parent POM, les bindings JNDI, etc.
 */
public class SourceProjectMetadata {

    // --- Parent POM info ---
    private String parentGroupId;
    private String parentArtifactId;
    private String parentVersion;

    // --- Source project coordinates ---
    private String sourceGroupId;
    private String sourceArtifactId;
    private String sourceVersion;

    // --- EJB module coordinates (for dependency reference) ---
    private String ejbGroupId;
    private String ejbArtifactId;
    private String ejbVersion;

    // --- JNDI bindings from ibm-ejb-jar-bnd.xml ---
    private List<JndiBinding> jndiBindings = new ArrayList<>();

    // --- SCM info ---
    private String scmConnection;

    // --- Properties ---
    private String repositoryName;

    public SourceProjectMetadata() {}

    // --- Inner class for JNDI binding ---
    public static class JndiBinding {
        private String ejbName;
        private String interfaceClass;
        private String bindingName;

        public JndiBinding() {}

        public JndiBinding(String ejbName, String interfaceClass, String bindingName) {
            this.ejbName = ejbName;
            this.interfaceClass = interfaceClass;
            this.bindingName = bindingName;
        }

        public String getEjbName() { return ejbName; }
        public void setEjbName(String ejbName) { this.ejbName = ejbName; }

        public String getInterfaceClass() { return interfaceClass; }
        public void setInterfaceClass(String interfaceClass) { this.interfaceClass = interfaceClass; }

        public String getBindingName() { return bindingName; }
        public void setBindingName(String bindingName) { this.bindingName = bindingName; }

        @Override
        public String toString() {
            return "JndiBinding{" + ejbName + " → " + bindingName + "}";
        }
    }

    // --- Getters & Setters ---

    public String getParentGroupId() { return parentGroupId; }
    public void setParentGroupId(String parentGroupId) { this.parentGroupId = parentGroupId; }

    public String getParentArtifactId() { return parentArtifactId; }
    public void setParentArtifactId(String parentArtifactId) { this.parentArtifactId = parentArtifactId; }

    public String getParentVersion() { return parentVersion; }
    public void setParentVersion(String parentVersion) { this.parentVersion = parentVersion; }

    public String getSourceGroupId() { return sourceGroupId; }
    public void setSourceGroupId(String sourceGroupId) { this.sourceGroupId = sourceGroupId; }

    public String getSourceArtifactId() { return sourceArtifactId; }
    public void setSourceArtifactId(String sourceArtifactId) { this.sourceArtifactId = sourceArtifactId; }

    public String getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(String sourceVersion) { this.sourceVersion = sourceVersion; }

    public String getEjbGroupId() { return ejbGroupId; }
    public void setEjbGroupId(String ejbGroupId) { this.ejbGroupId = ejbGroupId; }

    public String getEjbArtifactId() { return ejbArtifactId; }
    public void setEjbArtifactId(String ejbArtifactId) { this.ejbArtifactId = ejbArtifactId; }

    public String getEjbVersion() { return ejbVersion; }
    public void setEjbVersion(String ejbVersion) { this.ejbVersion = ejbVersion; }

    public List<JndiBinding> getJndiBindings() { return jndiBindings; }
    public void setJndiBindings(List<JndiBinding> jndiBindings) { this.jndiBindings = jndiBindings; }
    public void addJndiBinding(JndiBinding binding) { this.jndiBindings.add(binding); }

    public String getScmConnection() { return scmConnection; }
    public void setScmConnection(String scmConnection) { this.scmConnection = scmConnection; }

    public String getRepositoryName() { return repositoryName; }
    public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }

    /**
     * Retourne le binding JNDI pour un EJB donné, ou null si non trouvé.
     */
    public String getJndiNameForEjb(String ejbName) {
        return jndiBindings.stream()
                .filter(b -> ejbName.equals(b.getEjbName()))
                .map(JndiBinding::getBindingName)
                .findFirst()
                .orElse(null);
    }

    /**
     * Vérifie si les métadonnées du parent POM sont disponibles.
     */
    public boolean hasParentPom() {
        return parentGroupId != null && parentArtifactId != null && parentVersion != null;
    }

    /**
     * Vérifie si les coordonnées EJB sont disponibles.
     */
    public boolean hasEjbCoordinates() {
        return ejbGroupId != null && ejbArtifactId != null;
    }

    @Override
    public String toString() {
        return "SourceProjectMetadata{" +
                "parent=" + parentGroupId + ":" + parentArtifactId + ":" + parentVersion +
                ", ejb=" + ejbGroupId + ":" + ejbArtifactId + ":" + ejbVersion +
                ", bindings=" + jndiBindings.size() +
                "}";
    }
}
