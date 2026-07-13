package com.bank.tools.jaxrs.parser;

import com.bank.tools.jaxrs.model.FunctionCodeInfo;
import com.bank.tools.jaxrs.model.MethodInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse le corps d'une méthode process() d'un EJB SynchroneService pour extraire :
 * <ul>
 *   <li>Le chemin de dispatch (Flux/FONCTION ou flux/action)</li>
 *   <li>Les valeurs de l'enum Action</li>
 *   <li>Le mapping code→valeur enum (depuis le switch dans getAction)</li>
 *   <li>Les champs input lus dans chaque case du switch principal</li>
 * </ul>
 */
public class FunctionCodeParser {

    private static final Logger log = LoggerFactory.getLogger(FunctionCodeParser.class);

    // Pattern pour détecter le chemin de dispatch
    private static final Pattern DISPATCH_PATH_PATTERN = Pattern.compile(
            "getNodeAsString\\(\\s*(?:\"([^\"]+)\"|([A-Z_]+))\\s*\\)");

    // Pattern pour enum Action { VAL1, VAL2, VAL3 }
    private static final Pattern ENUM_PATTERN = Pattern.compile(
            "(?:private\\s+)?enum\\s+\\w+\\s*\\{([^}]+)\\}");

    // Pattern pour case "stringValue": ... action = Action.ENUM_VAL
    private static final Pattern CASE_MAPPING_PATTERN = Pattern.compile(
            "case\\s+\"([^\"]+)\"\\s*:.*?(?:action\\s*=\\s*\\w+\\.(\\w+)|Action\\.(\\w+))",
            Pattern.DOTALL);

    // Pattern pour getNodeAsString("path") dans un case block
    private static final Pattern GET_NODE_PATTERN = Pattern.compile(
            "getNodeAsString\\(\\s*\"([^\"]+)\"\\s*\\)");

    // Pattern pour addNode("path", ...) in output
    private static final Pattern ADD_NODE_PATTERN = Pattern.compile(
            "(?:addNode|ecrireString)\\([^,]*,\\s*[^,]*,\\s*\"([^\"]+)\"");

    // Pattern for constants like FLUX_FONCTION = "Flux/FONCTION"
    private static final Pattern CONSTANT_DEF_PATTERN = Pattern.compile(
            "(?:static\\s+)?(?:final\\s+)?String\\s+(\\w+)\\s*=\\s*\"([^\"]+)\"");

