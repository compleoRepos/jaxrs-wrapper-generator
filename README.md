# JAX-RS Generator — Transformation directe EJB/SOAP → JAX-RS

Module Maven indépendant qui prend un projet EJB ou JAX-WS (SOAP) en entrée (`.zip` ou répertoire) et génère un projet **JAX-RS REST natif** avec des DTOs JSON.

**Approche : transformation directe** — le code métier est adapté directement dans les endpoints REST. Pas de proxy JNDI, pas de couche Service intermédiaire, pas de dépendance EJB dans le projet généré.

> **Documentation complète** : voir [docs/INSTALL.md](docs/INSTALL.md) pour le guide d'installation et d'utilisation détaillé.

## Types de projets supportés

| Type source | Annotations détectées | Résultat |
|---|---|---|
| EJB classique (interface + impl) | `@Remote`/`@Local` + `@Stateless`/`@Stateful`/`@Singleton` | Resource JAX-RS avec corps métier |
| EJB sans interface | `@Stateless` seul (classe directe) | Resource JAX-RS avec corps métier |
| JAX-WS SOAP | `@WebService` + `@WebMethod` | Resource JAX-RS avec corps métier |
| Spring Boot REST | `@RestController` | **Ignoré** (déjà REST) |

## Démarrage rapide

```bash
# 1. Cloner et compiler
git clone https://github.com/compleoRepos/jaxrs-wrapper-generator.git
cd jaxrs-wrapper-generator
mvn clean package

# 2. Transformer un projet EJB
java -jar target/jaxrs-wrapper-generator-1.0.0-SNAPSHOT.jar \
  /chemin/vers/projet-ejb.zip \
  -o ./output/mon-service-rest \
  -g ma.eai.boa.xbanking \
  -a mon-service-rest \
  -p ma.eai.boa.xbanking

# 3. Compiler le projet généré
cd ./output/mon-service-rest
mvn clean package
```

## Options CLI

```
Usage: jaxrs-gen [-hV] [-a=<artifactId>] [-g=<groupId>] [-o=<output>]
                 [-p=<basePackage>] <input>

Génère un projet JAX-RS pur à partir d'un projet EJB (.zip ou répertoire).

      <input>       Chemin vers le projet EJB (.zip ou répertoire)
  -a, --artifact-id ArtifactId Maven du projet généré (défaut: rest-api)
  -g, --group-id    GroupId Maven du projet généré (défaut: com.bank.api)
  -h, --help        Affiche l'aide
  -o, --output      Répertoire de sortie (défaut: ./generated-jaxrs)
  -p, --package     Package de base du code généré (défaut: com.bank.api)
  -V, --version     Affiche la version
```

## Fonctionnalités

| Fonctionnalité | Description |
|---|---|
| Parser EJB | Détecte `@Remote`/`@Local`, `@Stateless`/`@Stateful`/`@Singleton`, extrait méthodes, paramètres et **corps** |
| Parser JAX-WS | Détecte `@WebService` + `@WebMethod`, extrait le serviceName, les opérations et leurs corps |
| Transformation directe | Le code métier est injecté directement dans la Resource JAX-RS |
| Génération Resources | `@Path`, `@GET`/`@POST`/`@PUT`/`@DELETE`, `@Consumes`/`@Produces` JSON, `@ApplicationScoped` |
| Génération DTOs | Request/Response en JSON pour chaque méthode |
| Inférence HTTP | Détermine GET/POST/PUT/DELETE à partir du nom de la méthode |
| CLI picocli | Exécution en ligne de commande avec aide intégrée |

## Ce que ce module n'est PAS

- **Pas un wrapper/proxy JNDI** : le projet généré ne fait pas de `InitialContext.lookup()`
- **Pas de dépendance EJB** : le POM généré ne contient pas `jakarta.ejb-api`
- **Pas de BIAN**, pas de résilience (CircuitBreaker/Retry), pas d'ACL

## Structure du projet généré

```
output/
├── pom.xml                          (Jakarta EE 10 + JSON-B — PAS de jakarta.ejb-api)
└── src/main/
    ├── java/{package}/
    │   ├── config/
    │   │   └── JaxRsApplication.java       (@ApplicationPath("/api"))
    │   ├── resource/
    │   │   └── XxxResource.java            (@Path, logique métier directe)
    │   └── dto/
    │       ├── XxxRequest.java
    │       ├── XxxResponse.java
    │       └── ErrorResponse.java
    └── resources/
        └── META-INF/beans.xml
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

**50 tests** au total :

| Suite | Tests | Couverture |
|---|:---:|---|
| `EjbZipParserTest` | 5 | Parsing EJB classique (@Remote/@Stateless) |
| `WebServiceParserTest` | 5 | Parsing @WebService/SOAP, Spring Boot ignoré |
| `JaxrsProjectGeneratorTest` | 11 | Génération et structure du projet |
| `RealProjectsIntegrationTest` | 9 | Projets réels (ZIP fournis) |
| `FullAuditTest` | 20 | Audit exhaustif sur 19 projets |

## Architecture

```
src/main/java/com/bank/tools/jaxrs/
├── model/
│   ├── EjbInfo.java          (modèle d'un EJB/WebService parsé, enum EjbType)
│   ├── MethodInfo.java       (modèle d'une méthode avec corps et inférence HTTP)
│   └── ParameterInfo.java    (modèle d'un paramètre)
├── parser/
│   └── EjbZipParser.java     (parse .zip ou répertoire, détecte EJB + @WebService)
├── generator/
│   └── JaxrsProjectGenerator.java  (transformation directe → JAX-RS)
└── cli/
    └── JaxrsGeneratorCli.java      (CLI picocli)
```

## Prérequis

- Java 17+
- Maven 3.8+

## Documentation

- [Guide d'installation et d'utilisation détaillé](docs/INSTALL.md)
- [Résultats d'audit](AUDIT-FINDINGS.md)
