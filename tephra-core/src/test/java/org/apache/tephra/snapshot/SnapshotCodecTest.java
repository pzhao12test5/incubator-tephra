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

package org.apache.tephra.snapshot;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.hadoop.conf.Configuration;
import org.apache.tephra.ChangeId;
import org.apache.tephra.Transaction;
import org.apache.tephra.TransactionManager;
import org.apache.tephra.TransactionNotInProgressException;
import org.apache.tephra.TxConstants;
import org.apache.tephra.persist.TransactionSnapshot;
import org.apache.tephra.persist.TransactionStateStorage;
import org.apache.tephra.persist.TransactionVisibilityState;
import org.apache.tephra.runtime.ConfigModule;
import org.apache.tephra.runtime.DiscoveryModules;
import org.apache.tephra.runtime.TransactionModules;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests related to {@link SnapshotCodec} implementations.
 */
public class SnapshotCodecTest {
  @ClassRule
  public static TemporaryFolder tmpDir = new TemporaryFolder();

  @Test
  public void testMinimalDeserialization() throws Exception {
    long now = System.currentTimeMillis();
    long nowWritePointer = now * TxConstants.MAX_TX_PER_MS;
    /*
     * Snapshot consisting of transactions at:
     */
    long tInvalid = nowWritePointer - 5;    // t1 - invalid
    long readPtr = nowWritePointer - 4;     // t2 - here and earlier committed
    long tLong = nowWritePointer - 3;       // t3 - in-progress LONG
    long tCommitted = nowWritePointer - 2;  // t4 - committed, changeset (r1, r2)
    long tShort = nowWritePointer - 1;      // t5 - in-progress SHORT, canCommit called, changeset (r3, r4)

    TreeMap<Long, TransactionManager.InProgressTx> inProgress = Maps.newTreeMap(ImmutableSortedMap.of(
      tLong, new TransactionManager.InProgressTx(readPtr,
                                                 TransactionManager.getTxExpirationFromWritePointer(
                                                   tLong, TxConstants.Manager.DEFAULT_TX_LONG_TIMEOUT),
                                                 TransactionManager.InProgressType.LONG),
      tShort, new TransactionManager.InProgressTx(readPtr, now + 1000, TransactionManager.InProgressType.SHORT)));

    TransactionSnapshot snapshot = new TransactionSnapshot(now, readPtr, nowWritePointer,
                                                           Lists.newArrayList(tInvalid), // invalid
                                                           inProgress, ImmutableMap.<Long, Set<ChangeId>>of(
                                                             tShort, Sets.<ChangeId>newHashSet()),
                                                           ImmutableMap.<Long, Set<ChangeId>>of(
                                                             tCommitted, Sets.<ChangeId>newHashSet()));

    Configuration conf1 = new Configuration();
    conf1.set(TxConstants.Persist.CFG_TX_SNAPHOT_CODEC_CLASSES, SnapshotCodecV4.class.getName());
    SnapshotCodecProvider provider1 = new SnapshotCodecProvider(conf1);

    byte[] byteArray;
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      provider1.encode(out, snapshot);
      byteArray = out.toByteArray();
    }

    // TransactionSnapshot and TransactionVisibilityState decode should pass now
    TransactionSnapshot txSnapshot = provider1.decode(new ByteArrayInputStream(byteArray));
    TransactionVisibilityState txVisibilityState =
      provider1.decodeTransactionVisibilityState(new ByteArrayInputStream(byteArray));
    assertTransactionVisibilityStateEquals(txSnapshot, txVisibilityState);

    // Corrupt the serialization byte array so that full deserialization will fail
    byteArray[byteArray.length - 1] = 'a';