    /**
     * Parse le corps d'une méthode process() et extrait les codes fonction.
     *
     * @param methodBody   le corps complet de la méthode process()
     * @param fullClassBody le corps complet de la classe (pour résoudre les constantes et l'enum)
     * @return liste des codes fonction détectés
     */
    public List<FunctionCodeInfo> parse(String methodBody, String fullClassBody) {
        if (methodBody == null || methodBody.isBlank()) {
            return Collections.emptyList();
        }

        String classSource = fullClassBody != null ? fullClassBody : methodBody;

        // 1. Résoudre les constantes définies dans la classe
        Map<String, String> constants = extractConstants(classSource);

        // 2. Détecter le chemin de dispatch
        String dispatchPath = detectDispatchPath(methodBody, constants);
        log.debug("Detected dispatch path: {}", dispatchPath);

        // 3. Extraire les valeurs de l'enum
        List<String> enumValues = extractEnumValues(classSource);
        log.debug("Enum values: {}", enumValues);

        // 4. Extraire le mapping code→enum depuis getAction() ou le switch direct
        Map<String, String> codeToEnum = extractCodeToEnumMapping(classSource);
        log.debug("Code to enum mapping: {}", codeToEnum);

        // 5. Si pas de mapping explicite, utiliser les enum values directement comme codes
        if (codeToEnum.isEmpty() && !enumValues.isEmpty()) {
            for (String ev : enumValues) {
                codeToEnum.put(ev, ev);
            }
        }

        // 6. Extraire les champs input par case dans le switch principal
        Map<String, List<String>> inputFieldsByCase = extractFieldsByCase(methodBody, constants);

        // 6b. Extraire les champs output par case (addNode/ecrireString)
        Map<String, List<String>> outputFieldsByCase = extractOutputFieldsByCase(methodBody, constants);

        // 7. Construire les FunctionCodeInfo
        List<FunctionCodeInfo> result = new ArrayList<>();

        if (!codeToEnum.isEmpty()) {
            for (Map.Entry<String, String> entry : codeToEnum.entrySet()) {
                String code = entry.getKey();
                String enumVal = entry.getValue();

                FunctionCodeInfo fci = new FunctionCodeInfo(code, enumVal);
                fci.setDispatchPath(dispatchPath);

                // Trouver les champs pour ce case
                List<String> fields = inputFieldsByCase.get(enumVal);
                if (fields == null) {
                    fields = inputFieldsByCase.get(code);
                }
                if (fields != null) {
                    fci.setInputFields(fields);
                }

                // Output fields
                List<String> outFields = outputFieldsByCase.get(enumVal);
                if (outFields == null) {
                    outFields = outputFieldsByCase.get(code);
                }
                if (outFields != null) {
                    fci.setOutputFields(outFields);
                }

                fci.setHttpMethod(fci.inferHttpMethod());
                result.add(fci);
            }
        }

        // 8. Si aucun code trouvé mais il y a des getNodeAsString dans le body,
        //    créer un seul FunctionCodeInfo "default"
        if (result.isEmpty()) {
            List<String> allFields = new ArrayList<>();
            Matcher m = GET_NODE_PATTERN.matcher(methodBody);
            while (m.find()) {
                String path = m.group(1);
                if (!path.equals(dispatchPath) && !allFields.contains(path)) {
                    allFields.add(path);
                }
            }
            if (!allFields.isEmpty()) {
                FunctionCodeInfo fci = new FunctionCodeInfo("process", "PROCESS");
                fci.setDispatchPath(dispatchPath);
                fci.setInputFields(allFields);
                fci.setHttpMethod(MethodInfo.HttpMethod.POST);
                result.add(fci);
            }
        }

        log.info("Parsed {} function codes from process() body", result.size());
        return result;
    }

    private Map<String, String> extractConstants(String source) {
        Map<String, String> constants = new HashMap<>();
        Matcher m = CONSTANT_DEF_PATTERN.matcher(source);
        while (m.find()) {
            constants.put(m.group(1), m.group(2));
        }
        return constants;
    }

    private String detectDispatchPath(String methodBody, Map<String, String> constants) {
        // Look for patterns like: getNodeAsString(FLUX_FONCTION) or getNodeAsString("flux/action")
        // The dispatch path is typically the first getNodeAsString call that feeds into a switch/valueOf
        Pattern dispatchUsage = Pattern.compile(
                "(?:valueOf|getAction)\\s*\\(.*?getNodeAsString\\(\\s*(?:\"([^\"]+)\"|([A-Z_]+))\\s*\\)");
        Matcher m = dispatchUsage.matcher(methodBody);
        if (m.find()) {
            if (m.group(1) != null) return m.group(1);
            if (m.group(2) != null) return constants.getOrDefault(m.group(2), m.group(2));
        }

        // Fallback: look for getNodeAsString right before valueOf/switch
        Pattern fallback = Pattern.compile(
                "getNodeAsString\\(\\s*(?:\"([^\"]+)\"|([A-Z_]+))\\s*\\)\\s*(?:\\.toString\\(\\))?\\s*(?:\\.toUpperCase\\(\\))?");
        m = fallback.matcher(methodBody);
        while (m.find()) {
            String path = m.group(1) != null ? m.group(1) : constants.getOrDefault(m.group(2), m.group(2));
            if (path != null && (path.toLowerCase().contains("action") || path.toLowerCase().contains("fonction"))) {
                return path;
            }
        }

        return "Flux/FONCTION"; // default
    }

    private List<String> extractEnumValues(String source) {
        Matcher m = ENUM_PATTERN.matcher(source);
        if (m.find()) {
            String body = m.group(1).trim();
            List<String> values = new ArrayList<>();
            for (String val : body.split("[,\\s]+")) {
                String trimmed = val.trim();
                if (!trimmed.isEmpty() && !trimmed.contains("(") && !trimmed.contains("{")) {
                    values.add(trimmed);
                }
            }
            return values;
        }
        return Collections.emptyList();
    }

