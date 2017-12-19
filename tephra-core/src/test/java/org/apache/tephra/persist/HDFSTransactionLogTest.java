/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tephra.persist;

import com.google.common.io.Closeables;
import com.google.common.primitives.Longs;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.tephra.TxConstants;
import org.apache.tephra.metrics.MetricsCollector;
import org.apache.tephra.metrics.TxMetricsCollector;
import org.apache.tephra.util.TransactionEditUtil;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;


/**
 * Testing for complete and partial sycs of {@link TransactionEdit} to {@link HDFSTransactionLog}
 */
public class HDFSTransactionLogTest {
  @ClassRule
  public static final TemporaryFolder TMP_FOLDER = new TemporaryFolder();
  private static final String LOG_FILE_PREFIX = "txlog.";

  private static MiniDFSCluster dfsCluster;
  private static Configuration conf;
  private static MetricsCollector metricsCollector;

  @BeforeClass
  public static void setupBeforeClass() throws Exception {
    Configuration hConf = new Configuration();
    hConf.set(MiniDFSCluster.HDFS_MINIDFS_BASEDIR, TMP_FOLDER.newFolder().getAbsolutePath());

    dfsCluster = new MiniDFSCluster.Builder(hConf).numDataNodes(1).build();
    conf = new Configuration(dfsCluster.getFileSystem().getConf());
    metricsCollector = new TxMetricsCollector();
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    dfsCluster.shutdown();
  }

  private Configuration getConfiguration() throws IOException {
    // tests should use the current user for HDFS
    conf.unset(TxConstants.Manager.CFG_TX_HDFS_USER);
    conf.set(TxConstants.Manager.CFG_TX_SNAPSHOT_DIR, TMP_FOLDER.newFolder().getAbsolutePath());
    return conf;
  }

  private HDFSTransactionLog getHDFSTransactionLog(Configuration conf,
                                                   FileSystem fs, long timeInMillis) throws Exception {
    String snapshotDir = conf.get(TxConstants.Manager.CFG_TX_SNAPSHOT_DIR);
    Path newLog = new Path(snapshotDir, LOG_FILE_PREFIX + timeInMillis);
    return new HDFSTransactionLog(fs, conf, newLog, timeInMillis, metricsCollector);
  }

  private SequenceFile.Writer getSequenceFileWriter(Configuration configuration, FileSystem fs,
                                                    long timeInMillis, byte versionNumber) throws IOException {
    String snapshotDir = configuration.get(TxConstants.Manager.CFG_TX_SNAPSHOT_DIR);
    Path newLog = new Path(snapshotDir, LOG_FILE_PREFIX + timeInMillis);
    SequenceFile.Metadata metadata = new SequenceFile.Metadata();
    if (versionNumber > 1) {
      metadata.set(new Text(TxConstants.TransactionLog.VERSION_KEY),
                   new Text(Byte.toString(versionNumber)));
    }

    switch (versionNumber) {
      case 1:
      case 2:
        return SequenceFile.createWriter(fs, configuration, newLog, LongWritable.class,
                                         co.cask.tephra.persist.TransactionEdit.class,
                                         SequenceFile.CompressionType.NONE, null, null, metadata);
      default:
        return SequenceFile.createWriter(fs, configuration, newLog, LongWritable.class,
                                         TransactionEdit.class, SequenceFile.CompressionType.NONE,
                                         null, null, metadata);
    }
  }

  private void writeNumWrites(SequenceFile.Writer writer, final int size) throws Exception {
    String key = TxConstants.TransactionLog.NUM_ENTRIES_APPENDED;
    CommitMarkerCodec.writeMarker(writer, size);
  }

  private void testCaskTransactionLogSync(int totalCount, int batchSize, byte versionNumber, boolean isComplete)
    throws Exception {
    List<co.cask.tephra.persist.TransactionEdit> edits = TransactionEditUtil.createRandomCaskEdits(totalCount);
    long timestamp = System.currentTimeMillis();
    Configuration configuration = getConfiguration();
    FileSystem fs = FileSystem.newInstance(FileSystem.getDefaultUri(configuration), configuration);
    SequenceFile.Writer writer = getSequenceFileWriter(configuration, fs, timestamp, versionNumber);
    AtomicLong logSequence = new AtomicLong();
    HDFSTransactionLog transactionLog = getHDFSTransactionLog(configuration, fs, timestamp);
    AbstractTransactionLog.CaskEntry entry;

    for (int i = 0; i < totalCount - batchSize; i += batchSize) {
      if (versionNumber > 1) {
        writeNumWrites(writer, batchSize);
      }
      for (int j = 0; j < batchSize; j++) {
        entry = new AbstractTransactionLog.CaskEntry(new LongWritable(logSequence.getAndIncrement()), edits.get(j));
        writer.append(entry.getKey(), entry.getEdit());
      }
      writer.syncFs();
    }

    if (versionNumber > 1) {
      writeNumWrites(writer, batchSize);
    }

    for (int i = totalCount - batchSize; i < totalCount - 1; i++) {
      entry = new AbstractTransactionLog.CaskEntry(new LongWritable(logSequence.getAndIncrement()), edits.get(i));
      writer.append(entry.getKey(), entry.getEdit());
    }

    entry = new AbstractTransactionLog.CaskEntry(new LongWritable(logSequence.getAndIncrement()),
                                                 edits.get(totalCount - 1));
    if (isComplete) {
      writer.append(entry.getKey(), entry.getEdit());
    } else {
      byte[] bytes = Longs.toByteArray(entry.getKey().get());
      writer.appendRaw(bytes, 0, bytes.length, new SequenceFile.ValueBytes() {
        @Override
        public void writeUncompressedBytes(DataOutputStream outStream) throws IOException {
          byte[] test = new byte[]{0x2};
          outStream.write(test, 0, 1);
        }

        @Override
        public void writeCompressedBytes(DataOutputStream outStream) throws IllegalArgumentException, IOException {
          // no-op
        }

        @Override
        public int getSize() {
          // mimic size longer than the actual byte array size written, so we would reach EOF
          return 12;
        }
      });
    }
    writer.syncFs();
    Closeables.closeQuietly(writer);

    // now let's try to read this log
    TransactionLogReader reader = transactionLog.getReader();
    int syncedEdits = 0;
    while (reader.next() != null) {
      // testing reading the transaction edits
      syncedEdits++;
    }
    if (isComplete) {
      Assert.assertEquals(totalCount, syncedEdits);
    } else {
      Assert.assertEquals(totalCount - batchSize, syncedEdits);
    }
  }

