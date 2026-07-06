# JAX-RS Wrapper Generator

Module Maven indépendant qui prend un projet EJB en entrée (`.zip` ou répertoire) et génère un projet **JAX-RS pur** avec des DTOs JSON — sans BIAN, sans résilience, sans ACL.

## Fonctionnalités

| Fonctionnalité | Description |
|---|---|
| Parser EJB | Détecte les interfaces `@Remote`/`@Local`, extrait les méthodes et paramètres |
| Génération Resources JAX-RS | `@Path`, `@GET`, `@POST`, `@PUT`, `@DELETE`, `@Consumes`/`@Produces` JSON |
| Génération DTOs | Request/Response en JSON pour chaque méthode (plus de XML) |
| Service avec JNDI lookup | Appel direct à l'EJB via `InitialContext.lookup()` |
| Inférence HTTP | Détermine GET/POST/PUT/DELETE à partir du nom de la méthode |
| CLI picocli | Exécution en ligne de commande |

## Prérequis

- Java 17+
- Maven 3.8+

## Installation

```bash
git clone https://github.com/compleoRepos/jaxrs-wrapper-generator.git
cd jaxrs-wrapper-generator
mvn clean install
```

## Utilisation

### En ligne de commande

```bash
# Depuis un .zip
java -jar target/jaxrs-wrapper-generator-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  --input /path/to/projet-ejb.zip \
  --output /path/to/output \
  --group-id com.bank.api \
  --artifact-id mon-service-rest \
  --base-package com.bank.api

# Depuis un répertoire
java -jar target/jaxrs-wrapper-generator-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  --input /path/to/projet-ejb/ \
  --output /path/to/output \
  --group-id com.bank.api \
  --artifact-id mon-service-rest \
  --base-package com.bank.api
```

### Comme dépendance Maven

```xml
<dependency>
    <groupId>com.bank.tools</groupId>
    <artifactId>jaxrs-wrapper-generator</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

```java
EjbZipParser parser = new EjbZipParser();
List<EjbInfo> ejbs = parser.parse(Path.of("projet-ejb.zip"));

JaxrsProjectGenerator generator = new JaxrsProjectGenerator();
generator.generate(ejbs, outputDir, "com.bank.api", "mon-service-rest", "com.bank.api");
```

## Structure du projet généré

```
output/
├── pom.xml                          (Jakarta EE + JAX-RS + JSON-B)
└── src/main/java/com/bank/api/
    ├── resource/
    │   ├── CommandeChequierResource.java   (@Path, @GET, @POST, etc.)
    │   └── VirementResource.java
    ├── dto/
    │   ├── EnregistrerCommandeRequest.java
    │   ├── EnregistrerCommandeResponse.java
    │   ├── EffectuerVirementRequest.java
    │   └── ...
    ├── service/
    │   ├── CommandeChequierServiceClient.java  (JNDI lookup)
    │   └── VirementServiceClient.java
    └── JaxrsApplication.java               (@ApplicationPath)
```

## Inférence des méthodes HTTP

| Préfixe du nom de méthode | Méthode HTTP |
|---|---|
| `get`, `find`, `search`, `consulter`, `lister`, `rechercher` | GET |
| `update`, `modify`, `edit`, `modifier`, `maj` | PUT |
| `delete`, `remove`, `supprimer`, `annuler` | DELETE |
| Tout le reste (`create`, `enregistrer`, `executer`, etc.) | POST |

## Tests

```bash
mvn test
```

12 tests unitaires et d'intégration couvrant :
- Parsing d'interfaces EJB depuis un répertoire et un .zip
- Extraction des paramètres et types de retour
- Inférence des méthodes HTTP
- Génération complète du projet (POM, Resources, DTOs, Services)

## Architecture

```
src/main/java/com/bank/tools/jaxrs/
├── model/
│   ├── EjbInfo.java          (modèle d'un EJB parsé)
│   ├── MethodInfo.java       (modèle d'une méthode avec inférence HTTP)
│   └── ParameterInfo.java    (modèle d'un paramètre)
├── parser/
│   └── EjbZipParser.java     (parse .zip ou répertoire, détecte @Remote/@Local)
├── generator/
│   └── JaxrsProjectGenerator.java  (génère le projet JAX-RS complet)
└── cli/
    └── JaxrsGeneratorCli.java      (CLI picocli)
```