    private Map<String, String> extractCodeToEnumMapping(String source) {
        Map<String, String> mapping = new LinkedHashMap<>();

        // Pattern: case "codeValue": ... action = Action.ENUM_VALUE; break;
        // or: case "codeValue": action = Action.ENUM_VALUE; break;
        Pattern casePattern = Pattern.compile(
                "case\\s+\"([^\"]+)\"\\s*:(.*?)(?=case\\s+\"|default\\s*:|\\})",
                Pattern.DOTALL);

        Matcher m = casePattern.matcher(source);
        while (m.find()) {
            String codeValue = m.group(1);
            String caseBody = m.group(2);

            // Find the enum assignment
            Pattern enumAssign = Pattern.compile("(?:action|act)\\s*=\\s*\\w+\\.(\\w+)");
            Matcher em = enumAssign.matcher(caseBody);
            if (em.find()) {
                mapping.put(codeValue, em.group(1));
            } else {
                // If no enum assignment, use the code itself as enum value
                mapping.put(codeValue, codeValue.toUpperCase().replace("-", "_"));
            }
        }

        return mapping;
    }

    private Map<String, List<String>> extractFieldsByCase(String methodBody, Map<String, String> constants) {
        Map<String, List<String>> result = new LinkedHashMap<>();

        // Split by case statements in the main switch
        Pattern casePattern = Pattern.compile(
                "case\\s+(\\w+)\\s*:(.*?)(?=case\\s+\\w+\\s*:|default\\s*:|\\}\\s*$)",
                Pattern.DOTALL);

        Matcher m = casePattern.matcher(methodBody);
        while (m.find()) {
            String caseName = m.group(1);
            String caseBody = m.group(2);

            List<String> fields = new ArrayList<>();
            Matcher fieldMatcher = GET_NODE_PATTERN.matcher(caseBody);
            while (fieldMatcher.find()) {
                String path = fieldMatcher.group(1);
                if (!fields.contains(path)) {
                    fields.add(path);
                }
            }

            // Also resolve constant references
            Pattern constRef = Pattern.compile("getNodeAsString\\(\\s*([A-Z_]+)\\s*\\)");
            Matcher constMatcher = constRef.matcher(caseBody);
            while (constMatcher.find()) {
                String constName = constMatcher.group(1);
                String resolved = constants.get(constName);
                if (resolved != null && !fields.contains(resolved)) {
                    fields.add(resolved);
                }
            }

            if (!fields.isEmpty()) {
                result.put(caseName, fields);
            }
        }

        return result;
    }

    /**
     * Extrait les champs output (addNode/ecrireString) par case dans le switch.
     */
    private Map<String, List<String>> extractOutputFieldsByCase(String methodBody, Map<String, String> constants) {
        Map<String, List<String>> result = new LinkedHashMap<>();

        Pattern casePattern = Pattern.compile(
                "case\\s+(\\w+)\\s*:(.*?)(?=case\\s+\\w+\\s*:|default\\s*:|\\}\\s*$)",
                Pattern.DOTALL);

        Matcher m = casePattern.matcher(methodBody);
        while (m.find()) {
            String caseName = m.group(1);
            String caseBody = m.group(2);

            List<String> fields = new ArrayList<>();

            // addNode("path", value) pattern
            Pattern addNodePat = Pattern.compile("addNode\\(\\s*\"([^\"]+)\"\\s*,");
            Matcher addMatcher = addNodePat.matcher(caseBody);
            while (addMatcher.find()) {
                String path = addMatcher.group(1);
                // Skip dispatch paths and code/message (already handled)
                if (!path.equals("flux/code") && !path.equals("flux/message")
                        && !path.equals("Flux/CODE") && !path.equals("Flux/MESSAGE")
                        && !fields.contains(path)) {
                    fields.add(path);
                }
            }

            // ecrireString(env, value, "path") pattern
            Matcher writeMatcher = ADD_NODE_PATTERN.matcher(caseBody);
            while (writeMatcher.find()) {
                String path = writeMatcher.group(1);
                if (!path.equals("code") && !path.equals("message") && !fields.contains(path)) {
                    fields.add(path);
                }
            }

            if (!fields.isEmpty()) {
                result.put(caseName, fields);
            }
        }

        return result;
    }
}
