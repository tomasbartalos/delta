/*
 * Copyright 2019 Databricks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta

import org.apache.spark.sql.delta.DeltaOperations.Truncate
import org.apache.spark.sql.delta.actions.{AddFile, Metadata, SetTransaction, Action}
import org.apache.hadoop.fs.Path

import org.apache.spark.sql.{QueryTest, Row}
import org.apache.spark.sql.execution.FileSourceScanExec
import org.apache.spark.sql.test.SharedSparkSession
import DeltaConfigs.ENABLE_CONCURRENT_PARTITIONS_WRITE

class OptimisticTransactionSuite extends QueryTest with SharedSparkSession {
  private val addA = AddFile("a", Map.empty, 1, 1, dataChange = true)
  private val addB = AddFile("b", Map.empty, 1, 1, dataChange = true)
  private val addC = AddFile("c", Map.empty, 1, 1, dataChange = true)

  import testImplicits._

  test("block append against metadata change") {
    withTempDir { tempDir =>
      val log = DeltaLog(spark, new Path(tempDir.getCanonicalPath))
      // Initialize the log. Truncate() is just a no-op placeholder.
      log.startTransaction().commit(Nil, Truncate())

      val txn = log.startTransaction()
      val winningTxn = log.startTransaction()
      winningTxn.commit(Metadata() :: Nil, Truncate())
      intercept[MetadataChangedException] {
        txn.commit(addA :: Nil, Truncate())
      }
    }
  }

  test("block read+append against append") {
    withTempDir { tempDir =>
      val log = DeltaLog(spark, new Path(tempDir.getCanonicalPath))
      // Initialize the log. Truncate() is just a no-op placeholder.
      log.startTransaction().commit(Metadata() :: Nil, Truncate())

      val txn = log.startTransaction()
      // reads the table
      txn.filterFiles()
      val winningTxn = log.startTransaction()
      winningTxn.commit(addA :: Nil, Truncate())
      // TODO: intercept a more specific exception
      intercept[DeltaConcurrentModificationException] {
        txn.commit(addB :: Nil, Truncate())
      }
    }
  }

  test("allow blind-append against any data change") {
    withTempDir { tempDir =>
      val log = DeltaLog(spark, new Path(tempDir.getCanonicalPath))
      // Initialize the log and add data. Truncate() is just a no-op placeholder.
      log.startTransaction().commit(addA :: Nil, Truncate())

      val txn = log.startTransaction()
      val winningTxn = log.startTransaction()
      winningTxn.commit(addA.remove :: addB :: Nil, Truncate())
      txn.commit(addC :: Nil, Truncate())
      checkAnswer(log.update().allFiles.select("path"), Row("b") :: Row("c") :: Nil)
    }
  }

  test("allow read+append+delete against no data change") {
    withTempDir { tempDir =>
      val log = DeltaLog(spark, new Path(tempDir.getCanonicalPath))
      // Initialize the log and add data. Truncate() is just a no-op placeholder.
      log.startTransaction().commit(addA :: Nil, Truncate())

      val txn = log.startTransaction()
      txn.filterFiles()
      val winningTxn = log.startTransaction()
      winningTxn.commit(Nil, Truncate())
      txn.commit(addA.remove :: addB :: Nil, Truncate())
      checkAnswer(log.update().allFiles.select("path"), Row("b") :: Nil)
    }
  }

  val A_P1 = "part=1/a"
  val B_P1 = "part=1/b"
  val C_P1 = "part=1/c"
  val C_P2 = "part=2/c"
  val D_P2 = "part=2/d"
  val E_P3 = "part=3/e"
  val F_P3 = "part=3/f"
  val G_P4 = "part=4/g"

  private val addA_P1 = AddFile(A_P1, Map("part" -> "1"), 1, 1, dataChange = true)
  private val addB_P1 = AddFile(B_P1, Map("part" -> "1"), 1, 1, dataChange = true)
  private val addC_P1 = AddFile(C_P1, Map("part" -> "1"), 1, 1, dataChange = true)
  private val addC_P2 = AddFile(C_P2, Map("part" -> "2"), 1, 1, dataChange = true)
  private val addD_P2 = AddFile(D_P2, Map("part" -> "2"), 1, 1, dataChange = true)
  private val addE_P3 = AddFile(E_P3, Map("part" -> "3"), 1, 1, dataChange = true)
  private val addF_P3 = AddFile(F_P3, Map("part" -> "3"), 1, 1, dataChange = true)
  private val addG_P4 = AddFile(G_P4, Map("part" -> "4"), 1, 1, dataChange = true)

  test("block concurrent commit when table is not configured") {
    withLog(addA_P1 :: addD_P2 :: addD_P2.remove :: Nil) { log =>
      val tx1 = log.startTransaction()
      // TX1 reads A_P1
      tx1.filterFiles(('part === 1).expr :: Nil)

      val tx2 = log.startTransaction()
      tx2.filterFiles()
      // TX2 modifies P2
      tx2.commit(addC_P2 :: addD_P2.remove :: Nil, Truncate())

      intercept[ConcurrentWriteException] {
        // this is safe however table was not configured for concurrent writes
        tx1.commit(addE_P3 :: addF_P3 :: Nil, Truncate())
      }
    }
  }

  test("allow concurrent commit on disjoint partitions") {
    withConcurrentLog(addA_P1 :: addD_P2 :: addE_P3 :: addD_P2.remove :: Nil) { log =>
      val tx1 = log.startTransaction()
      // TX1 reads P3 (but not P1)
      val tx1Read = tx1.filterFiles(('part === 3).expr :: Nil)
      assert(tx1Read.map(_.path) == E_P3 :: Nil)

      val tx2 = log.startTransaction()
      tx2.filterFiles()
      // TX2 modifies only P1
      tx2.commit(addB_P1 :: Nil, Truncate())

      // free to commit because P1 modified by TX2 was not read
      tx1.commit(addC_P2 :: addE_P3.remove :: Nil, Truncate())
      checkAnswer(log.update().allFiles.select("path"),
        // start (E_P3 was removed by TX1)
        Row(A_P1) ::
        // TX2
        Row(B_P1) ::
        // TX1
        Row(C_P2) :: Nil)
    }
  }

  test("allow concurrent commit on disjoint partitions reading all partitions") {
    withConcurrentLog(addA_P1 :: addD_P2 :: addD_P2.remove :: Nil) { log =>
      val tx1 = log.startTransaction()
      // TX1 read P1
      tx1.filterFiles(('part isin (1, 2)).expr :: Nil)

      val tx2 = log.startTransaction()
      tx2.filterFiles()
      tx2.commit(addC_P2 :: addD_P2.remove :: Nil, Truncate())

      tx1.commit(addE_P3 :: addF_P3 :: Nil, Truncate())

      checkAnswer(log.update().allFiles.select("path"),
        // start
        Row(A_P1) ::
        // TX2
        Row(C_P2) ::
        // TX1
        Row(E_P3) :: Row(F_P3) :: Nil)
    }
  }

  test("block concurrent commit when read was modified") {
    withConcurrentLog(addA_P1 :: addD_P2 :: addE_P3 :: addD_P2.remove :: Nil) { log =>
      val tx1 = log.startTransaction()
      // TX1 reads only P1
      val tx1Read = tx1.filterFiles(('part === 1).expr :: Nil)
      assert(tx1Read.map(_.path) == A_P1 :: Nil)

      val tx2 = log.startTransaction()
      tx2.filterFiles()
      // TX2 modifies only P1
      tx2.commit(addB_P1 :: Nil, Truncate())

      intercept[ConcurrentWriteException] {
        // P1 was modified
        tx1.commit(addC_P2 :: addE_P3 :: Nil, Truncate())
      }
    }
  }

  test("block concurrent commit on full table scan") {
    withConcurrentLog(addA_P1 :: addD_P2 :: addD_P2.remove :: Nil) { log =>
      val tx1 = log.startTransaction()
      // TX1 full table scan
      tx1.filterFiles()
      tx1.filterFiles(('part === 1).expr :: Nil)

      val tx2 = log.startTransaction()
      tx2.filterFiles()
      tx2.commit(addC_P2 :: addD_P2.remove :: Nil, Truncate())

      intercept[ConcurrentWriteException] {
        tx1.commit(addE_P3 :: addF_P3 :: Nil, Truncate())
      }
    }
  }

  val A_1_1 = "a=1/b=1/a"
  val B_1_2 = "a=1/b=2/b"
  val C_2_1 = "a=2/b=1/c"
  val D_3_1 = "a=3/b=1/d"

  val addA_1_1_nested = AddFile(A_1_1, Map("a" -> "1", "b" -> "1"),
    1, 1, dataChange = true)
  val addB_1_2_nested = AddFile(B_1_2, Map("a" -> "1", "b" -> "2"),
    1, 1, dataChange = true)
  val addC_2_1_nested = AddFile(C_2_1, Map("a" -> "2", "b" -> "1"),
    1, 1, dataChange = true)
  val addD_3_1_nested = AddFile(D_3_1, Map("a" -> "3", "b" -> "1"),
    1, 1, dataChange = true)

  test("allow concurrent commit on nested partitions") {
    withConcurrentLog(addA_1_1_nested :: Nil, partitionCols = "a" :: "b" :: Nil) { log =>
      val tx1 = log.startTransaction()
      // TX1 reads a=1/b=1
      val tx1Read = tx1.filterFiles(('a === 1 and 'b === 1).expr :: Nil)
      assert(tx1Read.map(_.path) == A_1_1 :: Nil)

      val tx2 = log.startTransaction()
      tx2.filterFiles()
      // TX2 modifies only a=1/b=2
      tx2.commit(addB_1_2_nested :: Nil, Truncate())

      // free to commit a=2/b=1
      tx1.commit(addC_2_1_nested :: Nil, Truncate())
      checkAnswer(log.update().allFiles.select("path"),
        // start
        Row(A_1_1) ::
        // TX2
        Row(B_1_2) ::
        // TX1
        Row(C_2_1) :: Nil)
    }
  }

  test("block concurrent commit on nested partitions conflicting add") {
    withConcurrentLog(addA_1_1_nested :: Nil, partitionCols = "a" :: "b" :: Nil) { log =>
      val tx1 = log.startTransaction()
      // TX1 reads a=1/b=1
      val tx1Read = tx1.filterFiles(('a === 1 and 'b === 1).expr :: Nil)
      assert(tx1Read.map(_.path) == A_1_1 :: Nil)

      val tx2 = log.startTransaction()
      tx2.filterFiles()
      // TX2 append a=1/b=2
      tx2.commit(addB_1_2_nested :: Nil, Truncate())

      intercept[ConcurrentWriteException] {
        val add = AddFile("a=1/b=2/x", Map("a" -> "1", "b" -> "2"),
          1, 1, dataChange = true)
        // TX1 append a=1/b=2
        tx1.commit(add :: Nil, Truncate())
      }
    }
  }

  test("allow concurrent commit read one level in nested partitions") {
    withConcurrentLog(addA_1_1_nested :: addB_1_2_nested :: Nil,
      partitionCols = "a" :: "b" :: Nil) { log =>
        val tx1 = log.startTransaction()
        val tx1Read = tx1.filterFiles(('a === 1).expr :: Nil)
        assert(tx1Read.map(_.path).toSet == Set(A_1_1, B_1_2))

        val tx2 = log.startTransaction()
        tx2.filterFiles()
        // TX2 modifies only a=2/b=1
        tx2.commit(addC_2_1_nested :: Nil, Truncate())

        // free to commit a=2/b=1
        tx1.commit(addD_3_1_nested :: Nil, Truncate())
        checkAnswer(log.update().allFiles.select("path"),
          // start
          Row(A_1_1) :: Row(B_1_2) ::
          // TX2
          Row(C_2_1) ::
          // TX1
          Row(D_3_1) :: Nil)
    }
  }

  test("block concurrent commit read one level in nested partitions") {
    withConcurrentLog(addA_1_1_nested :: addB_1_2_nested :: Nil,
      partitionCols = "a" :: "b" :: Nil) { log =>

      val tx1 = log.startTransaction()
      val tx1Read = tx1.filterFiles(('a === 1).expr :: Nil)
      assert(tx1Read.map(_.path).toSet == Set(A_1_1, B_1_2))

      val tx2 = log.startTransaction()
      tx2.filterFiles()
      // TX2 modifies a=1/b=1
      tx2.commit(addA_1_1_nested.remove :: Nil, Truncate())

      intercept[ConcurrentWriteException] {
        // TX2 modified a=1/b1, which was read by TX1
        tx1.commit(addD_3_1_nested :: Nil, Truncate())
      }
    }
  }

  test("block concurrent commit on nested partitions full table scan") {
    withConcurrentLog(addA_1_1_nested :: Nil, partitionCols = "a" :: "b" :: Nil) { log =>
      val tx1 = log.startTransaction()
      // TX1 full table scan
      tx1.filterFiles()

      val tx2 = log.startTransaction()
      tx2.filterFiles()
      // TX2 modifies only a=1/b=2
      tx2.commit(addB_1_2_nested :: Nil, Truncate())

      intercept[ConcurrentWriteException] {
        tx1.commit(addC_2_1_nested :: Nil, Truncate())
      }
    }
  }

  test("block concurrent commit on nested partitions conflicting read") {
    withConcurrentLog(addA_1_1_nested :: Nil,
      partitionCols = "a" :: "b" :: Nil) { log =>

      val tx1 = log.startTransaction()
      val tx1Read = tx1.filterFiles(('a >= 1 or 'b > 1).expr :: Nil)
      assert(tx1Read.map(_.path).toSet == Set(A_1_1))

      val tx2 = log.startTransaction()
      tx2.filterFiles()
      // TX2 modifies a=1/b=2
      tx2.commit(addB_1_2_nested :: Nil, Truncate())

      intercept[ConcurrentWriteException] {
        // partition a=1/b=2 conflicts with our read a >= 1 or 'b > 1
        tx1.commit(addD_3_1_nested :: Nil, Truncate())
      }
    }
  }

  test("block concurrent commit on nested partitions conflict add & remove") {
    val addA_10_nested = AddFile("a=1/b=0/a", Map("a" -> "1", "b" -> "0"),
      1, 1, dataChange = true)
    val addB_12_nested = AddFile("a=1/b=2/b", Map("a" -> "1", "b" -> "2"),
      1, 1, dataChange = true)
    withConcurrentLog(addA_10_nested :: Nil) { log =>
      val tx1 = log.startTransaction()
      // TX1 reads a=1/b=0
      tx1.filterFiles()

      val tx2 = log.startTransaction()
      tx2.filterFiles()
      // TX2 modifies a=1/b=2
      tx2.commit(addB_12_nested :: Nil, Truncate())

      intercept[ConcurrentWriteException] {
        // a=1/b=2 was manipulated by TX2
        tx1.commit(addB_12_nested.remove :: Nil, Truncate())
      }
    }
  }

  test("block concurrent commit on Delete attempt and Add initially empty") {
    withConcurrentLog(addC_P2 :: addE_P3 :: Nil) { log =>
      val tx1 = log.startTransaction()
      // empty read
      val tx1Read = tx1.filterFiles(('part === 1).expr :: Nil)
      assert(tx1Read.isEmpty)
      // tx2 deletes "part=p1"
      val tx2 = log.startTransaction()
      // empty read
      val tx2Read = tx2.filterFiles(('part === 1).expr :: Nil)
      assert(tx2Read.isEmpty)

      tx1.commit(addA_P1 :: Nil, Truncate())

      intercept[ConcurrentWriteException] {
        // tx1 modified P1 which we tried to read and could try to delete
        tx2.commit(Seq.empty, Truncate())
      }
    }
  }

  test("allow concurrent commit on Delete and Add disjoint partitions initially empty") {
    withConcurrentLog(addC_P2 :: addE_P3 :: Nil) { log =>
      // tx1 deletes "part=p1"
      val tx1 = log.startTransaction()
      // empty read
      val tx1Read = tx1.filterFiles(('part === 1).expr :: Nil)
      assert(tx1Read.isEmpty)

      // tx2 deletes "part=p1"
      val tx2 = log.startTransaction()
      // empty read
      val tx2Read = tx2.filterFiles(('part === 1).expr :: Nil)
      assert(tx2Read.isEmpty)

      tx1.commit(addD_P2 :: Nil, Truncate())
      // empty delete
      tx2.commit(Seq.empty, Truncate())

      checkAnswer(log.update().allFiles.select("path"),
        // start
        Row(C_P2) :: Row(E_P3) ::
        // TX1
        Row(D_P2) :: Nil)
    }
  }

  test("block concurrent commit when added conflicting partitions") {
    withConcurrentLog(addA_P1 :: Nil) { log =>
      val tx1 = log.startTransaction()
      tx1.filterFiles()

      val tx2 = log.startTransaction()
      tx2.filterFiles()
      tx2.commit(addC_P2.remove :: addB_P1 :: Nil, Truncate())

      intercept[ConcurrentWriteException] {
        // P1 is manipulated in both TX1 and TX2
        tx1.commit(addD_P2 :: Nil, Truncate())
      }
    }
  }

  test("block concurrent commit when delete conflicting partitions") {
    withConcurrentLog(addC_P2 :: Nil) { log =>
      val tx1 = log.startTransaction()
      tx1.filterFiles()

      val tx2 = log.startTransaction()
      tx2.filterFiles()
      tx2.commit(addA_P1.remove :: Nil, Truncate())

      intercept[ConcurrentWriteException] {
        // P1 read by TX1 was modified
        tx1.commit(addB_P1.remove :: Nil, Truncate())
      }
    }
  }

  test("block concurrent replaceWhere initial empty") {
    withConcurrentLog(addA_P1 :: Nil) { log =>
      val tx1 = log.startTransaction()
      // replaceWhere (part >= 2) -> empty read
      val tx1Read = tx1.filterFiles(('part >= 2).expr :: Nil)
      assert(tx1Read.isEmpty)
      val tx2 = log.startTransaction()
      // replaceWhere (part >= 2) -> empty read
      val tx2Read = tx2.filterFiles(('part >= 2).expr :: Nil)
      assert(tx2Read.isEmpty)

      tx1.commit(addC_P2 :: Nil, Truncate())
      intercept[ConcurrentWriteException] {
        // Tx1 have modified P2 which conflicts with our read (part >= 2)
        tx2.commit(addE_P3 :: Nil, Truncate())
      }
    }
  }

  test("allow concurrent replaceWhere disjoint partitions initial empty") {
    withConcurrentLog(addA_P1 :: Nil) { log =>
      val tx1 = log.startTransaction()
      // replaceWhere (part > 2 and part <= 3) -> empty read
      val tx1Read = tx1.filterFiles(('part > 1 and 'part <= 3).expr :: Nil)
      assert(tx1Read.isEmpty)

      val tx2 = log.startTransaction()
      // replaceWhere (part > 3) -> empty read
      val tx2Read = tx2.filterFiles(('part > 3).expr :: Nil)
      assert(tx2Read.isEmpty)

      tx1.commit(addC_P2 :: Nil, Truncate())
      // P2 doesn't conflict with read predicate (part > 3)
      tx2.commit(addG_P4 :: Nil, Truncate())
      checkAnswer(log.update().allFiles.select("path"),
        // start
        Row(A_P1) ::
        // TX1
        Row(C_P2) ::
        // TX2
        Row(G_P4) :: Nil)
    }
  }

  test("block concurrent replaceWhere NOT empty but conflicting predicate") {
    withConcurrentLog(addA_P1 :: addG_P4 :: Nil) { log =>
      val tx1 = log.startTransaction()
      // replaceWhere (part <= 3) -> read P1
      val tx1Read = tx1.filterFiles(('part <= 3).expr :: Nil)
      assert(tx1Read.map(_.path) == A_P1 :: Nil)
      val tx2 = log.startTransaction()
      // replaceWhere (part >= 2) -> read P4
      val tx2Read = tx2.filterFiles(('part >= 2).expr :: Nil)
      assert(tx2Read.map(_.path) == G_P4 :: Nil)

      tx1.commit(addA_P1.remove :: addC_P2 :: Nil, Truncate())
      intercept[ConcurrentWriteException] {
        // Tx1 have modified P2 which conflicts with our read (part >= 2)
        tx2.commit(addG_P4.remove :: addE_P3 :: Nil, Truncate())
      }
    }
  }

  test("block concurrent commit on read and remove") {
    withConcurrentLog(addA_P1 :: Nil) { log =>
      val tx1 = log.startTransaction()
      tx1.filterFiles(('part <= 3).expr :: Nil)

      val tx2 = log.startTransaction()
      tx2.filterFiles()
      tx2.commit(addA_P1.remove :: Nil, Truncate())

      intercept[ConcurrentWriteException] {
        tx1.commit(addG_P4 :: Nil, Truncate())
      }
    }
  }

  test("block concurrent commit on read & add conflicting partitions") {
    withConcurrentLog(addA_P1 :: Nil) { log =>
      val tx1 = log.startTransaction()
      // read P1
      tx1.filterFiles(('part === 1).expr :: Nil)

      // tx2 commits before tx1
      val tx2 = log.startTransaction()
      tx2.filterFiles()
      tx2.commit(addB_P1 :: Nil, Truncate())

      intercept[ConcurrentWriteException] {
        // P1 read by TX1 was modified by TX2
        tx1.commit(addE_P3 :: Nil, Truncate())
      }
    }
  }

  test("block concurrent commit on read & delete conflicting partitions") {
    withConcurrentLog(addA_P1 :: addB_P1 :: Nil) { log =>
      val tx1 = log.startTransaction()
      // read P1
      tx1.filterFiles(('part === 1).expr :: Nil)

      // tx2 commits before tx1
      val tx2 = log.startTransaction()
      tx2.filterFiles()
      tx2.commit(addA_P1.remove :: Nil, Truncate())

      intercept[ConcurrentWriteException] {
        // P1 read by TX1 was removed by TX2
        tx1.commit(addE_P3 :: Nil, Truncate())
      }
    }
  }

  test("block 2 concurrent replaceWhere transactions") {
    withConcurrentLog(addA_P1 :: Nil) { log =>
      val tx1 = log.startTransaction()
      // read P1
      tx1.filterFiles(('part === 1).expr :: Nil)

      val tx2 = log.startTransaction()
      // read P1
      tx2.filterFiles(('part === 1).expr :: Nil)

      // tx1 commits before tx2
      tx1.commit(addA_P1.remove :: addB_P1 :: Nil, Truncate())

      intercept[ConcurrentWriteException] {
        // P1 read & deleted by TX1 is being modified by TX2
        tx2.commit(addA_P1.remove :: addC_P1 :: Nil, Truncate())
      }
    }
  }

  test("block 2 concurrent replaceWhere transactions changing partitions") {
    withConcurrentLog(addA_P1 :: addC_P2 :: addE_P3 :: Nil) { log =>
      val tx1 = log.startTransaction()
      // read P3
      tx1.filterFiles(('part === 3 or 'part === 1).expr :: Nil)

      val tx2 = log.startTransaction()
      // read P3
      tx2.filterFiles(('part === 3 or 'part === 2).expr :: Nil)

      // tx1 commits before tx2
      tx1.commit(addA_P1.remove :: addE_P3.remove :: addB_P1 :: Nil, Truncate())

      intercept[ConcurrentWriteException] {
        // P3 read & deleted by TX1 is being modified by TX2
        tx2.commit(addC_P2.remove :: addE_P3.remove :: addD_P2 :: Nil, Truncate())
      }
    }
  }

  test("block concurrent full table scan and remove") {
    withConcurrentLog(addA_P1 :: addC_P2 :: addE_P3 :: Nil) { log =>
      val tx1 = log.startTransaction()

      val tx2 = log.startTransaction()
      tx2.filterFiles()
      tx2.commit(addC_P2 :: Nil, Truncate())

      tx1.filterFiles(('part === 1).expr :: Nil)
      // full table scan
      tx1.filterFiles()

      intercept[ConcurrentWriteException] {
        tx1.commit(addA_P1.remove :: Nil, Truncate())
      }
    }
  }

  test("block concurrent commit mixed metadata and data predicate") {
    withConcurrentLog(addA_P1 :: addC_P2 :: addE_P3 :: Nil) { log =>
      val tx1 = log.startTransaction()

      val tx2 = log.startTransaction()
      tx2.filterFiles()
      tx2.commit(addC_P2 :: Nil, Truncate())

      // actually a full table scan
      tx1.filterFiles(('part === 1 or 'year > 2019).expr :: Nil)

      intercept[ConcurrentWriteException] {
        tx1.commit(addA_P1.remove :: Nil, Truncate())
      }
    }
  }

  test("block concurrent read (2 scans) and add ") {
    withConcurrentLog(addA_P1 :: addE_P3 :: Nil) { log =>
      val tx1 = log.startTransaction()
      tx1.filterFiles(('part === 1).expr :: Nil)

      val tx2 = log.startTransaction()
      tx2.filterFiles()
      tx2.commit(addC_P2 :: Nil, Truncate())

      tx1.filterFiles(('part > 1 and 'part < 3).expr :: Nil)

      intercept[ConcurrentWriteException] {
        // P2 added by TX2 conflicts with our read condition 'part > 1 and 'part < 3
        tx1.commit(addA_P1.remove :: Nil, Truncate())
      }
    }
  }

  test("allow concurrent set-txns with different app ids") {
    withTempDir { tempDir =>
      val log = DeltaLog(spark, new Path(tempDir.getCanonicalPath))
      // Initialize the log and add data. Truncate() is just a no-op placeholder.
      log.startTransaction().commit(Nil, Truncate())

      val txn = log.startTransaction()
      txn.txnVersion("t1")
      val winningTxn = log.startTransaction()
      winningTxn.commit(SetTransaction("t2", 1, Some(1234L)) :: Nil, Truncate())
      txn.commit(Nil, Truncate())

      assert(log.update().transactions === Map("t2" -> 1))
    }
  }

  test("block concurrent set-txns with the same app id") {
    withTempDir { tempDir =>
      val log = DeltaLog(spark, new Path(tempDir.getCanonicalPath))
      // Initialize the log and add data. Truncate() is just a no-op placeholder.
      log.startTransaction().commit(Nil, Truncate())

      val txn = log.startTransaction()
      txn.txnVersion("t1")
      val winningTxn = log.startTransaction()
      winningTxn.commit(SetTransaction("t1", 1, Some(1234L)) :: Nil, Truncate())

      intercept[ConcurrentTransactionException] {
        txn.commit(Nil, Truncate())
      }
    }
  }

  test("query with predicates should skip partitions") {
    withTempDir { tempDir =>
      val testPath = tempDir.getCanonicalPath
      spark.range(2)
        .map(_.toInt)
        .withColumn("part", $"value" % 2)
        .write
        .format("delta")
        .partitionBy("part")
        .mode("append")
        .save(testPath)

      val query = spark.read.format("delta").load(testPath).where("part = 1")
      val fileScans = query.queryExecution.executedPlan.collect {
        case f: FileSourceScanExec => f
      }
      assert(fileScans.size == 1)
      assert(fileScans.head.metadata.get("PartitionCount").contains("1"))
      checkAnswer(query, Seq(Row(1, 1)))
    }
  }

  def withConcurrentLog(actions: Seq[Action],
                        partitionCols: Seq[String] = "part" :: Nil)
                       (test: DeltaLog => Unit): Unit = {
    val hasMetadata = actions.exists {
      case _: Metadata => true
      case _ => false
    }
    var actionWithMetaData = actions
    if (!hasMetadata) {
      actionWithMetaData = actions :+
        Metadata(configuration = Map(ENABLE_CONCURRENT_PARTITIONS_WRITE.key -> "true"),
          partitionColumns = partitionCols
        )
    }
    withLog(actionWithMetaData)(test)
  }

  def withLog(actions: Seq[Action])(test: DeltaLog => Unit): Unit = {
    withTempDir { tempDir =>
      val log = DeltaLog(spark, new Path(tempDir.getCanonicalPath))
      // Initialize the log and add data. Truncate() is just a no-op placeholder.
      log.startTransaction().commit(actions, Truncate())
      test(log)
    }
  }
}
