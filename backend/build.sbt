import org.typelevel.sbt.tpolecat.*

ThisBuild / scalaVersion := "3.9.0" // актуальная LTS-линейка Scala 3
ThisBuild / organization := "tracker"
ThisBuild / version := "0.1.0"

// Строгие флаги (включая -Werror) всегда, а не только в CI: предупреждение ломает сборку и локально.
ThisBuild / tpolecatDefaultOptionsMode := CiMode

// Все версии зафиксированы точно. doobie — единственная не-final зависимость:
// стабильная линейка 0.13 рассчитана на Cats Effect 2, совместимы с CE3 только 1.0.0-RC.
val catsEffectV = "3.7.1"
val http4sV = "0.23.38"
val circeV = "0.14.17"
val doobieV = "1.0.0-RC12"
val postgresV = "42.7.14"
val flywayV = "13.12.1"
val cirisV = "3.15.2"
val log4catsV = "2.8.0"
val logbackV = "1.6.6"
val password4jV = "1.8.4"
val munitCeV = "2.2.1"
val testcontainersV = "2.0.5"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "tracker-backend",
    Compile / mainClass := Some("tracker.Main"),
    Compile / doc / sources := Seq.empty, // scaladoc в образе не нужен
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectV,
      "org.http4s" %% "http4s-ember-server" % http4sV,
      "org.http4s" %% "http4s-dsl" % http4sV,
      "org.http4s" %% "http4s-circe" % http4sV,
      "io.circe" %% "circe-core" % circeV,
      "org.tpolecat" %% "doobie-core" % doobieV,
      "org.tpolecat" %% "doobie-hikari" % doobieV,
      "org.tpolecat" %% "doobie-postgres" % doobieV,
      "org.postgresql" % "postgresql" % postgresV,
      "org.flywaydb" % "flyway-core" % flywayV,
      "org.flywaydb" % "flyway-database-postgresql" % flywayV,
      "is.cir" %% "ciris" % cirisV,
      "org.typelevel" %% "log4cats-slf4j" % log4catsV,
      "ch.qos.logback" % "logback-classic" % logbackV,
      "com.password4j" % "password4j" % password4jV,
      "org.typelevel" %% "munit-cats-effect" % munitCeV % Test,
      "org.typelevel" %% "log4cats-testing" % log4catsV % Test,
      "org.testcontainers" % "testcontainers-postgresql" % testcontainersV % Test
    ),
    // Тесты поднимают Postgres и сервер: отдельная JVM и никакого параллелизма между сьютами.
    Test / fork := true,
    Test / parallelExecution := false
  )
