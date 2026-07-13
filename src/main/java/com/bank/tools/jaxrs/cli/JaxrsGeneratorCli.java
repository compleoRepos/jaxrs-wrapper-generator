package com.bank.tools.jaxrs.cli;

import com.bank.tools.jaxrs.generator.JaxrsProjectGenerator;
import com.bank.tools.jaxrs.model.EjbInfo;
import com.bank.tools.jaxrs.parser.EjbZipParser;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI pour le générateur JAX-RS.
 * Usage: java -jar jaxrs-wrapper-generator.jar --input projet-ejb.zip --output ./output
 */
@Command(
        name = "jaxrs-gen",
        mixinStandardHelpOptions = true,
        version = "1.0.0",
        description = "Génère un projet JAX-RS pur à partir d'un projet EJB (.zip ou répertoire)."
)
public class JaxrsGeneratorCli implements Callable<Integer> {

    @Parameters(index = "0", description = "Chemin vers le projet EJB (.zip ou répertoire)")
    private Path input;

    @Option(names = {"-o", "--output"}, description = "Répertoire de sortie", defaultValue = "./generated-jaxrs")
    private Path output;

    @Option(names = {"-g", "--group-id"}, description = "GroupId Maven du projet généré", defaultValue = "com.bank.api")
    private String groupId;

    @Option(names = {"-a", "--artifact-id"}, description = "ArtifactId Maven du projet généré", defaultValue = "rest-api")
    private String artifactId;

    @Option(names = {"-p", "--package"}, description = "Package de base du code généré", defaultValue = "com.bank.api")
    private String basePackage;

    @Override
    public Integer call() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║       JAX-RS Wrapper Generator v1.0.0           ║");
        System.out.println("╚══════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Input:     " + input);
        System.out.println("Output:    " + output);
        System.out.println("GroupId:   " + groupId);
        System.out.println("Artifact:  " + artifactId);
        System.out.println("Package:   " + basePackage);
        System.out.println();

        // 1. Parser le projet EJB
        EjbZipParser parser = new EjbZipParser();
        List<EjbInfo> ejbs;

        if (input.toString().endsWith(".zip")) {
            ejbs = parser.parse(input);
        } else {
            ejbs = parser.parseDirectory(input);
        }

        if (ejbs.isEmpty()) {
            System.err.println("ERREUR: Aucun EJB détecté dans le projet source.");
            return 1;
        }

        System.out.println("EJBs détectés: " + ejbs.size());
        for (EjbInfo ejb : ejbs) {
            System.out.println("  - " + ejb.getInterfaceName() + " (" + ejb.getMethods().size() + " méthodes)");
        }
        System.out.println();

        // 2. Générer le projet JAX-RS (avec résolution des types DTO)
        JaxrsProjectGenerator generator = new JaxrsProjectGenerator(groupId, artifactId, basePackage);
        generator.generate(ejbs, output, parser.getParsedClassMap());

        System.out.println();
        System.out.println("✓ Projet JAX-RS généré avec succès dans: " + output);
        System.out.println();
        System.out.println("Pour compiler: cd " + output + " && mvn clean package");
        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new JaxrsGeneratorCli()).execute(args);
        System.exit(exitCode);
    }
}