    // TransactionVisibilityState decoding should pass since it doesn't decode the committing and committed changesets.
    TransactionVisibilityState txVisibilityState2 = provider1.decodeTransactionVisibilityState(
      new ByteArrayInputStream(byteArray));
    Assert.assertNotNull(txVisibilityState2);
    Assert.assertEquals(txVisibilityState, txVisibilityState2);
    Assert.assertEquals(readPtr, txVisibilityState2.getReadPointer());
    try {
      provider1.decode(new ByteArrayInputStream(byteArray));
      Assert.fail();
    } catch (RuntimeException e) {
      // expected since we modified the serialization bytes
    }
  }

  /**
   * In-progress LONG transactions written with DefaultSnapshotCodec will not have the type serialized as part of
   * the data.  Since these transactions also contain a non-negative expiration, we need to ensure we reset the type
   * correctly when the snapshot is loaded.
   */
  @Test
  public void testDefaultToV3Compatibility() throws Exception {
    long now = System.currentTimeMillis();
    long nowWritePointer = now * TxConstants.MAX_TX_PER_MS;
    /*
     * Snapshot consisting of transactions at:
     */
    long tInvalid = nowWritePointer - 5;    // t1 - invalid
    long readPtr = nowWritePointer - 4;     // t2 - here and earlier committed
    long tLong = nowWritePointer - 3;       // t3 - in-progress LONG
    long tCommitted = nowWritePointer - 2;  // t4 - committed, changeset (r1, r2)
    long tShort = nowWritePointer - 1;      // t5 - in-progress SHORT, canCommit called, changeset (r3, r4)

    TreeMap<Long, TransactionManager.InProgressTx> inProgress = Maps.newTreeMap(ImmutableSortedMap.of(
        tLong, new TransactionManager.InProgressTx(
          readPtr,
          TransactionManager.getTxExpirationFromWritePointer(tLong, TxConstants.Manager.DEFAULT_TX_LONG_TIMEOUT),
          TransactionManager.InProgressType.LONG),
        tShort, new TransactionManager.InProgressTx(readPtr, now + 1000, TransactionManager.InProgressType.SHORT)));

    TransactionSnapshot snapshot = new TransactionSnapshot(now, readPtr, nowWritePointer,
        Lists.newArrayList(tInvalid), // invalid
        inProgress,
        ImmutableMap.<Long, Set<ChangeId>>of(
            tShort, Sets.newHashSet(new ChangeId(new byte[]{'r', '3'}), new ChangeId(new byte[]{'r', '4'}))),
        ImmutableMap.<Long, Set<ChangeId>>of(
            tCommitted, Sets.newHashSet(new ChangeId(new byte[]{'r', '1'}), new ChangeId(new byte[]{'r', '2'}))));

    Configuration conf1 = new Configuration();
    conf1.set(TxConstants.Persist.CFG_TX_SNAPHOT_CODEC_CLASSES, DefaultSnapshotCodec.class.getName());
    SnapshotCodecProvider provider1 = new SnapshotCodecProvider(conf1);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      provider1.encode(out, snapshot);
    } finally {
      out.close();
    }

    TransactionSnapshot snapshot2 = provider1.decode(new ByteArrayInputStream(out.toByteArray()));
    TransactionVisibilityState minTxSnapshot = provider1.decodeTransactionVisibilityState(
      new ByteArrayInputStream(out.toByteArray()));
    assertTransactionVisibilityStateEquals(snapshot2, minTxSnapshot);

    assertEquals(snapshot.getReadPointer(), snapshot2.getReadPointer());
    assertEquals(snapshot.getWritePointer(), snapshot2.getWritePointer());
    assertEquals(snapshot.getInvalid(), snapshot2.getInvalid());
    // in-progress transactions will have missing types
    assertNotEquals(snapshot.getInProgress(), snapshot2.getInProgress());
    assertEquals(snapshot.getCommittingChangeSets(), snapshot2.getCommittingChangeSets());
    assertEquals(snapshot.getCommittedChangeSets(), snapshot2.getCommittedChangeSets());

    // after fixing in-progress, full snapshot should match
    Map<Long, TransactionManager.InProgressTx> fixedInProgress = TransactionManager.txnBackwardsCompatCheck(
        TxConstants.Manager.DEFAULT_TX_LONG_TIMEOUT, 10000L, snapshot2.getInProgress());
    assertEquals(snapshot.getInProgress(), fixedInProgress);
    assertEquals(snapshot, snapshot2);
  }

  /**
   * Test full stack serialization for a TransactionManager migrating from DefaultSnapshotCodec to SnapshotCodecV3.
   */
  @Test
  public void testDefaultToV3Migration() throws Exception {
    File testDir = tmpDir.newFolder("testDefaultToV3Migration");
    Configuration conf = new Configuration();
    conf.set(TxConstants.Persist.CFG_TX_SNAPHOT_CODEC_CLASSES, DefaultSnapshotCodec.class.getName());
    conf.set(TxConstants.Manager.CFG_TX_SNAPSHOT_LOCAL_DIR, testDir.getAbsolutePath());

    Injector injector = Guice.createInjector(new ConfigModule(conf),
        new DiscoveryModules().getSingleNodeModules(), new TransactionModules().getSingleNodeModules());

    TransactionManager txManager = injector.getInstance(TransactionManager.class);
    txManager.startAndWait();

    txManager.startLong();

    // shutdown to force a snapshot
    txManager.stopAndWait();

    TransactionStateStorage txStorage = injector.getInstance(TransactionStateStorage.class);
    txStorage.startAndWait();

    // confirm that the in-progress entry is missing a type
    TransactionSnapshot snapshot = txStorage.getLatestSnapshot();
    TransactionVisibilityState txVisibilityState = txStorage.getLatestTransactionVisibilityState();
    assertTransactionVisibilityStateEquals(snapshot, txVisibilityState);
    assertNotNull(snapshot);
    assertEquals(1, snapshot.getInProgress().size());
    Map.Entry<Long, TransactionManager.InProgressTx> entry =
        snapshot.getInProgress().entrySet().iterator().next();
    assertNull(entry.getValue().getType());
    txStorage.stopAndWait();


    // start a new Tx manager to test fixup
    Configuration conf2 = new Configuration();
    conf2.set(TxConstants.Manager.CFG_TX_SNAPSHOT_LOCAL_DIR, testDir.getAbsolutePath());
    conf2.setStrings(TxConstants.Persist.CFG_TX_SNAPHOT_CODEC_CLASSES,
                     DefaultSnapshotCodec.class.getName(), SnapshotCodecV3.class.getName());
    Injector injector2 = Guice.createInjector(new ConfigModule(conf2),
        new DiscoveryModules().getSingleNodeModules(), new TransactionModules().getSingleNodeModules());

    TransactionManager txManager2 = injector2.getInstance(TransactionManager.class);
    txManager2.startAndWait();

    // state should be recovered
    TransactionSnapshot snapshot2 = txManager2.getCurrentState();
    assertEquals(1, snapshot2.getInProgress().size());
    Map.Entry<Long, TransactionManager.InProgressTx> inProgressTx =
        snapshot2.getInProgress().entrySet().iterator().next();
    assertEquals(TransactionManager.InProgressType.LONG, inProgressTx.getValue().getType());

    // save a new snapshot
    txManager2.stopAndWait();

    TransactionStateStorage txStorage2 = injector2.getInstance(TransactionStateStorage.class);
    txStorage2.startAndWait();

    TransactionSnapshot snapshot3 = txStorage2.getLatestSnapshot();
    // full snapshot should have deserialized correctly without any fixups
    assertEquals(snapshot2.getInProgress(), snapshot3.getInProgress());
    assertEquals(snapshot2, snapshot3);
    txStorage2.stopAndWait();
  }

  @Test
  public void testSnapshotCodecProviderConfiguration() throws Exception {
    Configuration conf = new Configuration(false);
    StringBuilder buf = new StringBuilder();
    for (Class c : TxConstants.Persist.DEFAULT_TX_SNAPHOT_CODEC_CLASSES) {
      if (buf.length() > 0) {
        buf.append(",\n    ");
      }
      buf.append(c.getName());
    }
    conf.set(TxConstants.Persist.CFG_TX_SNAPHOT_CODEC_CLASSES, buf.toString());

    SnapshotCodecProvider codecProvider = new SnapshotCodecProvider(conf);
    SnapshotCodec v1codec = codecProvider.getCodecForVersion(new DefaultSnapshotCodec().getVersion());
    assertNotNull(v1codec);
    assertTrue(v1codec instanceof DefaultSnapshotCodec);

    SnapshotCodec v2codec = codecProvider.getCodecForVersion(new SnapshotCodecV2().getVersion());
    assertNotNull(v2codec);
    assertTrue(v2codec instanceof SnapshotCodecV2);

    SnapshotCodec v3codec = codecProvider.getCodecForVersion(new SnapshotCodecV3().getVersion());
    assertNotNull(v3codec);
    assertTrue(v3codec instanceof SnapshotCodecV3);

    SnapshotCodec v4codec = codecProvider.getCodecForVersion(new SnapshotCodecV4().getVersion());
    assertNotNull(v4codec);
    assertTrue(v4codec instanceof SnapshotCodecV4);
  }

  @Test
  public void testSnapshotCodecV4() throws IOException, TransactionNotInProgressException {
    File testDir = tmpDir.newFolder("testSnapshotCodecV4");
    Configuration conf = new Configuration();
    conf.set(TxConstants.Persist.CFG_TX_SNAPHOT_CODEC_CLASSES, SnapshotCodecV4.class.getName());
    conf.set(TxConstants.Manager.CFG_TX_SNAPSHOT_LOCAL_DIR, testDir.getAbsolutePath());

    Injector injector = Guice.createInjector(new ConfigModule(conf),
                                             new DiscoveryModules().getSingleNodeModules(),
                                             new TransactionModules().getSingleNodeModules());

    TransactionManager txManager = injector.getInstance(TransactionManager.class);
    txManager.startAndWait();

    // Create a transaction and a checkpoint transaction
    Transaction transaction = txManager.startLong();
    Transaction checkpointTx = txManager.checkpoint(transaction);

    // create invalid transactions (invalidated out of order)
    Transaction shortTx1 = txManager.startShort();
    Transaction shortTx2 = txManager.startShort();
    Transaction shortTx3 = txManager.startShort();
    txManager.invalidate(shortTx3.getTransactionId());
    txManager.invalidate(shortTx1.getTransactionId());
    txManager.invalidate(shortTx2.getTransactionId());

    // shutdown to force a snapshot
    txManager.stopAndWait();

    // Validate the snapshot on disk
    TransactionStateStorage txStorage = injector.getInstance(TransactionStateStorage.class);
    txStorage.startAndWait();

    TransactionSnapshot snapshot = txStorage.getLatestSnapshot();
    Assert.assertTrue(Ordering.natural().isOrdered((snapshot.getInvalid())));
    TransactionVisibilityState txVisibilityState = txStorage.getLatestTransactionVisibilityState();
    assertTransactionVisibilityStateEquals(snapshot, txVisibilityState);

    Map<Long, TransactionManager.InProgressTx> inProgress = snapshot.getInProgress();
    Assert.assertEquals(2, inProgress.size());

    TransactionManager.InProgressTx inProgressTx = inProgress.get(transaction.getTransactionId());
    Assert.assertNotNull(inProgressTx);
    Assert.assertArrayEquals(checkpointTx.getCheckpointWritePointers(),
                             inProgressTx.getCheckpointWritePointers().toLongArray());

    inProgressTx = inProgress.get(checkpointTx.getWritePointer());
    Assert.assertNotNull(inProgressTx);
    Assert.assertEquals(TransactionManager.InProgressType.CHECKPOINT, inProgressTx.getType());
    Assert.assertTrue(inProgressTx.getCheckpointWritePointers().isEmpty());

    txStorage.stopAndWait();

    // start a new Tx manager to see if the transaction is restored correctly.
    Injector injector2 = Guice.createInjector(new ConfigModule(conf),
                                              new DiscoveryModules().getSingleNodeModules(),
                                              new TransactionModules().getSingleNodeModules());

    txManager = injector2.getInstance(TransactionManager.class);
    txManager.startAndWait();

    // state should be recovered
    snapshot = txManager.getCurrentState();
    Assert.assertTrue(Ordering.natural().isOrdered((snapshot.getInvalid())));
    inProgress = snapshot.getInProgress();
    Assert.assertEquals(2, inProgress.size());

    inProgressTx = inProgress.get(transaction.getTransactionId());
    Assert.assertNotNull(inProgressTx);
    Assert.assertArrayEquals(checkpointTx.getCheckpointWritePointers(),
                             inProgressTx.getCheckpointWritePointers().toLongArray());

    inProgressTx = inProgress.get(checkpointTx.getWritePointer());
    Assert.assertNotNull(inProgressTx);
    Assert.assertEquals(TransactionManager.InProgressType.CHECKPOINT, inProgressTx.getType());
    Assert.assertTrue(inProgressTx.getCheckpointWritePointers().isEmpty());

    // Should be able to commit the transaction
    Assert.assertTrue(txManager.canCommit(checkpointTx, Collections.<byte[]>emptyList()));
    Assert.assertTrue(txManager.commit(checkpointTx));

    // save a new snapshot
    txManager.stopAndWait();

    TransactionStateStorage txStorage2 = injector2.getInstance(TransactionStateStorage.class);
    txStorage2.startAndWait();

    snapshot = txStorage2.getLatestSnapshot();
    Assert.assertTrue(Ordering.natural().isOrdered((snapshot.getInvalid())));
    Assert.assertTrue(snapshot.getInProgress().isEmpty());
    txStorage2.stopAndWait();
  }

  private void assertTransactionVisibilityStateEquals(TransactionVisibilityState expected,
                                                      TransactionVisibilityState input) {
    Assert.assertEquals(expected.getTimestamp(), input.getTimestamp());
    Assert.assertEquals(expected.getReadPointer(), input.getReadPointer());
    Assert.assertEquals(expected.getWritePointer(), input.getWritePointer());
    Assert.assertEquals(expected.getInProgress(), input.getInProgress());
    Assert.assertEquals(expected.getInvalid(), input.getInvalid());
  }
}
