import sbt._
import Project.Setting
import Keys._

import GenTypeClass._

import java.awt.Desktop

import scala.collection.immutable.IndexedSeq

import sbtrelease._
import sbtrelease.ReleasePlugin.autoImport._
import sbtrelease.ReleaseStateTransformations._
import sbtrelease.Utilities._

import com.typesafe.sbt.pgp.PgpKeys._

import com.typesafe.sbt.osgi.OsgiKeys
import com.typesafe.sbt.osgi.SbtOsgi._

import sbtbuildinfo.Plugin._

import com.typesafe.tools.mima.plugin.MimaPlugin.mimaDefaultSettings
import com.typesafe.tools.mima.plugin.MimaKeys.mimaPreviousArtifacts
import sbtunidoc.Plugin._
import sbtunidoc.Plugin.UnidocKeys._

object build extends Build {
  type Sett = Def.Setting[_]

  lazy val publishSignedArtifacts = ReleaseStep(
    action = st => {
      val extracted = st.extract
      val ref = extracted.get(thisProjectRef)
      extracted.runAggregated(publishSigned in Global in ref, st)
    },
    check = st => {
      // getPublishTo fails if no publish repository is set up.
      val ex = st.extract
      val ref = ex.get(thisProjectRef)
      Classpaths.getPublishTo(ex.get(publishTo in Global in ref))
      st
    },
    enableCrossBuild = true
  )

  lazy val setMimaVersion: ReleaseStep = { st: State =>
    val extracted = Project.extract(st)

    val (releaseV, _) = st.get(ReleaseKeys.versions).getOrElse(sys.error("impossible"))
    IO.write(extracted get releaseVersionFile, s"""\nscalazMimaBasis in ThisBuild := "${releaseV}"\n""", append = true)
    reapply(Seq(scalazMimaBasis in ThisBuild := releaseV), st)
  }

  val scalacheckVersion = SettingKey[String]("scalacheckVersion")

  private def gitHash = sys.process.Process("git rev-parse HEAD").lines_!.head

  // no generic signatures for scala 2.10.x and 2.9.x, see SI-7932, #571 and #828
  def scalac210Options = Seq("-Yno-generic-signatures")

