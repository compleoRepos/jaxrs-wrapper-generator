package ma.eai.commons.services.parsing;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Stub de la classe Envelope utilisée par les EJBs.
 * Simule le comportement XML Envelope pour la compilation des projets générés.
 */
public class Envelope implements Serializable {

    private static final long serialVersionUID = 1L;
    private final Map<String, String> nodes = new HashMap<String, String>();
    private String body;

    public Envelope() {
    }

    public void addNode(String path, String value) {
        nodes.put(path, value);
    }

    public String getNodeAsString(String path) {
        return nodes.get(path);
    }

    public String getLNodeAsString(String path) {
        return nodes.get(path);
    }

    public int getNodeAsInt(String path) {
        String val = nodes.get(path);
        if (val == null || val.isEmpty()) return 0;
        return Integer.parseInt(val);
    }

    public long getNodeAsLong(String path) {
        String val = nodes.get(path);
        if (val == null || val.isEmpty()) return 0L;
        return Long.parseLong(val);
    }

    public double getNodeAsDouble(String path) {
        String val = nodes.get(path);
        if (val == null || val.isEmpty()) return 0.0;
        return Double.parseDouble(val);
    }

    public boolean getNodeAsBoolean(String path) {
        String val = nodes.get(path);
        return "true".equalsIgnoreCase(val) || "1".equals(val);
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getBody() {
        return body;
    }

    public Map<String, String> getAllNodes() {
        return nodes;
    }
}
