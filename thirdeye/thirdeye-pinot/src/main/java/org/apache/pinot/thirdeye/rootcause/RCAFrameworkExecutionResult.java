/*
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

package org.apache.pinot.thirdeye.rootcause;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Container object for framework execution results. Holds the results aggregated across all
 * pipeline executions as well as the results for each individual pipeline (keyed by pipeline name).
 *
 */
public final class RCAFrameworkExecutionResult {
  private final Set<Entity> results;
  private final Map<String, PipelineResult> pipelineResults;

  public RCAFrameworkExecutionResult(Set<? extends Entity> results, Map<String, PipelineResult> pipelineResults) {
    this.results = new HashSet<>(results);
    this.pipelineResults = pipelineResults;
  }

  /**
   * Returns the flattened results of a framework execution (i.e. the results of the
   * {@code RCAFramework.OUTPUT} pipeline).
   *
   * @return flattened framework execution results
   */
  public Set<Entity> getResults() {
    return results;
  }

  /**
   * Returns the flattened results of a framework execution (i.e. the results of the
   * {@code RCAFramework.OUTPUT} pipeline) in order of descending score.
   *
   * @return sorted flattened framework execution results
   */
  public List<Entity> getResultsSorted() {
    List<Entity> entities = new ArrayList<>(this.results);
    Collections.sort(entities, Entity.HIGHEST_SCORE_FIRST);
    return entities;
  }

  /**
   * Returns a map of sets of results as generated by each individual pipeline during execution.
   * The map is keyed by pipeline name.
   *
   * @return map of pipeline results keyed by pipeline name
   */
  public Map<String, PipelineResult> getPipelineResults() {
    return pipelineResults;
  }
}