  lazy val standardSettings: Seq[Sett] = Seq[Sett](
    organization := "org.scalaz",

    scalaVersion := "2.10.6",
    crossScalaVersions := Seq("2.9.3", "2.10.6", "2.11.8"),
    resolvers ++= (if (scalaVersion.value.endsWith("-SNAPSHOT")) List(Opts.resolver.sonatypeSnapshots) else Nil),
    fullResolvers ~= {_.filterNot(_.name == "jcenter")}, // https://github.com/sbt/sbt/issues/2217
    scalacOptions ++= {
      val sv = scalaVersion.value
      val versionDepOpts =
        if (sv startsWith "2.9")
          Seq("-Ydependent-method-types", "-deprecation")
        else
          // does not contain -deprecation (because of ClassManifest)
          // contains -language:postfixOps (because 1+ as a parameter to a higher-order function is treated as a postfix op)
          Seq("-feature", "-language:implicitConversions", "-language:higherKinds", "-language:existentials", "-language:postfixOps")

      Seq("-unchecked") ++ versionDepOpts
    } ++ (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, v)) if v <= 10 => scalac210Options
      case _ => Nil
    }),

    scalacOptions in (Compile, doc) <++= (baseDirectory in LocalProject("scalaz"), version) map { (bd, v) =>
      val tagOrBranch = if(v endsWith "SNAPSHOT") gitHash else ("v" + v)
      Seq("-sourcepath", bd.getAbsolutePath, "-doc-source-url", "https://github.com/scalaz/scalaz/tree/" + tagOrBranch + "€{FILE_PATH}.scala")
    },

    // retronym: I was seeing intermittent heap exhaustion in scalacheck based tests, so opting for determinism.
    parallelExecution in Test := false,
    testOptions in Test += Tests.Argument(TestFrameworks.ScalaCheck, "-maxSize", "5", "-minSuccessfulTests", "33", "-workers", "1"),

    (unmanagedClasspath in Compile) += Attributed.blank(file("dummy")),

    genTypeClasses <<= (scalaSource in Compile, streams, typeClasses) map {
      (scalaSource, streams, typeClasses) =>
        typeClasses.flatMap {
          tc =>
            val typeClassSource0 = typeclassSource(tc)
            typeClassSource0.sources.map(_.createOrUpdate(scalaSource, streams.log))
        }
    },
    checkGenTypeClasses <<= genTypeClasses.map{ classes =>
      if(classes.exists(_._1 != FileStatus.NoChange))
        sys.error(classes.groupBy(_._1).filterKeys(_ != FileStatus.NoChange).mapValues(_.map(_._2)).toString)
    },
    typeClasses := Seq(),
    genToSyntax <<= typeClasses map {
      (tcs: Seq[TypeClass]) =>
      val objects = tcs.map(tc => "object %s extends To%sSyntax".format(Util.initLower(tc.name), tc.name)).mkString("\n")
      val all = "object all extends " + tcs.map(tc => "To%sSyntax".format(tc.name)).mkString(" with ")
      objects + "\n\n" + all
    },
    typeClassTree <<= typeClasses map {
      tcs => tcs.map(_.doc).mkString("\n")
    },

    showDoc in Compile <<= (doc in Compile, target in doc in Compile) map { (_, out) =>
      val index = out / "index.html"
      if (index.exists()) Desktop.getDesktop.open(out / "index.html")
    },

    credentialsSetting,
    publishSetting,
    publishArtifact in Test := false,

    // adapted from sbt-release defaults
    // (performs `publish-signed` instead of `publish`)
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runTest,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      publishSignedArtifacts,
      setNextVersion,
      setMimaVersion,
      commitNextVersion,
      pushChanges
    ),

    scalacheckVersion := {
      val sv = scalaVersion.value
      if (sv startsWith "2.12")
        "1.11.6"
      else
        "1.11.4"
    },
    autoAPIMappings := PartialFunction.condOpt(CrossVersion.partialVersion(scalaVersion.value)){
      case Some((2, v)) if v < 10 => false
    }.getOrElse(true),
    apiMappings := Map.empty,
    pomIncludeRepository := {
      x => false
    },
    pomExtra := (
      <url>http://scalaz.org</url>
        <licenses>
          <license>
            <name>BSD-style</name>
            <url>http://opensource.org/licenses/BSD-3-Clause</url>
            <distribution>repo</distribution>
          </license>
        </licenses>
        <scm>
          <url>git@github.com:scalaz/scalaz.git</url>
          <connection>scm:git:git@github.com:scalaz/scalaz.git</connection>
        </scm>
        <developers>
          {
          Seq(
            ("runarorama", "Runar Bjarnason"),
            ("pchiusano", "Paul Chiusano"),
            ("tonymorris", "Tony Morris"),
            ("retronym", "Jason Zaugg"),
            ("ekmett", "Edward Kmett"),
            ("alexeyr", "Alexey Romanov"),
            ("copumpkin", "Daniel Peebles"),
            ("rwallace", "Richard Wallace"),
            ("nuttycom", "Kris Nuttycombe"),
            ("larsrh", "Lars Hupel")
          ).map {
            case (id, name) =>
              <developer>
                <id>{id}</id>
                <name>{name}</name>
                <url>http://github.com/{id}</url>
              </developer>
          }
        }
        </developers>
      )
  ) ++ osgiSettings ++ Seq[Sett](
    OsgiKeys.additionalHeaders := Map("-removeheaders" -> "Include-Resource,Private-Package")
  ) ++ mimaDefaultSettings ++ Seq[Sett](
    mimaPreviousArtifacts := scalazMimaBasis.?.value.map { bas =>
      organization.value % (name.value + "_" + scalaBinaryVersion.value) % bas
    }.toSet
  )

  lazy val scalaz = Project(
    id = "scalaz",
    base = file("."),
    settings = standardSettings ++ unidocSettings ++ Seq[Sett](
      mimaPreviousArtifacts := Set.empty,
      // <https://github.com/scalaz/scalaz/issues/261>
      unidocProjectFilter in (ScalaUnidoc, unidoc) := inAnyProject -- inProjects(typelevel),
      artifacts <<= Classpaths.artifactDefs(Seq(packageDoc in Compile)),
      packagedArtifacts <<= Classpaths.packaged(Seq(packageDoc in Compile))
    ) ++ Defaults.packageTaskSettings(packageDoc in Compile, (unidoc in Compile).map(_.flatMap(Path.allSubpaths))),
    aggregate = Seq(core, concurrent, effect, example, iteratee, scalacheckBinding, tests, typelevel, xml)
  )

  // http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22org.scala-lang.modules%22%20
  val scalaParserCombinatorsVersion = SettingKey[String]("scalaParserCombinatorsVersion")
  val scalaXmlVersion = SettingKey[String]("scalaXmlVersion")

  lazy val core = Project(
    id = "core",
    base = file("core"),
    settings = standardSettings ++ buildInfoSettings ++ Seq[Sett](
      name := "scalaz-core",
      typeClasses := TypeClass.core,
      sourceGenerators in Compile <+= (sourceManaged in Compile) map {
        dir => Seq(GenerateTupleW(dir))
      },
      scalaParserCombinatorsVersion := "1.0.4",
      scalaXmlVersion := "1.0.5",
      libraryDependencies ++= {
        CrossVersion.partialVersion(scalaVersion.value) match {
          case Some((2, scalaMajor)) if scalaMajor >= 11 =>
            Seq(
              "org.scala-lang.modules" %% "scala-parser-combinators" % scalaParserCombinatorsVersion.value,
              "org.scala-lang.modules" %% "scala-xml" % scalaXmlVersion.value
            )
          case _ =>
            Nil
        }
      },
      sourceGenerators in Compile <+= buildInfo,
      buildInfoKeys := Seq[BuildInfoKey](version, scalaVersion),
      buildInfoPackage := "scalaz",
      osgiExport("scalaz"),
      OsgiKeys.importPackage := Seq("javax.swing;resolution:=optional", "*")
    )
  )

  lazy val concurrent = Project(
    id = "concurrent",
    base = file("concurrent"),
    settings = standardSettings ++ Seq[Sett](
      name := "scalaz-concurrent",
      typeClasses := TypeClass.concurrent,
      osgiExport("scalaz.concurrent"),
      OsgiKeys.importPackage := Seq("javax.swing;resolution:=optional", "*")
    ),
    dependencies = Seq(core, effect)
  )

  lazy val effect = Project(
    id = "effect",
    base = file("effect"),
    settings = standardSettings ++ Seq[Sett](
      name := "scalaz-effect",
      typeClasses := TypeClass.effect,
      osgiExport("scalaz.effect", "scalaz.std.effect", "scalaz.syntax.effect")
    ),
    dependencies = Seq(core)
  )

  lazy val iteratee = Project(
    id = "iteratee",
    base = file("iteratee"),
    settings = standardSettings ++ Seq[Sett](
      name := "scalaz-iteratee",
      osgiExport("scalaz.iteratee")
    ),
    dependencies = Seq(effect)
  )

  lazy val typelevel = Project(
    id = "typelevel",
    base = file("typelevel"),
    settings = standardSettings ++ Seq[Sett](
      name := "scalaz-typelevel",
      osgiExport("scalaz.typelevel", "scalaz.syntax.typelevel")
    ),
    dependencies = Seq(core)
  )

  lazy val xml = Project(
    id = "xml",
    base = file("xml"),
    settings = standardSettings ++ Seq[Sett](
      name := "scalaz-xml",
      typeClasses := TypeClass.xml,
      osgiExport("scalaz.xml")
    ),
    dependencies = Seq(core)
  )

  lazy val example = Project(
    id = "example",
    base = file("example"),
    dependencies = Seq(core, iteratee, concurrent, typelevel, xml),
    settings = standardSettings ++ Seq[Sett](
      name := "scalaz-example",
      mimaPreviousArtifacts := Set.empty,
      publishArtifact := false
    )
  )

  lazy val scalacheckBinding = Project(
    id           = "scalacheck-binding",
    base         = file("scalacheck-binding"),
    dependencies = Seq(core, concurrent, typelevel, xml, iteratee),
    settings     = standardSettings ++ Seq[Sett](
      name := "scalaz-scalacheck-binding",
      libraryDependencies += "org.scalacheck" %% "scalacheck" % scalacheckVersion.value,
      osgiExport("scalaz.scalacheck")
    )
  )

  lazy val tests = Project(
    id = "tests",
    base = file("tests"),
    dependencies = Seq(core, iteratee, concurrent, effect, typelevel, xml, scalacheckBinding % "test"),
    settings = standardSettings ++Seq[Sett](
      name := "scalaz-tests",
      publishArtifact := false,
      mimaPreviousArtifacts := Set.empty,
      libraryDependencies += "org.scalacheck" %% "scalacheck" % scalacheckVersion.value % "test"
    )
  )

  lazy val publishSetting = publishTo <<= (version).apply{
    v =>
      val nexus = "https://oss.sonatype.org/"
      if (v.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases" at nexus + "service/local/staging/deploy/maven2")
  }

  lazy val credentialsSetting = credentials += {
    Seq("build.publish.user", "build.publish.password") map sys.props.get match {
      case Seq(Some(user), Some(pass)) =>
        Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", user, pass)
      case _                           =>
        Credentials(Path.userHome / ".ivy2" / ".credentials")
    }
  }

  lazy val scalazMimaBasis =
    SettingKey[String]("scalaz-mima-basis", "Version of scalaz against which to run MIMA.")

  lazy val genTypeClasses = TaskKey[Seq[(FileStatus, File)]]("gen-type-classes")

  lazy val typeClasses = TaskKey[Seq[TypeClass]]("type-classes")

  lazy val genToSyntax = TaskKey[String]("gen-to-syntax")

  lazy val showDoc = TaskKey[Unit]("show-doc")

  lazy val typeClassTree = TaskKey[String]("type-class-tree", "Generates scaladoc formatted tree of type classes.")

  lazy val checkGenTypeClasses = TaskKey[Unit]("check-gen-type-classes")

  def osgiExport(packs: String*) = OsgiKeys.exportPackage := packs.map(_ + ".*;version=${Bundle-Version}")
}

// vim: expandtab:ts=2:sw=2
