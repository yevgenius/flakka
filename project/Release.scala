package akka

import sbt._
import sbt.Keys._
import java.io.File
import com.typesafe.sbt.site.SphinxSupport.{ generate, Sphinx }
import com.typesafe.sbt.pgp.PgpKeys.publishSigned
import com.typesafe.sbt.S3Plugin.S3

object Release {
  val releaseDirectory = SettingKey[File]("release-directory")

  lazy val settings: Seq[Setting[_]] = commandSettings ++ Seq(
    releaseDirectory <<= crossTarget / "release"
  )

  lazy val commandSettings = Seq(
    commands ++= Seq(buildReleaseCommand, buildSubprojectReleaseCommand, uploadReleaseCommand)
  )

  def buildReleaseCommand = Command.command("build-release") { state =>
    val extracted = Project.extract(state)
    val release = extracted.get(releaseDirectory)
    val dist = extracted.get(Dist.distDirectory)
    val releaseVersion = extracted.get(version)
    val projectRef = extracted.get(thisProjectRef)
    val repo = extracted.get(Publish.defaultPublishTo)
    val state1 = extracted.runAggregated(publishSigned in projectRef, state)
    val (state2, (api, japi)) = extracted.runTask(Unidoc.unidoc, state1)
    val (state3, docs) = extracted.runTask(generate in Sphinx, state2)
    val (state4, _) = extracted.runTask(Dist.dist, state3)
    val (state5, activatorDist) = extracted.runTask(ActivatorDist.activatorDist in LocalProject(AkkaBuild.samples.id), state4)

    IO.delete(release)
    IO.createDirectory(release)
    IO.copyDirectory(repo, release / "releases")
    IO.copyDirectory(api, release / "api" / "akka" / releaseVersion)
    IO.copyDirectory(japi, release / "japi" / "akka" / releaseVersion)
    IO.copyDirectory(docs, release / "docs" / "akka" / releaseVersion)

    // copy all distributions from dist dir to downloads dir
    // may contain distributions from cross-builds
    (dist * "akka_*.zip").get map { file =>
      IO.copyFile(file, release / "downloads" / file.name)
    }
    for (f <- (activatorDist * "*.zip").get)
      IO.copyFile(f, release / "downloads" / f.name)
    state5
  }

  def buildSubprojectReleaseCommand = Command.single("build-subproject-release") { 
    case (state, subprojectId) =>
      val extracted = Project.extract(state)
      val release = extracted.get(releaseDirectory)
      val subprojectVersion = extracted.get(version in LocalProject(subprojectId))

      val repo = extracted.get(Publish.defaultPublishTo)
      val state1 = extracted.runAggregated(publishSigned in LocalProject(subprojectId), state)
      val (state2, (api, japi)) = extracted.runTask(Unidoc.unidoc, state1)   
    
      IO.delete(release)
      IO.createDirectory(release)
      IO.copyDirectory(repo, release / "releases")
      IO.copyDirectory(api, release / "api" / subprojectId / subprojectVersion)
      IO.copyDirectory(japi, release / "japi" / subprojectId / subprojectVersion)
      state2
  }

  def uploadReleaseCommand = Command.command("upload-release") { state =>
    val extracted = Project.extract(state)
    val (state1, _) = extracted.runTask(S3.upload, state)
    state1
  }
}
