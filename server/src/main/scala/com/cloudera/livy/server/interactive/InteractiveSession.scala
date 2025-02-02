/*
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cloudera.livy.server.interactive

import java.io.{File, InputStream}
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.{Files, Paths}
import java.util.{HashMap => JHashMap}
import java.util.concurrent.atomic.AtomicLong

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.{Future, _}

import org.apache.spark.launcher.SparkLauncher
import org.json4s._
import org.json4s.{DefaultFormats, Formats, JValue}
import org.json4s.JsonAST.JString
import org.json4s.jackson.JsonMethods._

import com.cloudera.livy._
import com.cloudera.livy.client.common.HttpMessages._
import com.cloudera.livy.rsc.{PingJob, RSCClient, RSCConf}
import com.cloudera.livy.sessions._

object InteractiveSession {
  val LivyReplDriverClassPath = "livy.repl.driverClassPath"
  val LivyReplJars = "livy.repl.jars"
  val SparkSubmitPyFiles = "spark.submit.pyFiles"
  val SparkYarnIsPython = "spark.yarn.isPython"
}

class InteractiveSession(
    id: Int,
    owner: String,
    override val proxyUser: Option[String],
    livyConf: LivyConf,
    request: CreateInteractiveRequest)
  extends Session(id, owner, livyConf) {

  import InteractiveSession._

  private implicit def jsonFormats: Formats = DefaultFormats

  private var _state: SessionState = SessionState.Starting()

  private val operations = mutable.Map[Long, String]()
  private val operationCounter = new AtomicLong(0)

  val kind = request.kind

  private val client = {
    info(s"Creating LivyClient for sessionId: $id")
    val builder = new LivyClientBuilder()
      .setConf("spark.app.name", s"livy-session-$id")
      .setAll(Option(request.conf).map(_.asJava).getOrElse(new JHashMap()))
      .setConf("livy.client.sessionId", id.toString)
      .setConf(RSCConf.Entry.DRIVER_CLASS.key(), "com.cloudera.livy.repl.ReplDriver")
      .setURI(new URI("local:spark"))

    kind match {
      case PySpark() =>
        val pySparkFiles = if (!LivyConf.TEST_MODE) findPySparkArchives() else Nil
        builder.setConf(SparkYarnIsPython, "true")
        builder.setConf(SparkSubmitPyFiles, (pySparkFiles ++ request.pyFiles).mkString(","))
      case SparkR() =>
        val sparkRFile = if (!LivyConf.TEST_MODE) findSparkRArchives() else Nil
        builder.setConf(RSCConf.Entry.SPARKR_PACKAGE.key(), sparkRFile.mkString(","))
      case _ =>
    }
    builder.setConf(RSCConf.Entry.SESSION_KIND.key, kind.toString)

    sys.env.get("LIVY_REPL_JAVA_OPTS").foreach { opts =>
      val userOpts = request.conf.get(SparkLauncher.DRIVER_EXTRA_JAVA_OPTIONS)
      val newOpts = userOpts.toSeq ++ Seq(opts)
      builder.setConf(SparkLauncher.DRIVER_EXTRA_JAVA_OPTIONS, newOpts.mkString(" "))
    }

    Option(livyConf.get(LivyReplDriverClassPath)).foreach { cp =>
      val userCp = request.conf.get(SparkLauncher.DRIVER_EXTRA_CLASSPATH)
      val newCp = Seq(cp) ++ userCp.toSeq
      builder.setConf(SparkLauncher.DRIVER_EXTRA_CLASSPATH, newCp.mkString(File.pathSeparator))
    }

    val allJars = livyJars(livyConf) ++ request.jars

    def listToConf(lst: List[String]): Option[String] = {
      if (lst.size > 0) Some(lst.mkString(",")) else None
    }

    val userOpts: Map[Option[String], String] = Map(
      listToConf(request.archives) -> "spark.yarn.dist.archives",
      listToConf(request.files) -> "spark.files",
      listToConf(allJars) -> "spark.jars",
      request.driverCores.map(_.toString) -> "spark.driver.cores",
      request.driverMemory.map(_.toString + "b") -> SparkLauncher.DRIVER_MEMORY,
      request.executorCores.map(_.toString) -> SparkLauncher.EXECUTOR_CORES,
      request.executorMemory.map(_.toString) -> SparkLauncher.EXECUTOR_MEMORY,
      request.numExecutors.map(_.toString) -> "spark.dynamicAllocation.maxExecutors"
    )

    userOpts.foreach { case (opt, configKey) =>
      opt.foreach { value => builder.setConf(configKey, value) }
    }

    builder.setConf(RSCConf.Entry.PROXY_USER.key(), proxyUser.orNull)
    builder.build()
  }.asInstanceOf[RSCClient]

  // Send a dummy job that will return once the client is ready to be used, and set the
  // state to "idle" at that point.
  client.submit(new PingJob()).addListener(new JobHandle.Listener[Void]() {
    override def onJobQueued(job: JobHandle[Void]): Unit = { }
    override def onJobStarted(job: JobHandle[Void]): Unit = { }

    override def onJobCancelled(job: JobHandle[Void]): Unit = {
      transition(SessionState.Error())
      stop()
    }

    override def onJobFailed(job: JobHandle[Void], cause: Throwable): Unit = {
      transition(SessionState.Error())
      stop()
    }

    override def onJobSucceeded(job: JobHandle[Void], result: Void): Unit = {
      transition(SessionState.Idle())
    }
  })


  private[this] var _executedStatements = 0
  private[this] var _statements = IndexedSeq[Statement]()

  override def logLines(): IndexedSeq[String] = IndexedSeq()

  override def state: SessionState = _state

  override def stopSession(): Unit = {
    transition(SessionState.ShuttingDown())
    client.stop(true)
    transition(SessionState.Dead())
  }

  def statements: IndexedSeq[Statement] = _statements

  def interrupt(): Future[Unit] = {
    stop()
  }

  def executeStatement(content: ExecuteRequest): Statement = {
    ensureRunning()
    _state = SessionState.Busy()
    recordActivity()

    val future = Future {
      val id = client.submitReplCode(content.code)
      waitForStatement(id)
    }

    val statement = new Statement(_executedStatements, content, future)

    _executedStatements += 1
    _statements = _statements :+ statement

    statement
  }

  def runJob(job: Array[Byte]): Long = {
    performOperation(job, true)
  }

  def submitJob(job: Array[Byte]): Long = {
    performOperation(job, false)
  }

  def addFile(fileStream: InputStream, fileName: String): Unit = {
    addFile(copyResourceToHDFS(fileStream, fileName))
  }

  def addJar(jarStream: InputStream, jarName: String): Unit = {
    addJar(copyResourceToHDFS(jarStream, jarName))
  }

  def addFile(uri: URI): Unit = {
    recordActivity()
    client.addFile(uri).get()
  }

  def addJar(uri: URI): Unit = {
    recordActivity()
    client.addJar(uri).get()
  }

  def jobStatus(id: Long): Any = {
    val clientJobId = operations(id)
    recordActivity()
    // TODO: don't block indefinitely?
    val status = client.getBypassJobStatus(clientJobId).get()
    new JobStatus(id, status.state, status.result, status.error)
  }

  def cancelJob(id: Long): Unit = {
    recordActivity()
    operations.remove(id).foreach { client.cancel }
  }

  @tailrec
  private def waitForStatement(id: String): JValue = {
    val response = client.getReplJobResult(id).get()
    if (response != null) {
      val result = parse(response)
      // If the response errored out, it's possible it took down the interpreter. Check if
      // it's still running.
      result \ "status" match {
        case JString("error") =>
          val state = client.getReplState().get() match {
            case "error" => SessionState.Error()
            case _ => SessionState.Idle()
          }
          transition(state)
        case _ => transition(SessionState.Idle())
      }
      result
    } else {
      Thread.sleep(1000)
      waitForStatement(id)
    }
  }

  private def livyJars(livyConf: LivyConf): List[String] = {
    Option(livyConf.get(LivyReplJars)).map(_.split(",").toList).getOrElse {
      val home = sys.env("LIVY_HOME")
      val jars = Option(new File(home, "repl-jars"))
        .filter(_.isDirectory())
        .getOrElse(new File(home, "repl/target/jars"))
      require(jars.isDirectory(), "Cannot find Livy REPL jars.")
      jars.listFiles().map(_.getAbsolutePath()).toList
    }
  }

  private def findSparkRArchives(): Seq[String] = {
    sys.env.get("SPARKR_ARCHIVES_PATH")
      .map(_.split(",").toSeq)
      .getOrElse {
        sys.env.get("SPARK_HOME").map { case sparkHome =>
          val rLibPath = Seq(sparkHome, "R", "lib").mkString(File.separator)
          val rArchivesFile = new File(rLibPath, "sparkr.zip")
          require(rArchivesFile.exists(),
            "sparkr.zip not found; cannot run sparkr application.")
          Seq(rArchivesFile.getAbsolutePath)
        }.getOrElse(Seq())
      }
  }


  private def findPySparkArchives(): Seq[String] = {
    sys.env.get("PYSPARK_ARCHIVES_PATH")
      .map(_.split(",").toSeq)
      .getOrElse {
        sys.env.get("SPARK_HOME") .map { case sparkHome =>
          val pyLibPath = Seq(sparkHome, "python", "lib").mkString(File.separator)
          val pyArchivesFile = new File(pyLibPath, "pyspark.zip")
          require(pyArchivesFile.exists(),
            "pyspark.zip not found; cannot run pyspark application in YARN mode.")

          val py4jFile = Files.newDirectoryStream(Paths.get(pyLibPath), "py4j-*-src.zip")
            .iterator()
            .next()
            .toFile

          require(py4jFile.exists(),
            "py4j-*-src.zip not found; cannot run pyspark application in YARN mode.")
          Seq(pyArchivesFile.getAbsolutePath, py4jFile.getAbsolutePath)
        }.getOrElse(Seq())
      }
  }


  private def transition(state: SessionState) = synchronized {
    _state = state
  }

  private def ensureRunning(): Unit = synchronized {
    _state match {
      case SessionState.Idle() | SessionState.Busy() =>
      case _ =>
        throw new IllegalStateException("Session is in state %s" format _state)
    }
  }

  private def performOperation(job: Array[Byte], sync: Boolean): Long = {
    ensureRunning()
    recordActivity()
    val future = client.bypass(ByteBuffer.wrap(job), sync)
    val opId = operationCounter.incrementAndGet()
    operations(opId) = future
    opId
   }

}
