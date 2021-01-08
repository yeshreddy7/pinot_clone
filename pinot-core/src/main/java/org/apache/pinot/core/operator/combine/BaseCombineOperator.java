/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.core.operator.combine;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.pinot.common.exception.QueryException;
import org.apache.pinot.core.common.Operator;
import org.apache.pinot.core.operator.BaseOperator;
import org.apache.pinot.core.operator.blocks.IntermediateResultsBlock;
import org.apache.pinot.core.query.exception.EarlyTerminationException;
import org.apache.pinot.core.query.request.context.QueryContext;
import org.apache.pinot.core.util.trace.TraceRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Base implementation of the combine operator.
 * <p>Combine operator uses multiple worker threads to process segments in parallel, and uses the main thread to merge
 * the results blocks from the processed segments. It can early-terminate the query to save the system resources if it
 * detects that the merged results can already satisfy the query, or the query is already errored out or timed out.
 */
@SuppressWarnings("rawtypes")
public abstract class BaseCombineOperator extends BaseOperator<IntermediateResultsBlock> {
  protected static final Logger LOGGER = LoggerFactory.getLogger(BaseCombineOperator.class);

  protected final List<Operator> _operators;
  protected final QueryContext _queryContext;
  protected final ExecutorService _executorService;
  protected final long _endTimeMs;

  public BaseCombineOperator(List<Operator> operators, QueryContext queryContext, ExecutorService executorService,
      long endTimeMs) {
    _operators = operators;
    _queryContext = queryContext;
    _executorService = executorService;
    _endTimeMs = endTimeMs;
  }

  @Override
  protected IntermediateResultsBlock getNextBlock() {
    int numOperators = _operators.size();
    int numThreads = CombineOperatorUtils.getNumThreadsForQuery(numOperators);

    // Use a BlockingQueue to store the per-segment result
    BlockingQueue<IntermediateResultsBlock> blockingQueue = new ArrayBlockingQueue<>(numOperators);
    // Use a Phaser to ensure all the Futures are done (not scheduled, finished or interrupted) before the main thread
    // returns. We need to ensure this because the main thread holds the reference to the segments. If a segment is
    // deleted/refreshed, the segment will be released after the main thread returns, which would lead to undefined
    // behavior (even JVM crash) when processing queries against it.
    Phaser phaser = new Phaser(1);

    Future[] futures = new Future[numThreads];
    for (int i = 0; i < numThreads; i++) {
      int threadIndex = i;
      futures[i] = _executorService.submit(new TraceRunnable() {
        @Override
        public void runJob() {
          try {
            // Register the thread to the phaser
            // NOTE: If the phaser is terminated (returning negative value) when trying to register the thread, that
            //       means the query execution has finished, and the main thread has deregistered itself and returned
            //       the result. Directly return as no execution result will be taken.
            if (phaser.register() < 0) {
              return;
            }

            for (int operatorIndex = threadIndex; operatorIndex < numOperators; operatorIndex += numThreads) {
              try {
                IntermediateResultsBlock resultsBlock =
                    (IntermediateResultsBlock) _operators.get(operatorIndex).nextBlock();
                if (isQuerySatisfied(resultsBlock)) {
                  // Query is satisfied, skip processing the remaining segments
                  blockingQueue.offer(resultsBlock);
                  return;
                } else {
                  blockingQueue.offer(resultsBlock);
                }
              } catch (EarlyTerminationException e) {
                // Early-terminated by interruption (canceled by the main thread)
                return;
              } catch (Exception e) {
                // Caught exception, skip processing the remaining operators
                LOGGER.error("Caught exception while executing operator of index: {} (query: {})", operatorIndex,
                    _queryContext, e);
                blockingQueue.offer(new IntermediateResultsBlock(e));
                return;
              }
            }
          } finally {
            phaser.arriveAndDeregister();
          }
        }
      });
    }

    IntermediateResultsBlock mergedBlock = null;
    try {
      int numBlocksMerged = 0;
      while (numBlocksMerged < numOperators) {
        IntermediateResultsBlock blockToMerge =
            blockingQueue.poll(_endTimeMs - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        if (blockToMerge == null) {
          // Query times out, skip merging the remaining results blocks
          LOGGER.error("Timed out while polling results block, numBlocksMerged: {} (query: {})", numBlocksMerged,
              _queryContext);
          mergedBlock = new IntermediateResultsBlock(QueryException.getException(QueryException.EXECUTION_TIMEOUT_ERROR,
              new TimeoutException("Timed out while polling results block")));
          break;
        }
        if (blockToMerge.getProcessingExceptions() != null) {
          // Caught exception while processing segment, skip merging the remaining results blocks and directly return
          // the exception
          mergedBlock = blockToMerge;
          break;
        }
        if (mergedBlock == null) {
          mergedBlock = blockToMerge;
        } else {
          mergeResultsBlocks(mergedBlock, blockToMerge);
        }
        numBlocksMerged++;
        if (isQuerySatisfied(mergedBlock)) {
          // Query is satisfied, skip merging the remaining results blocks
          break;
        }
      }
    } catch (Exception e) {
      LOGGER.error("Caught exception while merging results blocks (query: {})", _queryContext, e);
      mergedBlock = new IntermediateResultsBlock(QueryException.getException(QueryException.INTERNAL_ERROR, e));
    } finally {
      // Cancel all ongoing jobs
      for (Future future : futures) {
        if (!future.isDone()) {
          future.cancel(true);
        }
      }
      // Deregister the main thread and wait for all threads done
      phaser.awaitAdvance(phaser.arriveAndDeregister());
    }

    CombineOperatorUtils.setExecutionStatistics(mergedBlock, _operators);
    return mergedBlock;
  }

  /**
   * Can be overridden for early termination.
   */
  protected boolean isQuerySatisfied(IntermediateResultsBlock resultsBlock) {
    return false;
  }

  /**
   * Merge an IntermediateResultsBlock into the main IntermediateResultsBlock.
   * <p>NOTE: {@code blockToMerge} should contain the result for a segment without any exception. The errored segment
   * result is already handled.
   */
  protected abstract void mergeResultsBlocks(IntermediateResultsBlock mergedBlock,
      IntermediateResultsBlock blockToMerge);
}
