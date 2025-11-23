package distsort.shuffle

import distsort.core.Record
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Client interface for communicating with other workers
 * Used by ShuffleManager to send partition data
 */
trait WorkerClient {
  /**
   * Send a partition to this worker
   *
   * @param partitionId The partition ID being sent
   * @param data Iterator of records to send
   * @return Future indicating success/failure
   */
  def sendPartition(partitionId: Int, data: Iterator[Record]): Future[Boolean]

  /**
   * Store a replica of a partition
   *
   * @param partitionId The partition ID to store
   * @param records The records to store as a replica
   * @return Future indicating success/failure
   */
  def storeReplica(partitionId: Int, records: Seq[Record]): Future[Boolean] = {
    // Default implementation: use sendPartition for backward compatibility
    sendPartition(partitionId, records.iterator)
  }

  /**
   * Retrieve a replica of a partition
   *
   * @param partitionId The partition ID to retrieve
   * @return Future with the partition records
   */
  def getReplica(partitionId: Int): Future[Seq[Record]] = {
    // Default implementation: not supported
    Future.failed(new UnsupportedOperationException("Replica retrieval not supported"))
  }

  /**
   * Close the client connection
   */
  def close(): Unit
}