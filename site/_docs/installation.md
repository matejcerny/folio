# Installation

## Prerequisites

- **Scala 3** (3.3.x LTS or newer)

## Dependencies

### SBT

```scala
libraryDependencies ++= Seq(
  "io.github.matejcerny" %% "folio-core" % "{{ projectVersion }}",
  "io.github.matejcerny" %% "folio-cats" % "{{ projectVersion }}", // optional Cats adapter
  "io.github.matejcerny" %% "folio-skunk" % "{{ projectVersion }}" // optional Skunk integration
)
```

### Scala CLI

```scala
//> using dep io.github.matejcerny::folio-core:{{ projectVersion }}
//> using dep io.github.matejcerny::folio-cats:{{ projectVersion }}
//> using dep io.github.matejcerny::folio-skunk:{{ projectVersion }}
```

### Mill

```scala
def ivyDeps = Agg(
  ivy"io.github.matejcerny::folio-core:{{ projectVersion }}",
  ivy"io.github.matejcerny::folio-cats:{{ projectVersion }}",
  ivy"io.github.matejcerny::folio-skunk:{{ projectVersion }}"
)
```

## Scala.js and Scala Native

In **sbt 2**, `%%` already selects the platform-specific artifact (same as JVM). In sbt 1 / Mill, use `%%%` / `:::`:

```scala
// SBT 2 (JS / Native project)
libraryDependencies ++= Seq(
  "io.github.matejcerny" %% "folio-core" % "{{ projectVersion }}",
  "io.github.matejcerny" %% "folio-cats" % "{{ projectVersion }}",
  "io.github.matejcerny" %% "folio-skunk" % "{{ projectVersion }}"
)
```

## Available Artifacts

| Artifact      | Description                       | Platforms       |
|---------------|-----------------------------------|-----------------|
| `folio-core`  | Core pagination types and algebra | JVM, JS, Native |
| `folio-cats`  | Cats `ApplicativeError` adapter   | JVM, JS, Native |
| `folio-skunk` | Skunk SQL integration             | JVM, JS, Native |
