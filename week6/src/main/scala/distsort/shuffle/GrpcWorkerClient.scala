package distsort.shuffle

import distsort.core.Record
import distsort.proto.distsort._
import io.grpc.{ManagedChannel, ManagedChannelBuilder, StatusRuntimeException}
import io.grpc.stub.StreamObserver
import com.google.protobuf.ByteString
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}
import java.util.concurrent.TimeUnit

/**
 * gRPC-based implementation of WorkerClient
 * Communicates with other workers using gRPC streaming
 *
 * Based on docs/3-grpc-sequences.md Phase 6: Worker-to-Worker shuffle
 */
class GrpcWorkerClient(
    targetHost: String,
    targetPort: Int,
    maxRetries: Int = 3
)(implicit ec: ExecutionContext) extends WorkerClient {

  private val logger = LoggerFactory.getLogger(classOf[GrpcWorkerClient])

  private lazy val channel: ManagedChannel = ManagedChannelBuilder
    .forAddress(targetHost, targetPort)
    .usePlaintext()
    .maxInboundMessageSize(100 * 1024 * 1024) // 100MB
    .build()

  private lazy val stub = WorkerServiceGrpc.stub(channel)

  /**
   * Send a partition to target worker using gRPC streaming
   */
  override def sendPartition(partitionId: Int, data: Iterator[Record]): Future[Boolean] = {
    val promise = Promise[Boolean]()

    logger.debug(s"Sending partition $partitionId to $targetHost:$targetPort")

    // Create response observer
    val responseObserver = new StreamObserver[ShuffleAck] {
      override def onNext(ack: ShuffleAck): Unit = {
        if (ack.success) {
          logger.debug(s"Received ACK for partition $partitionId: ${ack.bytesReceived} bytes")
        } else {
          logger.warn(s"Received NACK for partition $partitionId: ${ack.message}")
        }
      }

      override def onError(t: Throwable): Unit = {
        logger.error(s"Error sending partition $partitionId: ${t.getMessage}", t)
        promise.tryFailure(t)
      }

      override def onCompleted(): Unit = {
        logger.info(s"Successfully sent partition $partitionId to $targetHost:$targetPort")
        promise.trySuccess(true)
      }
    }

    try {
      // Start streaming
      val requestObserver = stub.shuffleData(responseObserver)

      // Send data in chunks
      val chunkSize = 1024 * 1024 // 1MB chunks
      var totalBytes = 0L
      var chunkCount = 0

      data.grouped(chunkSize / 100).foreach { recordBatch =>
        val bytes = recordBatch.flatMap(_.toBytes).toArray
        totalBytes += bytes.length

        val chunk = ShuffleDataChunk(
          partitionId = partitionId,
          data = ByteString.copyFrom(bytes),
          chunkIndex = chunkCount,
          totalBytes = bytes.length,
          isLast = false
        )

        requestObserver.onNext(chunk)
        chunkCount += 1

        logger.trace(s"Sent chunk $chunkCount for partition $partitionId (${bytes.length} bytes)")
      }

      // Send end marker
      val endChunk = ShuffleDataChunk(
        partitionId = partitionId,
        data = ByteString.EMPTY,
        chunkIndex = chunkCount,
        totalBytes = totalBytes,
        isLast = true
      )

      requestObserver.onNext(endChunk)
      requestObserver.onCompleted()

      logger.debug(s"Finished sending partition $partitionId: $totalBytes bytes in $chunkCount chunks")

    } catch {
      case ex: StatusRuntimeException =>
        logger.error(s"gRPC error sending partition $partitionId: ${ex.getStatus}", ex)
        promise.tryFailure(ex)
      case ex: Exception =>
        logger.error(s"Unexpected error sending partition $partitionId", ex)
        promise.tryFailure(ex)
    }

    promise.future
  }

  /**
   * Close the gRPC channel
   */
  override def close(): Unit = {
    Try {
      if (!channel.isShutdown) {
        channel.shutdown()
        if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
          channel.shutdownNow()
          channel.awaitTermination(5, TimeUnit.SECONDS)
        }
        logger.debug(s"Closed connection to $targetHost:$targetPort")
      }
    } match {
      case Success(_) =>
        logger.debug(s"Successfully closed channel to $targetHost:$targetPort")
      case Failure(ex) =>
        logger.warn(s"Error closing channel to $targetHost:$targetPort", ex)
    }
  }
}

/**
 * Factory for creating GrpcWorkerClient instances
 */
object GrpcWorkerClient {
  /**
   * Create a new client for the given worker
   */
  def apply(
      workerInfo: WorkerInfo
  )(implicit ec: ExecutionContext): GrpcWorkerClient = {
    new GrpcWorkerClient(
      targetHost = workerInfo.host,
      targetPort = workerInfo.port
    )
  }

  /**
   * Create clients for all workers except the current one
   */
  def createClients(
      allWorkers: Seq[WorkerInfo],
      currentWorkerIndex: Int
  )(implicit ec: ExecutionContext): Map[Int, WorkerClient] = {
    allWorkers
      .filter(_.workerIndex != currentWorkerIndex)
      .map { info =>
        info.workerIndex -> GrpcWorkerClient(info)
      }
      .toMap
  }
}