package distsort.worker

import distsort.proto.distsort._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import io.grpc.stub.StreamObserver
import com.google.protobuf.ByteString
import java.nio.file.{Files, Paths}
import java.io.File
import scala.collection.mutable.ArrayBuffer

/**
 * Unit tests for WorkerService
 */
class WorkerServiceSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var tempDir: File = _
  private var service: WorkerService = _

  override def beforeEach(): Unit = {
    tempDir = Files.createTempDirectory("worker-test").toFile
    service = new WorkerService("test-worker", tempDir.getAbsolutePath)
  }

  override def afterEach(): Unit = {
    if (tempDir != null && tempDir.exists()) {
      deleteRecursively(tempDir)
    }
  }

  "WorkerService" should "report correct worker status" in {
    val statusResponse = Await.result(
      service.getWorkerStatus(WorkerStatusRequest("master")),
      5.seconds
    )

    statusResponse.workerId shouldBe "test-worker"
    statusResponse.currentPhase shouldBe WorkerPhase.PHASE_INITIALIZING
    statusResponse.recordsProcessed shouldBe 0
    statusResponse.partitionsReceived shouldBe 0
    statusResponse.partitionsSent shouldBe 0
    statusResponse.isHealthy shouldBe true
  }

  it should "handle phase transitions correctly" in {
    // Valid transition
    val response1 = Await.result(
      service.startPhase(StartPhaseRequest(WorkerPhase.PHASE_SAMPLING, "Start sampling")),
      5.seconds
    )
    response1.started shouldBe true
    service.getCurrentPhase shouldBe WorkerPhase.PHASE_SAMPLING

    // Invalid transition
    val response2 = Await.result(
      service.startPhase(StartPhaseRequest(WorkerPhase.PHASE_MERGING, "Skip to merge")),
      5.seconds
    )
    response2.started shouldBe false
    response2.message should include("Cannot transition")
  }

  it should "validate all phase transitions" in {
    val validTransitions = Seq(
      (WorkerPhase.PHASE_INITIALIZING, WorkerPhase.PHASE_SAMPLING, true),
      (WorkerPhase.PHASE_SAMPLING, WorkerPhase.PHASE_WAITING_FOR_PARTITIONS, true),
      (WorkerPhase.PHASE_WAITING_FOR_PARTITIONS, WorkerPhase.PHASE_SORTING, true),
      (WorkerPhase.PHASE_SORTING, WorkerPhase.PHASE_SHUFFLING, true),
      (WorkerPhase.PHASE_SHUFFLING, WorkerPhase.PHASE_MERGING, true),
      (WorkerPhase.PHASE_MERGING, WorkerPhase.PHASE_COMPLETED, true)
    )

    validTransitions.foreach { case (from, to, expectedValid) =>
      service.setPhase(from)
      val response = Await.result(
        service.startPhase(StartPhaseRequest(to, s"Transition to $to")),
        5.seconds
      )
      response.started shouldBe expectedValid
      if (expectedValid) {
        service.getCurrentPhase shouldBe to
      }
    }
  }

  it should "allow transition to FAILED from any state" in {
    val phases = Seq(
      WorkerPhase.PHASE_INITIALIZING,
      WorkerPhase.PHASE_SAMPLING,
      WorkerPhase.PHASE_SORTING,
      WorkerPhase.PHASE_SHUFFLING,
      WorkerPhase.PHASE_MERGING
    )

    phases.foreach { phase =>
      service.setPhase(phase)
      val response = Await.result(
        service.startPhase(StartPhaseRequest(WorkerPhase.PHASE_FAILED, "Error occurred")),
        5.seconds
      )
      response.started shouldBe true
      service.getCurrentPhase shouldBe WorkerPhase.PHASE_FAILED
    }
  }

  it should "allow restart from FAILED state" in {
    service.setPhase(WorkerPhase.PHASE_FAILED)
    val response = Await.result(
      service.startPhase(StartPhaseRequest(WorkerPhase.PHASE_INITIALIZING, "Restarting")),
      5.seconds
    )
    response.started shouldBe true
    service.getCurrentPhase shouldBe WorkerPhase.PHASE_INITIALIZING
  }

  it should "receive shuffle data stream correctly" in {
    val receivedResponses = ArrayBuffer[ShuffleResponse]()
    val errorReceived = ArrayBuffer[Throwable]()
    var completed = false

    val responseObserver = new StreamObserver[ShuffleResponse] {
      override def onNext(value: ShuffleResponse): Unit = {
        receivedResponses += value
      }
      override def onError(t: Throwable): Unit = {
        errorReceived += t
      }
      override def onCompleted(): Unit = {
        completed = true
      }
    }

    val requestObserver = service.receiveShuffleData(responseObserver)

    // Send some shuffle data
    val data1 = Array[Byte](1, 2, 3, 4, 5)
    requestObserver.onNext(ShuffleRequest(
      "sender-1", "test-worker", 0, ByteString.copyFrom(data1), 0, false
    ))

    Thread.sleep(100) // Let processing happen

    // Check acknowledgment
    receivedResponses should have length 1
    receivedResponses.head.acknowledged shouldBe true
    receivedResponses.head.bufferCapacity should be > 0

    // Send more data with last chunk
    val data2 = Array[Byte](6, 7, 8, 9, 10)
    requestObserver.onNext(ShuffleRequest(
      "sender-1", "test-worker", 0, ByteString.copyFrom(data2), 1, true
    ))

    Thread.sleep(100)

    // Complete the stream
    requestObserver.onCompleted()

    Thread.sleep(100)

    // Verify
    completed shouldBe true
    service.getPartitionsReceived shouldBe 1
    receivedResponses should have length 2
  }

  it should "handle shuffle data with wrong sequence number" in {
    val responseObserver = new TestStreamObserver[ShuffleResponse]()
    val requestObserver = service.receiveShuffleData(responseObserver)

    // Send with sequence number 1 (should be 0)
    requestObserver.onNext(ShuffleRequest(
      "sender-1", "test-worker", 0,
      ByteString.copyFrom(Array[Byte](1, 2, 3)),
      1, // Wrong sequence number
      false
    ))

    Thread.sleep(100)

    // Should get negative acknowledgment
    responseObserver.values should have length 1
    responseObserver.values.head.acknowledged shouldBe false
  }

  it should "handle multiple partitions concurrently" in {
    val responseObserver = new TestStreamObserver[ShuffleResponse]()
    val requestObserver = service.receiveShuffleData(responseObserver)

    // Send data for different partitions
    for (partitionId <- 0 to 2) {
      requestObserver.onNext(ShuffleRequest(
        "sender-1", "test-worker", partitionId,
        ByteString.copyFrom(Array.fill(100)(partitionId.toByte)),
        0, true
      ))
    }

    Thread.sleep(500)

    // Should have received all partitions
    service.getPartitionsReceived shouldBe 3
    responseObserver.values.filter(_.acknowledged) should have length 3
  }

  it should "apply backpressure when buffer is full" in {
    val service = new WorkerService("test-worker", tempDir.getAbsolutePath, maxShuffleBufferMB = 1)
    val responseObserver = new TestStreamObserver[ShuffleResponse]()
    val requestObserver = service.receiveShuffleData(responseObserver)

    // Send large amount of data
    val largeData = Array.fill(512 * 1024)(1.toByte) // 512KB
    for (i <- 0 to 3) { // 2MB total
      requestObserver.onNext(ShuffleRequest(
        "sender-1", "test-worker", i,
        ByteString.copyFrom(largeData),
        0, false
      ))
    }

    Thread.sleep(1000)

    // Check buffer capacity in responses
    val capacities = responseObserver.values.map(_.bufferCapacity)
    capacities.last should be <= capacities.head // Capacity should decrease
  }

  it should "write received shuffle data to files" in {
    val responseObserver = new TestStreamObserver[ShuffleResponse]()
    val requestObserver = service.receiveShuffleData(responseObserver)

    val testData = Array[Byte](1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
    requestObserver.onNext(ShuffleRequest(
      "sender-1", "test-worker", 42,
      ByteString.copyFrom(testData),
      0, true // Mark as last chunk
    ))

    Thread.sleep(500)

    // Check file was created
    val receivedFiles = service.getReceivedFiles
    receivedFiles should have length 1
    receivedFiles.head.getName should include("partition.42")

    // Verify file content
    val fileContent = Files.readAllBytes(receivedFiles.head.toPath)
    fileContent shouldBe testData
  }

  it should "handle stream errors gracefully" in {
    val responseObserver = new TestStreamObserver[ShuffleResponse]()
    val requestObserver = service.receiveShuffleData(responseObserver)

    // Send some data
    requestObserver.onNext(ShuffleRequest(
      "sender-1", "test-worker", 0,
      ByteString.copyFrom(Array[Byte](1, 2, 3)),
      0, false
    ))

    // Simulate error
    requestObserver.onError(new RuntimeException("Network error"))

    Thread.sleep(100)

    // Service should handle error gracefully
    service.getCurrentPhase should not be WorkerPhase.PHASE_FAILED
  }

  it should "track statistics correctly" in {
    service.incrementRecordsProcessed(100)
    service.getRecordsProcessed shouldBe 100

    service.incrementPartitionsSent(5)
    service.getPartitionsSent shouldBe 5

    val status = Await.result(
      service.getWorkerStatus(WorkerStatusRequest("master")),
      5.seconds
    )

    status.recordsProcessed shouldBe 100
    status.partitionsSent shouldBe 5
  }

  it should "maintain health status" in {
    // Initially healthy
    var status = Await.result(
      service.getWorkerStatus(WorkerStatusRequest("master")),
      5.seconds
    )
    status.isHealthy shouldBe true

    // Still healthy after some time
    Thread.sleep(100)
    status = Await.result(
      service.getWorkerStatus(WorkerStatusRequest("master")),
      5.seconds
    )
    status.isHealthy shouldBe true
  }

  // Helper classes and methods

  class TestStreamObserver[T] extends StreamObserver[T] {
    val values = ArrayBuffer[T]()
    var error: Option[Throwable] = None
    var completed = false

    override def onNext(value: T): Unit = values += value
    override def onError(t: Throwable): Unit = error = Some(t)
    override def onCompleted(): Unit = completed = true
  }

  private def deleteRecursively(file: File): Unit = {
    if (file.isDirectory) {
      file.listFiles().foreach(deleteRecursively)
    }
    file.delete()
  }
}