# Guide d'installation et d'utilisation

## Table des matières

1. [Prérequis](#prérequis)
2. [Installation](#installation)
3. [Utilisation en ligne de commande](#utilisation-en-ligne-de-commande)
4. [Utilisation comme dépendance Maven](#utilisation-comme-dépendance-maven)
5. [Structure du projet généré](#structure-du-projet-généré)
6. [Déploiement du projet généré](#déploiement-du-projet-généré)
7. [Exemples concrets](#exemples-concrets)
8. [Dépannage](#dépannage)

---

## Prérequis

### Environnement de build

| Composant | Version minimale | Vérification |
|-----------|:---:|---|
| JDK | 17+ | `java -version` |
| Maven | 3.8+ | `mvn -version` |
| Git | 2.x | `git --version` |

### Vérification rapide

```bash
# Vérifier Java
java -version
# Attendu: openjdk version "17.x.x" ou supérieur

# Vérifier Maven
mvn -version
# Attendu: Apache Maven 3.8.x ou supérieur

# Vérifier Git
git --version
```

> **Note** : Le JDK 17 est le minimum requis car le projet utilise les text blocks, les records et les sealed classes de Java 17.

---

## Installation

### Étape 1 : Cloner le dépôt

```bash
git clone https://github.com/compleoRepos/jaxrs-wrapper-generator.git
cd jaxrs-wrapper-generator
```

### Étape 2 : Compiler le projet

```bash
mvn clean package
```

Cette commande :
- Compile le code source
- Exécute les tests unitaires (50 tests)
- Produit le JAR exécutable via le plugin `maven-shade-plugin`

Le JAR final se trouve dans :
```
target/jaxrs-wrapper-generator-1.0.0-SNAPSHOT.jar
```

### Étape 3 (optionnel) : Installer dans le repository Maven local

```bash
mvn clean install
```

Cela permet d'utiliser le générateur comme dépendance Maven dans d'autres projets.

### Étape 4 (optionnel) : Créer un alias

```bash
# Linux/macOS
alias jaxrs-gen="java -jar $(pwd)/target/jaxrs-wrapper-generator-1.0.0-SNAPSHOT.jar"

# Windows (PowerShell)
Set-Alias jaxrs-gen "java -jar $PWD\target\jaxrs-wrapper-generator-1.0.0-SNAPSHOT.jar"
```

---

## Utilisation en ligne de commande

### Syntaxe

```bash
java -jar jaxrs-wrapper-generator-1.0.0-SNAPSHOT.jar <input> [options]
```

### Paramètres et options

| Paramètre/Option | Description | Obligatoire | Défaut |
|---|---|:---:|---|
| `<input>` | Chemin vers le projet EJB source (`.zip` ou répertoire) | Oui | — |
| `-o, --output` | Répertoire de sortie pour le projet JAX-RS généré | Non | `./generated-jaxrs` |
| `-g, --group-id` | GroupId Maven du projet généré | Non | `com.bank.api` |
| `-a, --artifact-id` | ArtifactId Maven du projet généré | Non | `rest-api` |
| `-p, --package` | Package de base du code Java généré | Non | `com.bank.api` |
| `-h, --help` | Affiche l'aide | Non | — |
| `-V, --version` | Affiche la version | Non | — |

### Exemples d'utilisation

#### Transformer un projet EJB depuis un fichier ZIP

```bash
java -jar target/jaxrs-wrapper-generator-1.0.0-SNAPSHOT.jar \
  /chemin/vers/commande-chequier-bmcedirect.zip \
  -o /chemin/vers/sortie/commande-chequier-rest \
  -g ma.eai.boa.xbanking \
  -a commande-chequier-rest \
  -p ma.eai.boa.xbanking
```

#### Transformer un projet EJB depuis un répertoire

```bash
java -jar target/jaxrs-wrapper-generator-1.0.0-SNAPSHOT.jar \
  /chemin/vers/virement-permanent-bmcedirect/ \
  -o ./output/virement-permanent-rest \
  -g ma.eai.boa.xbanking \
  -a virement-permanent-rest \
  -p ma.eai.boa.xbanking
```

#### Transformer un projet JAX-WS (SOAP)

```bash
java -jar target/jaxrs-wrapper-generator-1.0.0-SNAPSHOT.jar \
  /chemin/vers/interface-credit-jocker/ \
  -o ./output/credit-jocker-rest \
  -g ma.eai.boa.xbanking \
  -a credit-jocker-rest \
  -p ma.eai.boa.xbanking
```

#### Afficher l'aide

```bash
java -jar target/jaxrs-wrapper-generator-1.0.0-SNAPSHOT.jar --help
```

### Sortie console attendue

```
╔══════════════════════════════════════════════════╗
║       JAX-RS Wrapper Generator v1.0.0           ║
╚══════════════════════════════════════════════════╝

Input:     /chemin/vers/commande-chequier-bmcedirect
Output:    ./output/commande-chequier-rest
GroupId:   ma.eai.boa.xbanking
Artifact:  commande-chequier-rest
Package:   ma.eai.boa.xbanking

EJBs détectés: 1
  - CommandChequier (3 méthodes)

✓ Projet JAX-RS généré avec succès dans: ./output/commande-chequier-rest

Pour compiler: cd ./output/commande-chequier-rest && mvn clean package
```

---

## Utilisation comme dépendance Maven

### Ajouter la dépendance

```xml
<dependency>
    <groupId>com.bank.tools</groupId>
    <artifactId>jaxrs-wrapper-generator</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### API programmatique

```java
import com.bank.tools.jaxrs.parser.EjbZipParser;
import com.bank.tools.jaxrs.generator.JaxrsProjectGenerator;
import com.bank.tools.jaxrs.model.EjbInfo;

import java.nio.file.Path;
import java.util.List;

public class MonGenerateur {

    public void generer() throws Exception {
        // 1. Instancier le parser
        EjbZipParser parser = new EjbZipParser();

        // 2. Parser le projet EJB (depuis un ZIP)
        List<EjbInfo> ejbs = parser.parse(Path.of("/chemin/vers/projet-ejb.zip"));

        // Ou depuis un répertoire
        // List<EjbInfo> ejbs = parser.parseDirectory(Path.of("/chemin/vers/projet-ejb/"));

        // 3. Vérifier les résultats du parsing
        System.out.println("EJBs détectés: " + ejbs.size());
        for (EjbInfo ejb : ejbs) {
            System.out.println("  - " + ejb.getInterfaceName()
                + " [" + ejb.getEjbType() + "]"
                + " (" + ejb.getMethods().size() + " méthodes)");
        }

        // 4. Générer le projet JAX-RS
        JaxrsProjectGenerator generator = new JaxrsProjectGenerator(
            "ma.eai.boa.xbanking",      // groupId
            "mon-service-rest",          // artifactId
            "ma.eai.boa.xbanking"        // basePackage
        );
        generator.generate(ejbs, Path.of("/chemin/vers/sortie"));
    }
}
```

### Modèle de données

```java
// EjbInfo — représente un EJB ou WebService parsé
EjbInfo ejb = ejbs.get(0);
ejb.getInterfaceName();       // "CommandeChequierService"
ejb.getImplementationName();  // "CommandeChequierServiceBean"
ejb.getEjbType();             // EjbType.STATELESS, STATEFUL, SINGLETON, WEBSERVICE
ejb.getJndiName();            // JNDI name ou serviceName pour @WebService
ejb.getMethods();             // List<MethodInfo>

// MethodInfo — représente une méthode
MethodInfo method = ejb.getMethods().get(0);
method.getName();             // "enregistrerCommande"
method.getReturnType();       // "String"
method.getHttpMethod();       // HttpMethod.POST (inféré du nom)
method.getParameters();       // List<ParameterInfo>
method.getMethodBody();       // "String id = UUID.randomUUID()..."
method.hasMethodBody();       // true

// ParameterInfo — représente un paramètre
ParameterInfo param = method.getParameters().get(0);
param.getName();              // "numCompte"
param.getType();              // "String"
```

---

## Structure du projet généré

```
output/
├── pom.xml
└── src/
    └── main/
        ├── java/{package}/
        │   ├── config/
        │   │   └── JaxRsApplication.java
        │   ├── resource/
        │   │   ├── XxxResource.java
        │   │   └── YyyResource.java
        │   └── dto/
        │       ├── Method1Request.java
        │       ├── Method1Response.java
        │       ├── Method2Request.java
        │       ├── Method2Response.java
        │       └── ErrorResponse.java
        └── resources/
            └── META-INF/
                └── beans.xml
```

### Détail des fichiers générés

| Fichier | Rôle |
|---------|------|
| `pom.xml` | POM Maven avec Jakarta EE 10 (JAX-RS 3.x, JSON-B 3.x, CDI 4.x) — **pas de `jakarta.ejb-api`** |
| `JaxRsApplication.java` | Point d'entrée JAX-RS avec `@ApplicationPath("/api")` |
| `XxxResource.java` | Endpoint REST avec `@Path`, `@GET`/`@POST`/`@PUT`/`@DELETE`, logique métier directe |
| `XxxRequest.java` | DTO d'entrée avec les paramètres de la méthode |
| `XxxResponse.java` | DTO de sortie (si type de retour non-void et non-primitif) |
| `ErrorResponse.java` | DTO d'erreur standard avec champ `message` |
| `beans.xml` | Configuration CDI avec `bean-discovery-mode="all"` |

### POM généré — dépendances

```xml
<dependencies>
    <dependency>
        <groupId>jakarta.platform</groupId>
        <artifactId>jakarta.jakartaee-api</artifactId>
        <version>10.0.0</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

> **Important** : Aucune dépendance `jakarta.ejb-api` n'est incluse. Le projet généré est un pur projet JAX-RS/CDI.

---

## Déploiement du projet généré

### Compiler le projet généré

```bash
cd /chemin/vers/sortie
mvn clean package
```

Cela produit un fichier WAR dans `target/`.

### Serveurs d'application compatibles

Le WAR généré est compatible avec tout serveur Jakarta EE 10 :

| Serveur | Version minimale | Commande de déploiement |
|---------|:---:|---|
| WildFly | 27+ | Copier le WAR dans `standalone/deployments/` |
| Payara | 6+ | `asadmin deploy target/xxx.war` |
| Open Liberty | 23.x+ | Configurer dans `server.xml` |
| TomEE | 9+ | Copier le WAR dans `webapps/` |

### Exemple avec WildFly

```bash
# Compiler
cd /chemin/vers/sortie
mvn clean package

# Déployer
cp target/commande-chequier-rest.war $WILDFLY_HOME/standalone/deployments/

# Tester
curl http://localhost:8080/commande-chequier-rest/api/command-chequier/get-action?actionValue=enrgCommande
```

### Exemple avec Docker (WildFly)

```dockerfile
FROM quay.io/wildfly/wildfly:27.0.0.Final-jdk17
COPY target/commande-chequier-rest.war /opt/jboss/wildfly/standalone/deployments/
```

```bash
docker build -t mon-service-rest .
docker run -p 8080:8080 mon-service-rest
```

---

## Exemples concrets

### Exemple 1 : Projet EJB classique (@Stateless + @Remote)

**Entrée** — `CommandeChequierServiceBean.java` :
```java
@Stateless
public class CommandeChequierServiceBean implements CommandeChequierService {
    @Override
    public String enregistrerCommande(String numCompte, int nbCheques, String typeCarnet) {
        // Logique métier...
        return UUID.randomUUID().toString();
    }
}
```

**Sortie** — `CommandchequierResource.java` :
```java
@Path("/command-chequier")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class CommandchequierResource {

    @POST
    @Path("/enregistrer-commande")
    public Response enregistrerCommande(EnregistrerCommandeRequest request) {
        // --- Logique métier transformée depuis CommandChequier ---
        try {
            // Logique métier...
            return UUID.randomUUID().toString();
            // TODO: le return original a été conservé — adapter si nécessaire
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse(e.getMessage()))
                    .build();
        }
    }
}
```

### Exemple 2 : Projet JAX-WS SOAP (@WebService)

**Entrée** — `MonetiqueCreditJockerWS.java` :
```java
@WebService(serviceName = "MonetiqueCreditJockerWS")
public class MonetiqueCreditJockerWS {
    @WebMethod(operationName = "getLigneDeclicGAB")
    public String getLigneDeclicGAB(String compte, String canal, String reference) {
        Log.info("start : getLigneDeclicGAB");
        return getSolde(compte, canal, reference);
    }
}
```

**Sortie** — `MonetiquecreditjockerResource.java` :
```java
@Path("/monetique-credit-jocker")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class MonetiquecreditjockerResource {

    @GET
    @Path("/get-ligne-declic-g-a-b")
    public Response getLigneDeclicGAB(GetLigneDeclicGABRequest request) {
        // --- Logique métier transformée depuis MonetiqueCreditJockerWS ---
        try {
            Log.info("start : getLigneDeclicGAB");
            return getSolde(compte, canal, reference);
            // TODO: le return original a été conservé — adapter si nécessaire
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse(e.getMessage()))
                    .build();
        }
    }
}
```

### Exemple 3 : Transformation en lot (batch)

```bash
#!/bin/bash
# Script de transformation en lot de tous les projets EJB d'un répertoire

INPUT_DIR="/chemin/vers/projets-ejb"
OUTPUT_DIR="/chemin/vers/sortie"
JAR="target/jaxrs-wrapper-generator-1.0.0-SNAPSHOT.jar"

for project in "$INPUT_DIR"/*/; do
    name=$(basename "$project")
    echo "=== Transformation de $name ==="
    java -jar "$JAR" "$project" \
        -o "$OUTPUT_DIR/$name-rest" \
        -g ma.eai.boa.xbanking \
        -a "$name-rest" \
        -p ma.eai.boa.xbanking
    echo ""
done
```

---

## Dépannage

### Problème : "Aucun EJB détecté dans le projet source"

**Causes possibles :**

1. **Le projet est déjà REST** (Spring Boot `@RestController`) — pas de transformation nécessaire.
2. **Structure non standard** — le parser cherche les fichiers `.java` dans `src/main/java/`. Si votre projet a une structure différente, pointez directement vers le répertoire contenant les sources Java.
3. **Annotations manquantes** — le parser détecte uniquement :
   - `@Remote` / `@Local` (interfaces EJB)
   - `@Stateless` / `@Stateful` / `@Singleton` (implémentations EJB)
   - `@WebService` + `@WebMethod` (services SOAP)

**Solution :** Vérifier que le projet contient bien des classes annotées avec l'une des annotations ci-dessus.

### Problème : Le corps de la méthode n'est pas extrait (TODO généré)

**Cause :** Le parser n'a trouvé que l'interface `@Remote`/`@Local` sans l'implémentation `@Stateless` correspondante.

**Solution :** S'assurer que le fichier `.java` de l'implémentation est présent dans le ZIP ou le répertoire source.

### Problème : Erreur de compilation du projet généré

**Causes possibles :**

1. **Dépendances manquantes** — le code métier extrait peut référencer des classes utilitaires (Log, Parser, Envelope, etc.) qui ne sont pas incluses dans le projet généré.
2. **Variables non résolues** — le corps de la méthode peut référencer des variables de classe (`this.xxx`) qui ne sont pas dans la Resource.

**Solution :** Le code généré est un point de départ. Il faut :
- Ajouter les classes utilitaires manquantes au projet généré
- Adapter les références aux variables de classe
- Remplacer les appels EJB internes par des appels REST ou des services CDI

### Problème : Erreur "OutOfMemoryError" sur un gros projet

**Solution :**
```bash
java -Xmx512m -jar target/jaxrs-wrapper-generator-1.0.0-SNAPSHOT.jar ...
```

### Problème : Encodage des caractères (accents)

**Solution :**
```bash
java -Dfile.encoding=UTF-8 -jar target/jaxrs-wrapper-generator-1.0.0-SNAPSHOT.jar ...
```

---

## Support

- **Dépôt GitHub** : https://github.com/compleoRepos/jaxrs-wrapper-generator
- **Issues** : https://github.com/compleoRepos/jaxrs-wrapper-generator/issues
- **Tests** : `mvn test` (50 tests, BUILD SUCCESS attendu)