  private void testTransactionLogSync(int totalCount, int batchSize, byte versionNumber, boolean isComplete)
    throws Exception {
    List<TransactionEdit> edits = TransactionEditUtil.createRandomEdits(totalCount);
    long timestamp = System.currentTimeMillis();
    Configuration configuration = getConfiguration();
    FileSystem fs = FileSystem.newInstance(FileSystem.getDefaultUri(configuration), configuration);
    SequenceFile.Writer writer = getSequenceFileWriter(configuration, fs, timestamp, versionNumber);
    AtomicLong logSequence = new AtomicLong();
    HDFSTransactionLog transactionLog = getHDFSTransactionLog(configuration, fs, timestamp);
    AbstractTransactionLog.Entry entry;

    for (int i = 0; i < totalCount - batchSize; i += batchSize) {
      writeNumWrites(writer, batchSize);
      for (int j = 0; j < batchSize; j++) {
        entry = new AbstractTransactionLog.Entry(new LongWritable(logSequence.getAndIncrement()), edits.get(j));
        writer.append(entry.getKey(), entry.getEdit());
      }
      writer.syncFs();
    }

    writeNumWrites(writer, batchSize);
    for (int i = totalCount - batchSize; i < totalCount - 1; i++) {
      entry = new AbstractTransactionLog.Entry(new LongWritable(logSequence.getAndIncrement()), edits.get(i));
      writer.append(entry.getKey(), entry.getEdit());
    }

    entry = new AbstractTransactionLog.Entry(new LongWritable(logSequence.getAndIncrement()),
                                             edits.get(totalCount - 1));
    if (isComplete) {
      writer.append(entry.getKey(), entry.getEdit());
    } else {
      byte[] bytes = Longs.toByteArray(entry.getKey().get());
      writer.appendRaw(bytes, 0, bytes.length, new SequenceFile.ValueBytes() {
        @Override
        public void writeUncompressedBytes(DataOutputStream outStream) throws IOException {
          byte[] test = new byte[]{0x2};
          outStream.write(test, 0, 1);
        }

        @Override
        public void writeCompressedBytes(DataOutputStream outStream) throws IllegalArgumentException, IOException {
          // no-op
        }

        @Override
        public int getSize() {
          // mimic size longer than the actual byte array size written, so we would reach EOF
          return 12;
        }
      });
    }
    writer.syncFs();
    Closeables.closeQuietly(writer);

    // now let's try to read this log
    TransactionLogReader reader = transactionLog.getReader();
    int syncedEdits = 0;
    while (reader.next() != null) {
      // testing reading the transaction edits
      syncedEdits++;
    }
    if (isComplete) {
      Assert.assertEquals(totalCount, syncedEdits);
    } else {
      Assert.assertEquals(totalCount - batchSize, syncedEdits);
    }
  }

  @Test
  public void testTransactionLogVersion3() throws Exception {
    // in-complete sync
    testTransactionLogSync(1000, 1, (byte) 3, false);
    testTransactionLogSync(2000, 5, (byte) 3, false);

    // complete sync
    testTransactionLogSync(1000, 1, (byte) 3, true);
    testTransactionLogSync(2000, 5, (byte) 3, true);
  }

  @Test
  public void testTransactionLogVersion2() throws Exception {
    // in-complete sync
    testCaskTransactionLogSync(1000, 1, (byte) 2, false);
    testCaskTransactionLogSync(2000, 5, (byte) 2, false);

    // complete sync
    testCaskTransactionLogSync(1000, 1, (byte) 2, true);
    testCaskTransactionLogSync(2000, 5, (byte) 2, true);
  }

  @Test
  public void testTransactionLogOldVersion() throws Exception {
    // in-complete sync
    testCaskTransactionLogSync(1000, 1, (byte) 1, false);

    // complete sync
    testCaskTransactionLogSync(2000, 5, (byte) 1, true);
  }
}
