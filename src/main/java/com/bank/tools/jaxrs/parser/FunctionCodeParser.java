package com.bank.tools.jaxrs.parser;

import com.bank.tools.jaxrs.model.FunctionCodeInfo;
import com.bank.tools.jaxrs.model.MethodInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
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

        // Determine if the class has an Action enum — if so, only accept cases that assign to it
        boolean hasActionEnum = ENUM_PATTERN.matcher(source).find();

        // Try to find the getAction() method body first (scoped extraction)
        String searchScope = extractGetActionMethodBody(source);
        if (searchScope == null) {
            // Fallback: search the full source but be strict about enum assignment
            searchScope = source;
        }

        // Pattern: case "codeValue": ... action = Action.ENUM_VALUE; break;
        Pattern casePattern = Pattern.compile(
                "case\\s+\"([^\"]+)\"\\s*:(.*?)(?=case\\s+\"|default\\s*:|\\})",
                Pattern.DOTALL);

        Matcher m = casePattern.matcher(searchScope);
        while (m.find()) {
            String codeValue = m.group(1);
            String caseBody = m.group(2);

            // Find the enum assignment
            Pattern enumAssign = Pattern.compile("(?:action|act)\\s*=\\s*\\w+\\.(\\w+)");
            Matcher em = enumAssign.matcher(caseBody);
            if (em.find()) {
                mapping.put(codeValue, em.group(1));
            } else if (!hasActionEnum) {
                // Only use the code itself as enum value when there is NO Action enum in the class.
                // If an Action enum exists, cases without enum assignment are secondary switches
                // (e.g., canal type "A"/"G"/"I"/"M") and should be ignored.
                mapping.put(codeValue, codeValue.toUpperCase().replace("-", "_"));
            }
        }

        return mapping;
    }

    /**
     * Extract the body of a getAction() or similar method that maps string codes to enum values.
     * Returns null if no such method is found.
     */
    private String extractGetActionMethodBody(String source) {
        // Look for methods like: Action getAction(String ...) { ... }
        Pattern getActionPattern = Pattern.compile(
                "(?:public|private|protected)?\\s*\\w+\\s+get\\w*[Aa]ction\\s*\\([^)]*\\)\\s*\\{",
                Pattern.MULTILINE);
        Matcher m = getActionPattern.matcher(source);
        if (m.find()) {
            int braceStart = m.end() - 1;
            int depth = 1;
            int i = braceStart + 1;
            while (i < source.length() && depth > 0) {
                char c = source.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                i++;
            }
            if (depth == 0) {
                return source.substring(braceStart, i);
            }
        }
        return null;
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
     * Strategy 2: Parse function codes when the enum and/or switch are in external classes.
     * Handles patterns like:
     * - fatourati: process() delegates to Traitement(), enum Action in same class, dispatch via Utility class
     * - virement: process() calls Utilities.getServiceName(), enum ActionWeb in separate file with constructor values
     *
     * @param processBody    the process() method body
     * @param classBody      the full class source
     * @param allClassBodies map of className → full source for all classes in the project
     * @return list of function codes detected
     */
    public List<FunctionCodeInfo> parseWithExternalClasses(String processBody, String classBody, Map<String, String> allClassBodies) {
        if (processBody == null || processBody.isBlank()) {
            return Collections.emptyList();
        }

        // Merge all constants from the main class and all external classes
        Map<String, String> allConstants = new HashMap<>();
        allConstants.putAll(extractConstants(classBody != null ? classBody : ""));
        for (String extSource : allClassBodies.values()) {
            allConstants.putAll(extractConstants(extSource));
        }

        // Step 1: Find the dispatch path from process() or delegated methods
        String dispatchPath = detectDispatchPathExternal(processBody, classBody, allClassBodies, allConstants);
        log.debug("[External] Detected dispatch path: {}", dispatchPath);

        // Step 2: Find the switch body - either in process() or in a delegated method
        String switchBody = findSwitchBody(processBody, classBody);
        if (switchBody == null || switchBody.isBlank()) {
            log.debug("[External] No switch found in process() or delegated methods");
            return Collections.emptyList();
        }

        // Step 3: Find enum references in the switch to identify the enum class
        String enumClassName = detectEnumClassName(switchBody, allClassBodies);
        if (enumClassName == null) {
            log.debug("[External] No enum class reference found in switch");
            return Collections.emptyList();
        }
        log.debug("[External] Enum class: {}", enumClassName);

        // Step 4: Find the enum source (in classBody or external)
        String enumSource = null;
        if (classBody != null && classBody.contains("enum " + enumClassName)) {
            enumSource = classBody;
        } else {
            enumSource = allClassBodies.get(enumClassName);
        }
        if (enumSource == null) {
            log.debug("[External] Enum source not found for: {}", enumClassName);
            return Collections.emptyList();
        }

        // Step 5: Extract enum values - detect if constructor-valued or simple
        List<String> functionCodes = extractEnumFunctionCodes(enumSource, enumClassName);
        log.debug("[External] Extracted {} function codes from enum {}", functionCodes.size(), enumClassName);

        if (functionCodes.isEmpty()) {
            return Collections.emptyList();
        }

        // Step 6: Build FunctionCodeInfo for each code
        // Try to extract input/output fields from the switch cases
        Map<String, List<String>> inputFieldsByCase = extractFieldsByCase(switchBody, allConstants);
        Map<String, List<String>> outputFieldsByCase = extractOutputFieldsByCase(switchBody, allConstants);

        List<FunctionCodeInfo> result = new ArrayList<>();
        for (String code : functionCodes) {
            FunctionCodeInfo fci = new FunctionCodeInfo(code, code.toUpperCase().replace("-", "_"));
            fci.setDispatchPath(dispatchPath);

            // Try to find fields for this case (match by enum constant name or code value)
            String enumConstant = findEnumConstantForCode(enumSource, enumClassName, code);
            List<String> inputs = inputFieldsByCase.get(enumConstant);
            if (inputs == null) inputs = inputFieldsByCase.get(code);
            if (inputs != null) fci.setInputFields(inputs);

            List<String> outputs = outputFieldsByCase.get(enumConstant);
            if (outputs == null) outputs = outputFieldsByCase.get(code);
            if (outputs != null) fci.setOutputFields(outputs);

            fci.setHttpMethod(fci.inferHttpMethod());
            result.add(fci);
        }

        log.info("[External] Parsed {} function codes from external enum {}", result.size(), enumClassName);
        return result;
    }

    /**
     * Detect dispatch path by looking in process(), delegated methods, and utility classes.
     */
    private String detectDispatchPathExternal(String processBody, String classBody,
                                              Map<String, String> allClassBodies, Map<String, String> allConstants) {
        // First try in process() itself
        String path = detectDispatchPath(processBody, allConstants);
        if (path != null && !path.equals("Flux/FONCTION")) {
            return path;
        }

        // Look in the full class body (other methods like Traitement)
        if (classBody != null) {
            // Find getNodeAsString calls that feed into valueOf/fromValue
            Pattern utilityCall = Pattern.compile(
                    "(?:valueOf|fromValue)\\(.*?getNodeAsString\\(\\s*(?:\"([^\"]+)\"|([A-Z_]+))\\s*\\)");
            Matcher m = utilityCall.matcher(classBody);
            if (m.find()) {
                if (m.group(1) != null) return m.group(1);
                if (m.group(2) != null) return allConstants.getOrDefault(m.group(2), m.group(2));
            }
        }

        // Look in external utility classes
        for (String extSource : allClassBodies.values()) {
            Pattern utilityCall = Pattern.compile(
                    "(?:valueOf|fromValue)\\(.*?getNodeAsString\\(\\s*(?:\"([^\"]+)\"|([A-Z_]+))\\s*\\)");
            Matcher m = utilityCall.matcher(extSource);
            if (m.find()) {
                if (m.group(1) != null) return m.group(1);
                if (m.group(2) != null) {
                    // Resolve constant from the same external class
                    Map<String, String> extConstants = extractConstants(extSource);
                    String resolved = extConstants.get(m.group(2));
                    if (resolved != null) return resolved;
                    return allConstants.getOrDefault(m.group(2), m.group(2));
                }
            }

            // Also look for getNodeAsString feeding into a variable that's used with valueOf
            Pattern directRead = Pattern.compile(
                    "getNodeAsString\\(\\s*(?:\"([^\"]+)\"|([A-Z_]+))\\s*\\)");
            Matcher dm = directRead.matcher(extSource);
            while (dm.find()) {
                String p = dm.group(1) != null ? dm.group(1) : allConstants.getOrDefault(dm.group(2), dm.group(2));
                if (p != null && (p.contains("action") || p.contains("Action") || p.contains("fonction") || p.contains("FONCTION"))) {
                    return p;
                }
            }
        }

        return "flux/action"; // default for external dispatch
    }

    /**
     * Find the switch body - either directly in process() or in a delegated method.
     */
    private String findSwitchBody(String processBody, String classBody) {
        // Check if process() itself has a switch
        if (processBody.contains("switch")) {
            return processBody;
        }

        // Look for method calls in process() that might contain the switch
        // Pattern: methodName(envIn) or methodName(envelope)
        Pattern methodCall = Pattern.compile("(?:=\\s*)?([A-Z]\\w+)\\s*\\(\\s*(?:envIn|envelope|env)\\s*\\)");
        Matcher m = methodCall.matcher(processBody);
        while (m.find()) {
            String methodName = m.group(1);
            // Find this method in the class body
            if (classBody != null) {
                Pattern methodDef = Pattern.compile(
                        "(?:public|private|protected)\\s+\\w+\\s+" + Pattern.quote(methodName) + "\\s*\\([^)]*\\)\\s*(?:throws[^{]*)?\\{(.*)",
                        Pattern.DOTALL);
                Matcher mm = methodDef.matcher(classBody);
                if (mm.find()) {
                    String body = extractMethodBody(mm.group(1));
                    if (body != null && body.contains("switch")) {
                        return body;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Extract balanced method body from the start of a method (after the opening brace).
     */
    private String extractMethodBody(String afterOpenBrace) {
        int depth = 1;
        int i = 0;
        while (i < afterOpenBrace.length() && depth > 0) {
            char c = afterOpenBrace.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            i++;
        }
        return i > 0 ? afterOpenBrace.substring(0, i - 1) : null;
    }

    /**
     * Detect the enum class name from switch case references.
     */
    private String detectEnumClassName(String switchBody) {
        return detectEnumClassName(switchBody, null);
    }

    /**
     * Detect the enum class name from switch case references, with access to all class bodies.
     */
    private String detectEnumClassName(String switchBody, Map<String, String> allClassBodies) {
        // Pattern 1: case EnumClass.CONSTANT or EnumClass.CONSTANT:
        Pattern enumRef = Pattern.compile("case\\s+(\\w+)\\.(\\w+)\\s*:");
        Matcher m = enumRef.matcher(switchBody);
        if (m.find()) {
            return m.group(1);
        }

        // Pattern 2: switch (variable) where variable is typed as EnumClass
        Pattern switchVar = Pattern.compile("switch\\s*\\(\\s*(\\w+)\\s*\\)");
        Matcher sv = switchVar.matcher(switchBody);
        if (sv.find()) {
            String varName = sv.group(1);
            // Find type declaration: EnumClass varName = ...
            Pattern typeDecl = Pattern.compile("(\\w+)\\s+" + Pattern.quote(varName) + "\\s*=");
            Matcher td = typeDecl.matcher(switchBody);
            if (td.find()) {
                String typeName = td.group(1);
                if (!typeName.equals("String") && !typeName.equals("int") && !typeName.equals("var")) {
                    return typeName;
                }
            }
        }

        // Pattern 3: bare ALL_CAPS enum constants - collect case labels and search allClassBodies for matching enum
        if (allClassBodies != null && !allClassBodies.isEmpty()) {
            Pattern bareCase = Pattern.compile("case\\s+([A-Z][A-Z0-9_]+)\\s*:");
            Matcher bc = bareCase.matcher(switchBody);
            Set<String> caseLabels = new LinkedHashSet<>();
            while (bc.find()) {
                caseLabels.add(bc.group(1));
            }
            if (!caseLabels.isEmpty()) {
                // Search all class bodies for an enum that contains at least 2 of these labels
                for (Map.Entry<String, String> entry : allClassBodies.entrySet()) {
                    String className = entry.getKey();
                    String source = entry.getValue();
                    if (source.contains("enum " + className) || source.contains("enum\t" + className)) {
                        int matchCount = 0;
                        for (String label : caseLabels) {
                            if (source.contains(label)) matchCount++;
                        }
                        if (matchCount >= 2 || (matchCount >= 1 && caseLabels.size() <= 2)) {
                            log.debug("[detectEnumClassName] Found enum {} matching {} of {} case labels",
                                    className, matchCount, caseLabels.size());
                            return className;
                        }
                    }
                }
                // Also search in classBody for inner enums
                // Pattern: enum SomeName { LABEL1, LABEL2 }
                Pattern innerEnum = Pattern.compile("enum\\s+(\\w+)\\s*\\{([^}]+)\\}");
                for (Map.Entry<String, String> entry : allClassBodies.entrySet()) {
                    Matcher ie = innerEnum.matcher(entry.getValue());
                    while (ie.find()) {
                        String enumName = ie.group(1);
                        String enumBody = ie.group(2);
                        int matchCount = 0;
                        for (String label : caseLabels) {
                            if (enumBody.contains(label)) matchCount++;
                        }
                        if (matchCount >= 2 || (matchCount >= 1 && caseLabels.size() <= 2)) {
                            log.debug("[detectEnumClassName] Found inner enum {} matching {} labels", enumName, matchCount);
                            return enumName;
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Extract function codes from an enum source.
     * Handles both simple enums (CONST1, CONST2) and constructor-valued enums (CONST1("value1")).
     */
    private List<String> extractEnumFunctionCodes(String enumSource, String enumClassName) {
        List<String> codes = new ArrayList<>();

        // Find the enum body
        Pattern enumBodyPat = Pattern.compile(
                "enum\\s+" + Pattern.quote(enumClassName) + "\\s*\\{([^;]*(?:;|\\}))",
                Pattern.DOTALL);
        Matcher m = enumBodyPat.matcher(enumSource);
        if (!m.find()) {
            // Try simpler pattern
            Pattern simple = Pattern.compile("enum\\s+" + Pattern.quote(enumClassName) + "\\s*\\{([^}]+)\\}", Pattern.DOTALL);
            m = simple.matcher(enumSource);
            if (!m.find()) return codes;
        }

        String enumBody = m.group(1).trim();
        // Remove trailing semicolon if present
        if (enumBody.endsWith(";")) enumBody = enumBody.substring(0, enumBody.length() - 1).trim();

        // Check if constructor-valued: look for pattern CONST("value")
        boolean isConstructorValued = enumBody.contains("(\"");

        if (isConstructorValued) {
            // Extract constructor values: CONST_NAME("actualValue")
            Pattern constPat = Pattern.compile("(\\w+)\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");
            Matcher cm = constPat.matcher(enumBody);
            while (cm.find()) {
                codes.add(cm.group(2)); // Use the constructor value as the function code
            }
        } else {
            // Simple enum: constant names are the codes
            // First, strip single-line comments (// ...) to handle inline comments after commas
            String cleanedBody = enumBody.replaceAll("//[^\\n]*", "");
            for (String line : cleanedBody.split(",")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("@")) continue;
                // Extract just the constant name (first word token)
                Pattern constName = Pattern.compile("^([A-Z]\\w*)");
                Matcher cn = constName.matcher(trimmed);
                if (cn.find()) {
                    String name = cn.group(1);
                    if (!name.isEmpty() && !name.equals("private") && !name.equals("public")) {
                        codes.add(name);
                    }
                }
            }
        }

        return codes;
    }

    /**
     * Find the enum constant name that corresponds to a given function code value.
     * For simple enums, the constant name IS the code.
     * For constructor-valued enums, we need to find which constant has that value.
     */
    private String findEnumConstantForCode(String enumSource, String enumClassName, String code) {
        // Check if it's a constructor-valued enum
        Pattern constPat = Pattern.compile("(\\w+)\\s*\\(\\s*\"" + Pattern.quote(code) + "\"\\s*\\)");
        Matcher m = constPat.matcher(enumSource);
        if (m.find()) {
            return m.group(1); // Return the constant name
        }
        // For simple enums, the code IS the constant name
        return code;
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
