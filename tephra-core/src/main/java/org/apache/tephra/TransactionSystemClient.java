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

package org.apache.tephra;

import java.io.InputStream;
import java.util.Collection;
import java.util.Set;

/**
 * Client talking to transaction system.
 * See also {@link TransactionAware}.
 * todo: explain Omid.
 */
public interface TransactionSystemClient {
  /**
   * Starts new short transaction.
   * @return instance of {@link Transaction}
   */
  // TODO: "short" and "long" are very misleading names. Use transaction attributes like "detect conflicts or not", etc.
  Transaction startShort();

  /**
   * Starts new short transaction.
   * @param timeout the timeout for the transaction
   * @return instance of {@link Transaction}
   * @throws IllegalArgumentException if the provided timeout is negative or exceeds the configured maximum
   */
  Transaction startShort(int timeout);

  /**
   * Starts new long transaction.
   * @return instance of {@link Transaction}
   */
  Transaction startLong();

  // this pre-commit detects conflicts with other transactions committed so far
  // NOTE: the changes set should not change after this operation, this may help us do some extra optimizations
  // NOTE: there should be time constraint on how long does it take to commit changes by the client after this operation
  //       is submitted so that we can cleanup related resources
  // NOTE: as of now you can call this method multiple times, each time the changeSet of tx will be updated. Not sure
  //       if we can call it a feature or a side-affect of implementation. It makes more sense to append changeset, but
  //       before we really need it we don't do it because it will slow down tx manager throughput.

  /**
   * Checks if transaction with the set of changes can be committed. E.g. it can check conflicts with other changes and
   * refuse commit if there are conflicts. It is assumed that no other changes will be done in between this method call
   * and {@link #commit(Transaction)} which may check conflicts again to avoid races.
   * <p/>
   * Since we do conflict detection at commit time as well, this may seem redundant. The idea is to check for conflicts
   * before we persist changes to avoid rollback in case of conflicts as much as possible.
   * NOTE: in some situations we may want to skip this step to save on RPC with a risk of many rollback ops. So by
   *       default we take safe path.
   *
   * @param tx transaction to verify
   * @param changeIds ids of changes made by transaction
   * @return true if transaction can be committed otherwise false
   */
  boolean canCommit(Transaction tx, Collection<byte[]> changeIds) throws TransactionNotInProgressException;

  /**
   * Makes transaction visible. It will again check conflicts of changes submitted previously with
   * {@link #canCommit(Transaction, java.util.Collection)}
   * @param tx transaction to make visible.
   * @return true if transaction can be committed otherwise false
   */
  boolean commit(Transaction tx) throws TransactionNotInProgressException;

  /**
   * Makes transaction visible. You should call it only when all changes of this tx are undone.
   * NOTE: it will not throw {@link TransactionNotInProgressException} if transaction has timed out.
   * @param tx transaction to make visible.
   */
  void abort(Transaction tx);

  /**
   * Makes transaction invalid. You should call it if not all changes of this tx could be undone.
   * NOTE: it will not throw {@link TransactionNotInProgressException} if transaction has timed out.
   * @param tx transaction id to invalidate.
   * @return true if transaction has been successfully invalidated
   */
  boolean invalidate(long tx);

  /**
   * Performs a checkpoint operation on the current transaction, returning a new Transaction instance with the
   * updated state.  A checkpoint operation assigns a new write pointer for the current transaction.
   * @param tx the current transaction to checkpoint
   * @return an updated transaction instance with the new write pointer
   */
  Transaction checkpoint(Transaction tx) throws TransactionNotInProgressException;

  /**
   * Retrieves the state of the transaction manager and send it as a stream. The snapshot will not be persisted.
   * @return an input stream containing an encoded snapshot of the transaction manager
   */
  InputStream getSnapshotInputStream() throws TransactionCouldNotTakeSnapshotException;

  /**
   * Return the status of the transaction Manager
   * @return a String which denotes the status of txManager
   */
  String status();

  /**
   * Resets the state of the transaction manager.
   */
  void resetState();

  /**
   * Removes the given transaction ids from the invalid list. 
   * @param invalidTxIds transaction ids
   * @return true if invalid list got changed, false otherwise
   */
  boolean truncateInvalidTx(Set<Long> invalidTxIds);

  /**
   * Removes all transaction ids started before the given time from invalid list.
   * @param time time in milliseconds
   * @return true if invalid list got changed, false otherwise
   * @throws InvalidTruncateTimeException if there are any in-progress transactions started before given time
   */
  boolean truncateInvalidTxBefore(long time) throws InvalidTruncateTimeException;

  /**
   * @return the size of invalid list
   */
  int getInvalidSize();

  /**
   * Trigger transaction pruning now.
   */
  void pruneNow();
}
