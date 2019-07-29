package io.rml.framework

import java.io.File
import java.util.concurrent.{CompletableFuture, Executors}

import io.rml.framework.engine.{BulkPostProcessor, JsonLDProcessor, NopPostProcessor, PostProcessor}
import io.rml.framework.util._
import io.rml.framework.util.fileprocessing.{DataSourceTestUtil, ExpectedOutputTestUtil, MappingTestUtil, StreamDataSourceTestUtil}
import io.rml.framework.util.logging.Logger
import io.rml.framework.util.server.{KafkaTestServerFactory, StreamTestServerFactory, TCPTestServer, TCPTestServerFactory, TestServer, TestSink}
import org.apache.flink.api.common.JobID
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.api.scala.ExecutionEnvironment
import org.apache.flink.runtime.messages.Acknowledge
import org.apache.flink.runtime.minicluster.MiniCluster
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}


object StreamingTestMain {
  Logger.lineBreak(50)
  implicit val env: ExecutionEnvironment = ExecutionEnvironment.getExecutionEnvironment
  implicit val senv: StreamExecutionEnvironment = StreamExecutionEnvironment.getExecutionEnvironment
  implicit val executor: ExecutionContextExecutor = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())
  val cluster: Future[MiniCluster] = StreamTestUtil.getClusterFuture
  var serverOpt: Option[TestServer] = None

  val serverFactoryMap: Map[String, StreamTestServerFactory] = Map("tcp" -> TCPTestServerFactory, "kafka" -> KafkaTestServerFactory)

  val PATH_PARAM = "path"
  val TYPE_PARAM = "type"
  val POST_PROCESS_PARAM = "post-process"


  def main(args: Array[String]): Unit = {

    val EMPTY_VALUE = "__NO_VALUE_KEY"

    // get parameters
    if (args.nonEmpty)
      Logger.logInfo(s"Arguments: ${args.mkString(" ")}")

    val parameters = ParameterTool.fromArgs(args)

    val fileName = if (parameters.has(PATH_PARAM)) parameters.get(PATH_PARAM)
    else "stream/kafka/RMLTC0008a-XML-STREAM-KAFKA"
    val testType = if (parameters.has(TYPE_PARAM)) parameters.get(TYPE_PARAM)
    else "kafka"

    val postProcessorType = if (parameters.has(POST_PROCESS_PARAM)) parameters.get(POST_PROCESS_PARAM)
    else "noopt"

    implicit val postProcessor: PostProcessor = TestUtil.pickPostProcessor(postProcessorType)

    val folder = MappingTestUtil.getFile(fileName)
    Logger.logInfo(s"Creating $testType server")
    val server = serverFactoryMap(testType).createServer()
    serverOpt = Some(server)
    server.setup()

    Logger.logInfo("Server setup done")
    val awaited = Await.ready(executeTestCase(folder), Duration.Inf)

    awaited andThen {
      case Success(_) =>
        Logger.logSuccess(s"Test passed!!")
    } andThen {
      case Failure(exception) =>
        Logger.logError(exception.toString)

    } andThen {
      case _ =>
        server.tearDown()
        Logger.lineBreak(50)
        sys.exit(1)
    }
  }


  def executeTestCase(folder: File)(implicit postProcessor: PostProcessor): Future[Unit] = {
    cluster flatMap { cluster =>
      if (serverOpt.isEmpty) {
        throw new IllegalStateException("Set up the server first!!!")
      }
      Logger.logInfo(folder.toString)
      val dataStream = StreamTestUtil.createDataStream(folder)


      Logger.logInfo("Datastream created")
      val sink = TestSink()
      val expectedOutput = ExpectedOutputTestUtil.processFilesInTestFolder(folder.toString).toSet.flatten
      TestSink.setExpectedTriples(Sanitizer.sanitize(expectedOutput))
      Logger.logInfo("sink created")
      dataStream.addSink(sink)

      Logger.logInfo("Sink added")
      val eventualJobID = StreamTestUtil.submitJobToCluster(cluster, dataStream, folder.getName)
      Await.result(eventualJobID, Duration.Inf)
      val jobID = eventualJobID.value.get.get
      Logger.logInfo(s"Cluster job $jobID started")


      val inputData = StreamDataSourceTestUtil.processFilesInTestFolder(folder.toString)

      Logger.logInfo(s"Start reading input data")
      TestSink.startCountDown(10 second)

      Logger.logInfo(inputData.toString())
      serverOpt.get.writeData(inputData)

      Logger.logInfo("Input Data sent to server")


      TestSink.sinkFuture flatMap {
        _ =>
          Logger.logInfo( s"Sink's promise completion status: ${TestSink.sinkPromise.isCompleted}")
          StreamingTestMain.compareResults(folder, TestSink.getTriples.filter(!_.isEmpty))
          TestSink.reset()
          val waitfor = resetTestStates(jobID, cluster)
          waitfor.get
          //Await.result(resetTestStates(jobID, cluster), Duration.Inf)
          Logger.logInfo(s"Cluster job $jobID done")
          Future.successful()
      }
    }
  }

  def resetTestStates(jobID: JobID, cluster: MiniCluster): CompletableFuture[Acknowledge] = {

    // Cancel the job
    StreamTestUtil.cancelJob(jobID, cluster)
  }

  def compareResults(folder: File, unsanitizedOutput: List[String]): Unit = {

    var expectedOutputs: Set[String] = ExpectedOutputTestUtil.processFilesInTestFolder(folder.toString).toSet.flatten
    expectedOutputs = Sanitizer.sanitize(expectedOutputs)
    val generatedOutputs = Sanitizer.sanitize(unsanitizedOutput)


    Logger.logInfo(List("Generated output: ", generatedOutputs.mkString("\n")).mkString("\n"))
    Logger.logInfo(List("Expected Output: ", expectedOutputs.mkString("\n")).mkString("\n"))


    /**
      * Check if the generated triple is in the expected output.
      */

    Logger.logInfo("Generated size: " + generatedOutputs.size)

    val errorMsgMismatch = Array("Generated output does not match expected output",
      "Expected: ", expectedOutputs.mkString("\n"),
      "Generated: ", generatedOutputs.mkString("\n"),
      s"Test case: ${folder.getName}").mkString("\n")


    if (expectedOutputs.nonEmpty && expectedOutputs.size > generatedOutputs.size) {
      errorMsgMismatch.split("\n").foreach(Logger.logError)
      return
    }

    for (generatedTriple <- generatedOutputs) {
      if (!expectedOutputs.contains(generatedTriple)) {
        errorMsgMismatch.split("\n").foreach(Logger.logError)
        return
      }
    }

    Logger.logSuccess(s"Testcase ${folder.getName} passed streaming test!")
  }
}
