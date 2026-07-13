package com.bank.tools.jaxrs.parser;

import com.bank.tools.jaxrs.model.SourceProjectMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.nio.file.*;
import java.util.stream.Stream;

/**
 * Parse les métadonnées du projet source EJB :
 * - Parent POM (groupId, artifactId, version)
 * - Coordonnées Maven du module EJB
 * - Bindings JNDI depuis ibm-ejb-jar-bnd.xml
 */
public class SourceProjectMetadataParser {

    private static final Logger log = LoggerFactory.getLogger(SourceProjectMetadataParser.class);

    /**
     * Parse les métadonnées depuis un répertoire de projet.
     */
    public SourceProjectMetadata parse(Path projectDir) {
        SourceProjectMetadata metadata = new SourceProjectMetadata();

        // 1. Trouver et parser le POM parent (racine du projet)
        Path rootPom = findRootPom(projectDir);
        if (rootPom != null) {
            parseRootPom(rootPom, metadata);
        }

        // 2. Trouver et parser le POM du module EJB
        Path ejbPom = findEjbModulePom(projectDir);
        if (ejbPom != null) {
            parseEjbPom(ejbPom, metadata);
        }

        // 3. Trouver et parser ibm-ejb-jar-bnd.xml pour les bindings JNDI
        Path bndXml = findIbmEjbJarBnd(projectDir);
        if (bndXml != null) {
            parseIbmEjbJarBnd(bndXml, metadata);
        }

        log.info("Parsed source project metadata: {}", metadata);
        return metadata;
    }

    private Path findRootPom(Path projectDir) {
        // Le POM racine est à la racine du projet
        Path pom = projectDir.resolve("pom.xml");
        if (Files.exists(pom)) return pom;

        // Chercher dans les sous-répertoires (cas où le ZIP contient un dossier racine)
        try (Stream<Path> stream = Files.walk(projectDir, 2)) {
            return stream
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> {
                        // Vérifier que c'est un POM parent (packaging=pom ou a des modules)
                        try {
                            String content = Files.readString(p);
                            return content.contains("<packaging>pom</packaging>")
                                    || content.contains("<modules>");
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.warn("Error searching for root POM: {}", e.getMessage());
            return null;
        }
    }

    private Path findEjbModulePom(Path projectDir) {
        try (Stream<Path> stream = Files.walk(projectDir, 3)) {
            return stream
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> {
                        String parentDir = p.getParent().getFileName().toString();
                        return parentDir.contains("-ejb") || parentDir.endsWith("ejb");
                    })
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.warn("Error searching for EJB POM: {}", e.getMessage());
            return null;
        }
    }

    private Path findIbmEjbJarBnd(Path projectDir) {
        try (Stream<Path> stream = Files.walk(projectDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().equals("ibm-ejb-jar-bnd.xml"))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.warn("Error searching for ibm-ejb-jar-bnd.xml: {}", e.getMessage());
            return null;
        }
    }

    private void parseRootPom(Path pomPath, SourceProjectMetadata metadata) {
        try {
            Document doc = parseXml(pomPath);
            if (doc == null) return;

            Element root = doc.getDocumentElement();

            // Extract parent info
            NodeList parentNodes = root.getElementsByTagName("parent");
            if (parentNodes.getLength() > 0) {
                Element parent = (Element) parentNodes.item(0);
                metadata.setParentGroupId(getTextContent(parent, "groupId"));
                metadata.setParentArtifactId(getTextContent(parent, "artifactId"));
                metadata.setParentVersion(getTextContent(parent, "version"));
            }

            // Extract project coordinates
            String groupId = getDirectTextContent(root, "groupId");
            String artifactId = getDirectTextContent(root, "artifactId");
            String version = getDirectTextContent(root, "version");

            // If groupId/version not specified, inherit from parent
            if (groupId == null && metadata.getParentGroupId() != null) {
                groupId = metadata.getParentGroupId();
            }
            if (version == null && metadata.getParentVersion() != null) {
                version = metadata.getParentVersion();
            }

            metadata.setSourceGroupId(groupId);
            metadata.setSourceArtifactId(artifactId);
            metadata.setSourceVersion(version);

            // Extract SCM info
            NodeList scmNodes = root.getElementsByTagName("scm");
            if (scmNodes.getLength() > 0) {
                Element scm = (Element) scmNodes.item(0);
                metadata.setScmConnection(getTextContent(scm, "connection"));
            }

            log.info("Parsed root POM: {}:{}:{}", groupId, artifactId, version);
        } catch (Exception e) {
            log.warn("Failed to parse root POM {}: {}", pomPath, e.getMessage());
        }
    }

    private void parseEjbPom(Path pomPath, SourceProjectMetadata metadata) {
        try {
            Document doc = parseXml(pomPath);
            if (doc == null) return;

            Element root = doc.getDocumentElement();

            String groupId = getDirectTextContent(root, "groupId");
            String artifactId = getDirectTextContent(root, "artifactId");
            String version = getDirectTextContent(root, "version");

            // Inherit from parent if not specified
            if (groupId == null && metadata.getSourceGroupId() != null) {
                groupId = metadata.getSourceGroupId();
            }
            if (version == null && metadata.getSourceVersion() != null) {
                version = metadata.getSourceVersion();
            }

            metadata.setEjbGroupId(groupId);
            metadata.setEjbArtifactId(artifactId);
            metadata.setEjbVersion(version);

            log.info("Parsed EJB POM: {}:{}:{}", groupId, artifactId, version);
        } catch (Exception e) {
            log.warn("Failed to parse EJB POM {}: {}", pomPath, e.getMessage());
        }
    }

    private void parseIbmEjbJarBnd(Path bndPath, SourceProjectMetadata metadata) {
        try {
            Document doc = parseXml(bndPath);
            if (doc == null) return;

            Element root = doc.getDocumentElement();

            // Parse <session name="..."> elements
            NodeList sessions = root.getElementsByTagName("session");
            for (int i = 0; i < sessions.getLength(); i++) {
                Element session = (Element) sessions.item(i);
                String ejbName = session.getAttribute("name");

                // Parse <interface> elements within the session
                NodeList interfaces = session.getElementsByTagName("interface");
                for (int j = 0; j < interfaces.getLength(); j++) {
                    Element iface = (Element) interfaces.item(j);
                    String className = iface.getAttribute("class");
                    String bindingName = iface.getAttribute("binding-name");

                    if (bindingName != null && !bindingName.isEmpty()) {
                        SourceProjectMetadata.JndiBinding binding =
                                new SourceProjectMetadata.JndiBinding(ejbName, className, bindingName);
                        metadata.addJndiBinding(binding);
                        log.debug("Found JNDI binding: {} → {}", ejbName, bindingName);
                    }
                }
            }

            log.info("Parsed {} JNDI bindings from ibm-ejb-jar-bnd.xml", metadata.getJndiBindings().size());
        } catch (Exception e) {
            log.warn("Failed to parse ibm-ejb-jar-bnd.xml {}: {}", bndPath, e.getMessage());
        }
    }

    private Document parseXml(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(is);
        } catch (Exception e) {
            log.warn("Failed to parse XML {}: {}", path, e.getMessage());
            return null;
        }
    }

    private String getTextContent(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        return null;
    }

    /**
     * Gets direct child text content (not nested elements).
     */
    private String getDirectTextContent(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                Element child = (Element) children.item(i);
                if (child.getTagName().equals(tagName)) {
                    return child.getTextContent().trim();
                }
            }
        }
        return null;
    }
}
